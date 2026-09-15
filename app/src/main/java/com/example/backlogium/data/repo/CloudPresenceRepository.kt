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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerializationException
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
        configurationState.value = credentialsStore.readCloudCredentials()?.toConfiguration()
    }

    suspend fun verifyAndSave(endpoint: String, token: String): CloudConfigurationResult {
        val normalizedEndpoint = normalizeEndpoint(endpoint) ?: return CloudConfigurationResult.InvalidEndpoint
        val normalizedToken = token.trim().takeIf { it.isNotBlank() }
            ?: return CloudConfigurationResult.RejectedCredential
        val account = credentialsProvider.currentCredentials()?.steamId
            ?: return CloudConfigurationResult.NoSteamAccount

        return when (val result = fetch(normalizedEndpoint, normalizedToken, account, null)) {
            is RemoteReadResult.Success -> {
                settings.clearCloudReadPosition()
                persistSuccessfulRead(
                    credentials = CloudCredentials(normalizedEndpoint, normalizedToken),
                    trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                    parsed = result.parsed,
                )
                CloudConfigurationResult.Saved
            }
            is RemoteReadResult.AccountMismatch -> {
                record(
                    trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                    outcome = CloudReadOutcome.ACCOUNT_MISMATCH,
                    parsed = null,
                )
                CloudConfigurationResult.AccountMismatch(account, result.actualAccount)
            }
            is RemoteReadResult.Failure -> {
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
        val credentials = credentialsStore.readCloudCredentials()
            ?: return CloudReadResult.Unconfigured
        val account = credentialsProvider.currentCredentials()?.steamId
            ?: return CloudReadResult.NoSteamAccount
        val position = settings.cloudReadPosition.first()
        return when (val result = fetch(credentials.endpoint, credentials.token, account, position)) {
            is RemoteReadResult.Success -> {
                persistSuccessfulRead(credentials, trigger, result.parsed)
                CloudReadResult.Success(result.parsed.toSnapshot())
            }
            is RemoteReadResult.AccountMismatch -> {
                record(trigger, CloudReadOutcome.ACCOUNT_MISMATCH, null)
                CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
            }
            is RemoteReadResult.Failure -> {
                record(trigger, result.failure.toOutcome(), null)
                CloudReadResult.Failed(result.failure)
            }
        }
    }

    suspend fun removeConfiguration() {
        credentialsStore.clearCloudCredentials()
        settings.clearCloudReadPosition()
        configurationState.value = null
        snapshotState.value = null
    }

    private suspend fun persistSuccessfulRead(
        credentials: CloudCredentials,
        trigger: CloudReadTrigger,
        parsed: ParsedCloudRead,
    ) {
        credentialsStore.writeCloudCredentials(credentials.endpoint, credentials.token)
        parsed.nextPosition?.let { settings.setCloudReadPosition(it) }
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
                if (http.code() == 401 || http.code() == 403) {
                    CloudReadFailure.REJECTED_CREDENTIAL
                } else {
                    CloudReadFailure.UNREACHABLE
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
        fun toSnapshot(): CloudPresenceSnapshot = CloudPresenceSnapshot(
            windowStart = windowStart,
            windowEnd = windowEnd,
            readAt = readAt,
            intervals = CloudPresenceReconstruction.reconstruct(transitions, current),
            current = current,
            observationCount = transitions.size,
            nextPosition = nextPosition,
            hasMore = hasMore,
        )
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
    val next = nextPosition?.trim()?.takeIf { it.isNotBlank() }?.also { requiredInstant(it) }
    if (hasMore && next == null) error("Cloud reader returned more data without a position")
    return CloudPresenceRepository.ParsedCloudRead(
        account = expectedAccount,
        transitions = parsedTransitions,
        current = current?.toDomainCurrent(),
        windowStart = windowStart,
        windowEnd = windowEnd,
        readAt = readAt,
        nextPosition = next,
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
