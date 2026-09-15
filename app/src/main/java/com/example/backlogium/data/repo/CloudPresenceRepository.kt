package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.credentials.maskCredential
import com.example.backlogium.data.local.dao.CloudReadDao
import com.example.backlogium.data.local.entity.CloudReadRecord
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.domain.CloudPresenceCurrentState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresenceReconstruction
import com.example.backlogium.domain.CloudPresenceTransition
import com.example.backlogium.domain.TimeProvider
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

enum class CloudReadTrigger {
    SETTINGS_VERIFICATION,
    SETTINGS_MANUAL,
    DIAGNOSTICS,
}

enum class CloudReadFailure {
    UNREACHABLE,
    REJECTED_CREDENTIAL,
    ACCOUNT_MISMATCH,
    UNUSABLE_RESPONSE,
}

data class CloudPresenceConfiguration(
    val endpoint: String,
    val maskedToken: String,
)

data class CloudReadStatus(
    val configured: Boolean,
    val lastAttemptAt: Long?,
    val lastSuccessAt: Long?,
    val lastFailureAt: Long?,
    val lastFailure: CloudReadFailure?,
    val healthy: Boolean?,
)

data class CloudPresenceSnapshot(
    val windowStart: Long,
    val windowEnd: Long,
    val readAt: Long,
    val intervals: List<CloudPresenceInterval>,
    val current: CloudPresenceCurrentState?,
    val observationCount: Int,
    val nextPosition: String?,
    val hasMore: Boolean,
)

sealed interface CloudConfigurationResult {
    data object Saved : CloudConfigurationResult
    data object NoSteamAccount : CloudConfigurationResult
    data object InvalidEndpoint : CloudConfigurationResult
    data object RejectedCredential : CloudConfigurationResult
    data object Unreachable : CloudConfigurationResult
    data object UnusableResponse : CloudConfigurationResult
    data class AccountMismatch(
        val expectedAccount: String,
        val endpointAccount: String,
    ) : CloudConfigurationResult
}

sealed interface CloudReadResult {
    data object Unconfigured : CloudReadResult
    data object NoSteamAccount : CloudReadResult
    data class Success(val snapshot: CloudPresenceSnapshot) : CloudReadResult
    data class Failed(val failure: CloudReadFailure) : CloudReadResult
}

/**
 * Cloud presence is a read-only side channel. It owns encrypted configuration, the resumable
 * watermark, and the current in-memory comparison snapshot, but never writes sessions or progress.
 */
@Singleton
class CloudPresenceRepository @Inject constructor(
    private val api: CloudPresenceApi,
    private val credentialsStore: CloudCredentialsStore,
    private val credentialsProvider: CredentialsProvider,
    private val settings: SettingsRepository,
    private val cloudReadDao: CloudReadDao,
    private val time: TimeProvider,
) {
    private val configurationState = MutableStateFlow<CloudPresenceConfiguration?>(null)
    private val snapshotState = MutableStateFlow<CloudPresenceSnapshot?>(null)
    // Account-change generation: captured atomically with the account-bound inputs under
    // cloudStateMutex, incremented by invalidation/removal and by the account-change fence
    // before and after promotion. A result whose generation changed, or whose current account
    // no longer matches the captured account, is discarded before any persistence, so a late
    // A response cannot repopulate state under account B.
    private val accountGeneration = AtomicLong(0)
    // Serializes post-fetch persistence against invalidation/removal. The fetch itself
    // stays outside the lock so an account switch never blocks on the network.
    private val cloudStateMutex = Mutex()

    val configuration: Flow<CloudPresenceConfiguration?> =
        configurationState.onStart {
            configurationState.value = credentialsStore.readCloudCredentials()?.toConfiguration()
        }

    val snapshot: Flow<CloudPresenceSnapshot?> = snapshotState.asStateFlow()

    val status: Flow<CloudReadStatus> =
        combine(configuration, cloudReadDao.observeRecords()) { configuration, records ->
            val latest = records.firstOrNull()
            val success = records.firstOrNull { it.outcome == CloudReadOutcome.SUCCESS.name }
            val failure = records.firstOrNull { it.outcome != CloudReadOutcome.SUCCESS.name }
            CloudReadStatus(
                configured = configuration != null,
                lastAttemptAt = latest?.at,
                lastSuccessAt = success?.at,
                lastFailureAt = failure?.at,
                lastFailure = failure?.let { outcomeToFailure(it.outcome) },
                healthy = latest?.let { it.outcome == CloudReadOutcome.SUCCESS.name },
            )
        }

    suspend fun refreshConfiguration() {
        cloudStateMutex.withLock {
            configurationState.value = credentialsStore.readCloudCredentials()?.toConfiguration()
        }
    }

    suspend fun verifyAndSave(endpoint: String, token: String): CloudConfigurationResult {
        val normalizedEndpoint = normalizeEndpoint(endpoint) ?: return CloudConfigurationResult.InvalidEndpoint
        val normalizedToken = token.trim().takeIf { it.isNotBlank() }
            ?: return CloudConfigurationResult.RejectedCredential
        // Capture the Steam account and the generation atomically under the same mutex that
        // invalidation owns: sampling them separately would let an A->B bump land between the
        // two reads, capturing old account A with B's new generation so A's late response
        // later sees an unchanged generation and is accepted.
        val capture = cloudStateMutex.withLock {
            VerifyStart(
                account = credentialsProvider.currentCredentials()?.steamId,
                generation = accountGeneration.get(),
            )
        }
        val account = capture.account ?: return CloudConfigurationResult.NoSteamAccount
        val generationAtStart = capture.generation

        return when (val result = fetch(normalizedEndpoint, normalizedToken, account, null)) {
            is RemoteReadResult.Success -> cloudStateMutex.withLock {
                val current = credentialsProvider.currentCredentials()?.steamId
                if (generationAtStart != accountGeneration.get() || current != account) {
                    // The account changed while verifying: A's endpoint must not become B's
                    // configuration, and B's watermark must not be cleared. Report against the
                    // current account without persisting anything. The identity check covers a
                    // read that started after invalidation but before the new Steam identity
                    // was committed/visible, where the generation alone still matches.
                    if (current == null) {
                        return@withLock CloudConfigurationResult.NoSteamAccount
                    }
                    return@withLock CloudConfigurationResult.AccountMismatch(current, result.parsed.account)
                }
                settings.clearCloudReadPosition()
                persistVerifiedConfiguration(
                    credentials = CloudCredentials(normalizedEndpoint, normalizedToken),
                    trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                    parsed = result.parsed,
                )
                CloudConfigurationResult.Saved
            }
            is RemoteReadResult.AccountMismatch -> cloudStateMutex.withLock {
                val current = credentialsProvider.currentCredentials()?.steamId
                if (generationAtStart != accountGeneration.get() || current != account) {
                    return@withLock if (current == null) {
                        CloudConfigurationResult.NoSteamAccount
                    } else {
                        CloudConfigurationResult.AccountMismatch(current, result.actualAccount)
                    }
                }
                record(
                    trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                    outcome = CloudReadOutcome.ACCOUNT_MISMATCH,
                    parsed = null,
                )
                CloudConfigurationResult.AccountMismatch(account, result.actualAccount)
            }
            is RemoteReadResult.Failure -> cloudStateMutex.withLock {
                val current = credentialsProvider.currentCredentials()?.steamId
                if (generationAtStart != accountGeneration.get() || current != account) {
                    return@withLock result.failure.toConfigurationResult()
                }
                record(
                    trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                    outcome = result.failure.toOutcome(),
                    parsed = null,
                )
                result.failure.toConfigurationResult()
            }
        }
    }

    suspend fun read(trigger: CloudReadTrigger = CloudReadTrigger.SETTINGS_MANUAL): CloudReadResult {
        // Capture every account-bound input (stored endpoint/token, Steam account, resumable
        // watermark) together with the generation under the invalidation mutex, so a bump
        // cannot land mid-capture and leave e.g. A's account paired with B's generation or
        // B's cleared watermark.
        val start = cloudStateMutex.withLock {
            ReadStart(
                credentials = credentialsStore.readCloudCredentials(),
                account = credentialsProvider.currentCredentials()?.steamId,
                position = settings.cloudReadPosition.first(),
                generation = accountGeneration.get(),
            )
        }
        val credentials = start.credentials ?: return CloudReadResult.Unconfigured
        val account = start.account ?: return CloudReadResult.NoSteamAccount
        val position = start.position
        val generationAtStart = start.generation
        return when (val result = fetch(credentials.endpoint, credentials.token, account, position)) {
            is RemoteReadResult.Success -> cloudStateMutex.withLock {
                if (generationAtStart != accountGeneration.get() ||
                    credentialsProvider.currentCredentials()?.steamId != account
                ) {
                    // A success carries A's timeline: returning it would leak A's data to B's
                    // caller, and persisting it would repopulate watermark/audit/snapshot.
                    // The identity check covers a read started after invalidation but before
                    // the new Steam identity was committed/visible.
                    return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
                }
                persistSuccessfulRead(trigger, result.parsed, credentials)
                CloudReadResult.Success(result.parsed.toSnapshot())
            }
            is RemoteReadResult.AccountMismatch -> cloudStateMutex.withLock {
                if (generationAtStart != accountGeneration.get() ||
                    credentialsProvider.currentCredentials()?.steamId != account
                ) {
                    return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
                }
                record(trigger, CloudReadOutcome.ACCOUNT_MISMATCH, null)
                CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
            }
            is RemoteReadResult.Failure -> cloudStateMutex.withLock {
                if (generationAtStart != accountGeneration.get() ||
                    credentialsProvider.currentCredentials()?.steamId != account
                ) {
                    return@withLock CloudReadResult.Failed(result.failure)
                }
                record(trigger, result.failure.toOutcome(), null)
                CloudReadResult.Failed(result.failure)
            }
        }
    }

    suspend fun removeConfiguration() {
        cloudStateMutex.withLock {
            // Invalidate in-flight reads so a late response cannot repopulate after removal.
            accountGeneration.incrementAndGet()
            credentialsStore.clearCloudCredentials()
            settings.clearCloudReadPosition()
            configurationState.value = null
            snapshotState.value = null
        }
    }

    /**
     * Drops cloud state when the Steam account changes. The durable position is cleared by
     * `SettingsDataStore.clearAccountDerivedState` and the audit rows by `AccountRoomReset`;
     * those clears are repeated here under the same mutex that guards capture and post-fetch
     * persistence, so a response landing between the coordinator's clears and this increment
     * cannot repopulate watermark/audit/snapshot under the new account. The increment also
     * discards any in-flight verification before it can persist an A-bound configuration.
     *
     * Prefer [runAccountChangeTransition] for the coordinated switch: it holds this mutex
     * across promotion so no capture or persistence can interleave between the pre- and
     * post-promotion invalidations.
     */
    suspend fun invalidateForAccountChange() {
        cloudStateMutex.withLock {
            accountGeneration.incrementAndGet()
            snapshotState.value = null
            settings.clearCloudReadPosition()
            cloudReadDao.deleteAll()
        }
    }

    /**
     * Fences the whole identity transition rather than one instant. Holds the capture and
     * persistence mutex across [block] (durable reset plus credential promotion plus identity
     * refresh), bumping the generation and re-clearing cloud state on entry and on exit. A
     * capture of old account A with the entry generation is discarded by the exit bump, and
     * no new capture or persistence can start mid-transition to capture A with the exit
     * generation or persist A's timeline/configuration just before promotion. The exit
     * invalidation is the definitive one: [block] must leave the new Steam identity
     * committed and visible (refreshed provider) before it returns.
     */
    suspend fun runAccountChangeTransition(block: suspend () -> Unit) {
        cloudStateMutex.withLock {
            accountGeneration.incrementAndGet()
            snapshotState.value = null
            settings.clearCloudReadPosition()
            cloudReadDao.deleteAll()
            try {
                block()
            } finally {
                accountGeneration.incrementAndGet()
                snapshotState.value = null
                settings.clearCloudReadPosition()
                cloudReadDao.deleteAll()
            }
        }
    }

    private data class VerifyStart(
        val account: String?,
        val generation: Long,
    )

    private data class ReadStart(
        val credentials: CloudCredentials?,
        val account: String?,
        val position: String?,
        val generation: Long,
    )

    private suspend fun persistVerifiedConfiguration(
        credentials: CloudCredentials,
        trigger: CloudReadTrigger,
        parsed: ParsedCloudRead,
    ) {
        // Caller holds cloudStateMutex and has already checked generation + identity.
        credentialsStore.writeCloudCredentials(credentials.endpoint, credentials.token)
        persistSuccessfulRead(trigger, parsed, credentials)
    }

    private suspend fun persistSuccessfulRead(
        trigger: CloudReadTrigger,
        parsed: ParsedCloudRead,
        credentials: CloudCredentials,
    ) {
        // Caller holds cloudStateMutex and has already checked generation + identity.
        parsed.nextPosition?.let { settings.setCloudReadPosition(it) }
        // In-memory only: a normal read's durable effects are the watermark above and its
        // diagnostic record below. Rewriting the encrypted credentials here would turn a
        // Keystore/DataStore failure into a failed read and produce fresh ciphertext per fetch.
        configurationState.value = credentials.toConfiguration()
        snapshotState.value = parsed.toSnapshot()
        record(trigger, CloudReadOutcome.SUCCESS, parsed)
    }

    private suspend fun fetch(
        endpoint: String,
        token: String,
        expectedAccount: String,
        position: String?,
    ): RemoteReadResult {
        val response = try {
            api.read(endpoint, "Bearer " + token.trim(), position)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (http: HttpException) {
            return RemoteReadResult.Failure(
                when {
                    http.code() == 401 || http.code() == 403 -> CloudReadFailure.REJECTED_CREDENTIAL
                    http.isUnusableResponse() -> CloudReadFailure.UNUSABLE_RESPONSE
                    else -> CloudReadFailure.UNREACHABLE
                },
            )
        } catch (_: SerializationException) {
            return RemoteReadResult.Failure(CloudReadFailure.UNUSABLE_RESPONSE)
        } catch (_: Exception) {
            return RemoteReadResult.Failure(CloudReadFailure.UNREACHABLE)
        }

        val account = response.account?.trim()
            ?: return RemoteReadResult.Failure(CloudReadFailure.UNUSABLE_RESPONSE)
        if (account != expectedAccount) {
            return RemoteReadResult.AccountMismatch(account)
        }
        return runCatching { response.toParsedRead(expectedAccount) }
            .fold(
                onSuccess = { RemoteReadResult.Success(it) },
                onFailure = { RemoteReadResult.Failure(CloudReadFailure.UNUSABLE_RESPONSE) },
            )
    }

    private suspend fun record(
        trigger: CloudReadTrigger,
        outcome: CloudReadOutcome,
        parsed: ParsedCloudRead?,
    ) {
        cloudReadDao.insert(
            CloudReadRecord(
                at = time.nowMillis(),
                trigger = trigger.name,
                outcome = outcome.name,
                windowStart = parsed?.windowStart,
                windowEnd = parsed?.windowEnd,
                observationCount = parsed?.transitions?.size ?: 0,
                nextPosition = parsed?.nextPosition,
            ),
        )
        cloudReadDao.prune(MAX_DIAGNOSTIC_RECORDS)
    }

    private sealed interface RemoteReadResult {
        data class Success(val parsed: ParsedCloudRead) : RemoteReadResult
        data class AccountMismatch(val actualAccount: String) : RemoteReadResult
        data class Failure(val failure: CloudReadFailure) : RemoteReadResult
    }

    data class ParsedCloudRead(
        val account: String,
        val transitions: List<CloudPresenceTransition>,
        val current: CloudPresenceCurrentState?,
        val windowStart: Long,
        val windowEnd: Long,
        val readAt: Long,
        val nextPosition: String?,
        val hasMore: Boolean,
    ) {
        fun toSnapshot(): CloudPresenceSnapshot {
            // An incomplete page covers only its returned transitions: combining the page's
            // prefix with the latest current state would fabricate a tail interval across
            // the omitted transitions, and presenting the server's full-window end would
            // claim complete evidence. Bound the snapshot to the page and label it via hasMore.
            val pageEnd = transitions.maxOfOrNull { it.at }
            val effectiveCurrent = if (hasMore) null else current
            val effectiveWindowEnd = if (hasMore && pageEnd != null) {
                minOf(windowEnd, pageEnd)
            } else {
                windowEnd
            }
            return CloudPresenceSnapshot(
                windowStart = windowStart,
                windowEnd = effectiveWindowEnd,
                readAt = readAt,
                intervals = CloudPresenceReconstruction.reconstruct(transitions, effectiveCurrent),
                current = current,
                observationCount = transitions.size,
                nextPosition = nextPosition,
                hasMore = hasMore,
            )
        }
    }

    private fun CloudCredentials.toConfiguration() = CloudPresenceConfiguration(
        endpoint = endpoint,
        maskedToken = maskCredential(token),
    )

    private fun CloudReadFailure.toOutcome() = when (this) {
        CloudReadFailure.UNREACHABLE -> CloudReadOutcome.UNREACHABLE
        CloudReadFailure.REJECTED_CREDENTIAL -> CloudReadOutcome.REJECTED_CREDENTIAL
        CloudReadFailure.ACCOUNT_MISMATCH -> CloudReadOutcome.ACCOUNT_MISMATCH
        CloudReadFailure.UNUSABLE_RESPONSE -> CloudReadOutcome.UNUSABLE_RESPONSE
    }

    private fun CloudReadFailure.toConfigurationResult() = when (this) {
        CloudReadFailure.UNREACHABLE -> CloudConfigurationResult.Unreachable
        CloudReadFailure.REJECTED_CREDENTIAL -> CloudConfigurationResult.RejectedCredential
        CloudReadFailure.ACCOUNT_MISMATCH -> CloudConfigurationResult.UnusableResponse
        CloudReadFailure.UNUSABLE_RESPONSE -> CloudConfigurationResult.UnusableResponse
    }

    private fun outcomeToFailure(outcome: String): CloudReadFailure? = when (outcome) {
        CloudReadOutcome.UNREACHABLE.name -> CloudReadFailure.UNREACHABLE
        CloudReadOutcome.REJECTED_CREDENTIAL.name -> CloudReadFailure.REJECTED_CREDENTIAL
        CloudReadOutcome.ACCOUNT_MISMATCH.name -> CloudReadFailure.ACCOUNT_MISMATCH
        CloudReadOutcome.UNUSABLE_RESPONSE.name -> CloudReadFailure.UNUSABLE_RESPONSE
        else -> null
    }

    companion object {
        private const val MAX_DIAGNOSTIC_RECORDS = 200

        fun normalizeEndpoint(raw: String): String? {
            val endpoint = raw.trim()
            val url = endpoint.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" || url.host.isBlank()) return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
            if (url.encodedQuery != null || url.fragment != null) return null
            return url.toString().removeSuffix("/")
        }
    }
}

private enum class CloudReadOutcome {
    SUCCESS,
    UNREACHABLE,
    REJECTED_CREDENTIAL,
    ACCOUNT_MISMATCH,
    UNUSABLE_RESPONSE,
}

private fun CloudPresenceResponseDto.toParsedRead(expectedAccount: String): CloudPresenceRepository.ParsedCloudRead {
    val windowStart = requiredInstant(windowStart)
    val windowEnd = requiredInstant(windowEnd)
    val readAt = requiredInstant(readAt)
    val parsedTransitions = transitions.map { it.toDomainTransition() }
    val serverPosition = nextPosition?.trim()?.takeIf { it.isNotBlank() }?.also { requiredInstant(it) }
    if (hasMore && serverPosition == null) error("Cloud reader returned more data without a position")
    // Preserve one transition of overlap across pages: resuming strictly after the last
    // transition would drop the interval it opens (t250 -> t251), since page 1 reconstructs
    // only through t249 -> t250 and page 2 only from t251 onward. Persisting the second-last
    // transition causes the last to be returned again, so every adjacent pair still exists
    // in some page's reconstruction. Document keys are unique ISO timestamps, so the
    // second-last instant is strictly before the last and re-fetches exactly one transition.
    val resumePosition = if (hasMore && parsedTransitions.size >= 2) {
        val ordered = parsedTransitions.sortedBy { it.at }
        Instant.ofEpochMilli(ordered[ordered.size - 2].at).toString()
    } else {
        serverPosition
    }
    return CloudPresenceRepository.ParsedCloudRead(
        account = expectedAccount,
        transitions = parsedTransitions,
        current = current?.toDomainCurrent(),
        windowStart = windowStart,
        windowEnd = windowEnd,
        readAt = readAt,
        nextPosition = resumePosition,
        hasMore = hasMore,
    )
}

private fun CloudPresenceTransitionDto.toDomainTransition() = CloudPresenceTransition(
    at = requiredInstant(t),
    appId = parseAppId(gameid),
    gameName = gameName,
    personastate = personastate,
    previousLastObservedAt = optionalInstant(prevLastObservedAt),
    previousCoverageLapseFrom = optionalInstant(prevCoverageLapseFrom),
    previousCoverageLapseRecoveredAt = optionalInstant(prevCoverageLapseRecoveredAt),
    schemaVersion = v,
)

private fun CloudPresenceCurrentDto.toDomainCurrent() = CloudPresenceCurrentState(
    observedAt = optionalInstant(lastObservedAt),
    appId = parseAppId(gameid),
    gameName = gameName,
    personastate = personastate,
    since = optionalInstant(since),
    coverageLapseFrom = optionalInstant(coverageLapseFrom),
    coverageLapseRecoveredAt = optionalInstant(coverageLapseRecoveredAt),
    schemaVersion = v,
)

private fun requiredInstant(raw: String?): Long {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: error("Cloud reader omitted a timestamp")
    return Instant.parse(value).toEpochMilli()
}

private fun optionalInstant(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return Instant.parse(raw.trim()).toEpochMilli()
}

private fun parseAppId(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return raw.trim().toLongOrNull()?.takeIf { it >= 0L }
        ?: error("Cloud reader returned a non-numeric app id")
}

@Serializable
private data class CloudErrorBody(val error: String? = null)

private val cloudErrorJson = Json { ignoreUnknownKeys = true }

private fun HttpException.isUnusableResponse(): Boolean {
    return try {
        val raw = response()?.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: return false
        runCatching { cloudErrorJson.decodeFromString<CloudErrorBody>(raw).error == "unusable_response" }
            .getOrDefault(false)
    } catch (_: Exception) {
        false
    }
}
