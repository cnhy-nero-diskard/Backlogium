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
import com.example.backlogium.work.CloudRoutineWorkCanceller
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
    SYNC,
    POST_PLAY,
    ROUTINE,
}

sealed interface CloudCatchUpResult {
    data class Complete(val pages: Int, val transitions: Int, val noNewData: Boolean) : CloudCatchUpResult
    data class Partial(val pages: Int, val transitions: Int) : CloudCatchUpResult
    data class Failed(val pages: Int, val transitions: Int, val failure: CloudReadFailure) : CloudCatchUpResult
    data object Unavailable : CloudCatchUpResult
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
 * Cloud presence owns encrypted configuration, the resumable read watermark, and the current
 * in-memory comparison snapshot. Session/progress writes remain delegated to the explicit ingest
 * callback supplied by the caller of [read].
 */
@Singleton
class CloudPresenceRepository @Inject constructor(
    private val api: CloudPresenceApi,
    private val credentialsStore: CloudCredentialsStore,
    private val credentialsProvider: CredentialsProvider,
    private val settings: SettingsRepository,
    private val cloudReadDao: CloudReadDao,
    private val pendingEvidence: CloudPendingEvidence,
    private val time: TimeProvider,
    private val routineWorkCanceller: CloudRoutineWorkCanceller? = null,
) {
    /** Compatibility constructor for existing read-protocol tests without a Room evidence store. */
    internal constructor(
        api: CloudPresenceApi,
        credentialsStore: CloudCredentialsStore,
        credentialsProvider: CredentialsProvider,
        settings: SettingsRepository,
        cloudReadDao: CloudReadDao,
        time: TimeProvider,
    ) : this(api, credentialsStore, credentialsProvider, settings, cloudReadDao, EmptyPendingEvidence, time)

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
    // Serializes every read sharing the durable cloudReadPosition: a single page read
    // against the historical drain's reset plus paginated drain, and against a successful
    // verification's promotion. The drain resets the watermark and then advances it page
    // by page, so an ordinary placement read (Steam sync/post-play) interleaving between
    // pages would consume a page the drain then skips, permanently losing it when a later
    // terminal page completes the drain. A verification interleaving between drain pages
    // is worse: it clears both positions and persists its own nextPosition, so the
    // drain's next page resumes from the new sequence while its accumulator still holds
    // the old sequence's pages, duplicating/skipping history under the original
    // firstWindowStart. Held across the whole drain, including network fetches, and
    // across verification promotion, so the concurrent party waits instead of mixing
    // generations. Outer to cloudStateMutex everywhere.
    private val cloudReadSequenceMutex = Mutex()

    val configuration: Flow<CloudPresenceConfiguration?> =
        configurationState.onStart {
            configurationState.value = credentialsStore.readCloudCredentials()?.toConfiguration()
        }

    val snapshot: Flow<CloudPresenceSnapshot?> = snapshotState.asStateFlow()

    /** Durable reader metadata; never the in-memory interval snapshot or a poller health flag. */
    val readSummary: Flow<CloudReadSummary> = settings.cloudReadSummary

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

    /** Startup/upgrade reconciliation is local-only and never moves either cloud cursor. */
    suspend fun reconcileRoutinePolicy(): CloudRoutineState? {
        cloudReadSequenceMutex.withLock { recoverStagedPromotionLocked() }
        return cloudStateMutex.withLock {
            if (credentialsProvider.currentCredentials()?.steamId == null ||
                credentialsStore.readCloudCredentials() == null
            ) return@withLock null
            settings.initializeCloudRoutinePolicy()
        }
    }

    /**
     * Finishes or abandons a reader promotion interrupted by process death, before any read or
     * replacement proceeds. Staged credentials and the staged page are written before the durable
     * marker, so a marked promotion can always be completed by re-running the idempotent steps
     * after its commit point; staged inputs without a marker are an abandoned attempt and are
     * discarded. This is what makes endpoint replacement one logical transaction across the
     * pending-evidence Room store, Settings DataStore, and the encrypted credential DataStore:
     * no read can observe the replacement reader's evidence paired with the old credentials, or
     * the old generation with its evidence already retired.
     *
     * Caller must hold [cloudReadSequenceMutex]; the account-state mutex is taken here so
     * invalidation cannot interleave with the completed promotion's writes.
     */
    private suspend fun recoverStagedPromotionLocked() {
        cloudStateMutex.withLock {
            val marker = settings.cloudReaderPromotionTarget.first()
            val staged = credentialsStore.readStagedCloudCredentials()
            if (marker == null) {
                if (staged != null) credentialsStore.clearStagedCloudCredentials()
                return@withLock
            }
            val account = credentialsProvider.currentCredentials()?.steamId ?: return@withLock
            if (staged != null) credentialsStore.commitStagedCloudCredentials()
            val target = settings.finishCloudReaderPromotion() ?: return@withLock
            // Re-apply the post-commit effects a crash may have skipped, so the replacement
            // never resumes the old reader's watermark.
            pendingEvidence.clearExcept(account, target)
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = false)
            settings.clearCloudReadPosition()
            settings.clearCloudReadSummary()
            settings.initializeCloudRoutinePolicy()
        }
    }

    /** Verify identity at work start; DataStore arbitrates coincident trigger admissions. */
    suspend fun admitRoutineWork(account: String, generation: Long): CloudRoutineAdmission =
        cloudStateMutex.withLock {
            if (credentialsProvider.currentCredentials()?.steamId != account ||
                credentialsStore.readCloudCredentials() == null ||
                settings.cloudReaderGeneration.first() != generation
            ) return@withLock CloudRoutineAdmission.UNAVAILABLE
            settings.admitCloudRoutine(time.nowMillis())
        }

    suspend fun verifyAndSave(
        endpoint: String,
        token: String,
        consume: suspend (CloudPresenceSnapshot) -> Unit = {},
    ): CloudConfigurationResult {
        val normalizedEndpoint = normalizeEndpoint(endpoint) ?: return CloudConfigurationResult.InvalidEndpoint
        val normalizedToken = token.trim().takeIf { it.isNotBlank() }
            ?: return CloudConfigurationResult.RejectedCredential
        // A promotion interrupted by process death is finished or abandoned before this
        // verification captures anything, so the capture below always sees committed state.
        cloudReadSequenceMutex.withLock { recoverStagedPromotionLocked() }
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
            // Promotion holds the read-sequence mutex outer to the account-state mutex, like
            // every drain page: it clears both positions and persists the verification
            // response's nextPosition, so completing between drain pages would otherwise let
            // the drain's next page resume from the new sequence while its accumulator still
            // holds the old sequence's pages. The fetch stays outside both locks so a drain
            // never blocks verification's network; only promotion waits for the terminal
            // watermark, and drains wait for promotion.
            is RemoteReadResult.Success -> {
                // Cancel queued/running routine work as soon as the replacement has answered;
                // the serialized promotion below still waits for any active page to finish.
                val stillCurrent = cloudStateMutex.withLock {
                    generationAtStart == accountGeneration.get() &&
                        credentialsProvider.currentCredentials()?.steamId == account
                }
                if (stillCurrent) routineWorkCanceller?.cancelOldReaderWork(removePeriodic = false)
                cloudReadSequenceMutex.withLock { cloudStateMutex.withLock {
                    val current = credentialsProvider.currentCredentials()?.steamId
                    if (generationAtStart != accountGeneration.get() || current != account) {
                        // The account changed while verifying: A's endpoint must not become B's
                        // configuration, and B's watermark must not be cleared. Report against the
                        // current account without persisting anything. The identity check covers a
                        // read that started after invalidation but before the new Steam identity
                        // was committed/visible, where the generation alone still matches.
                        if (current == null) {
                            CloudConfigurationResult.NoSteamAccount
                        } else {
                            CloudConfigurationResult.AccountMismatch(current, result.parsed.account)
                        }
                    } else {
                        val persistedGeneration = settings.cloudReaderGeneration.first()
                        val readerGeneration = persistedGeneration + 1L
                        val snapshot = result.parsed.toSnapshot()
                        val previousIngestPosition = settings.cloudIngestPosition.first()
                        settings.clearCloudIngestPosition()
                        // Discard rows an interrupted replacement left staged, so this
                        // verification's page can never combine with a previous attempt's
                        // evidence under the same target generation.
                        pendingEvidence.clearExcept(account, persistedGeneration)
                        try {
                            consume(snapshot)
                            pendingEvidence.retain(
                                account, readerGeneration, snapshot.windowStart,
                                snapshot.intervals, result.parsed.transitions.maxByOrNull { it.at },
                            )
                            credentialsStore.stageCloudCredentials(normalizedEndpoint, normalizedToken)
                            settings.markCloudReaderPromotion(readerGeneration)
                        } catch (failure: Throwable) {
                            // Nothing passed the durable marker: the old verified reader is
                            // still the active one, so abandon the staged page and restore
                            // its ingest cursor.
                            credentialsStore.clearStagedCloudCredentials()
                            pendingEvidence.clearExcept(account, persistedGeneration)
                            if (previousIngestPosition != null) {
                                settings.setCloudIngestPosition(previousIngestPosition)
                            }
                            throw failure
                        }
                        // The marker is the commit point. Every step below it is idempotent,
                        // so a persistence failure or process death after it is finished by
                        // recoverStagedPromotionLocked on the next access rather than rolled
                        // back: the old credentials are already replaced.
                        credentialsStore.commitStagedCloudCredentials()
                        settings.finishCloudReaderPromotion()
                        // Only now that the credential+generation promotion is durably
                        // committed is the old generation's evidence retired.
                        pendingEvidence.clearExcept(account, readerGeneration)
                        routineWorkCanceller?.cancelOldReaderWork(removePeriodic = false)
                        settings.clearCloudReadPosition()
                        settings.clearCloudReadSummary()
                        persistVerifiedConfiguration(
                            credentials = CloudCredentials(normalizedEndpoint, normalizedToken),
                            trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                            parsed = result.parsed,
                        )
                        settings.initializeCloudRoutinePolicy()
                        CloudConfigurationResult.Saved
                    }
                } }
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

    suspend fun read(
        trigger: CloudReadTrigger = CloudReadTrigger.SETTINGS_MANUAL,
        consume: suspend (CloudPresenceSnapshot) -> Unit = {},
    ): CloudReadResult = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        readPage(trigger, consume)
    }

    /** At most four serialized pages per routine opportunity; every consumed page commits its cursor. */
    suspend fun readRoutineCatchUp(
        expectedAccount: String? = null,
        expectedGeneration: Long? = null,
        consume: suspend (CloudPresenceSnapshot) -> Unit,
    ): CloudCatchUpResult = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        val account = credentialsProvider.currentCredentials()?.steamId ?: return@withLock CloudCatchUpResult.Unavailable
        if (credentialsStore.readCloudCredentials() == null) return@withLock CloudCatchUpResult.Unavailable
        val generation = settings.cloudReaderGeneration.first()
        if ((expectedAccount != null && account != expectedAccount) ||
            (expectedGeneration != null && generation != expectedGeneration)
        ) return@withLock CloudCatchUpResult.Unavailable
        var transitions = 0
        repeat(MAX_ROUTINE_PAGES) { page ->
            if (credentialsProvider.currentCredentials()?.steamId != account ||
                settings.cloudReaderGeneration.first() != generation
            ) return@withLock CloudCatchUpResult.Failed(page, transitions, CloudReadFailure.ACCOUNT_MISMATCH)
            val result = try {
                readPage(CloudReadTrigger.ROUTINE, consume)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@withLock CloudCatchUpResult.Failed(page, transitions, CloudReadFailure.UNUSABLE_RESPONSE)
            }
            when (result) {
                is CloudReadResult.Success -> {
                    transitions += result.snapshot.observationCount
                    if (!result.snapshot.hasMore) {
                        return@withLock CloudCatchUpResult.Complete(
                            page + 1, transitions, noNewData = transitions == 0,
                        )
                    }
                }
                is CloudReadResult.Failed ->
                    return@withLock CloudCatchUpResult.Failed(page, transitions, result.failure)
                else -> return@withLock CloudCatchUpResult.Unavailable
            }
        }
        CloudCatchUpResult.Partial(MAX_ROUTINE_PAGES, transitions)
    }

    /**
     * Single-page read without acquiring [cloudReadSequenceMutex]: the caller must hold it.
     * Ordinary [read] holds it for one page; [readCompleteHistory] and [readRemainingHistory]
     * hold it across every page so no other read can advance the shared watermark mid-drain.
     */
    private suspend fun readPage(
        trigger: CloudReadTrigger = CloudReadTrigger.SETTINGS_MANUAL,
        consume: suspend (CloudPresenceSnapshot) -> Unit = {},
    ): CloudReadResult {
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
                readerGeneration = settings.cloudReaderGeneration.first(),
            )
        }
        val credentials = start.credentials ?: return CloudReadResult.Unconfigured
        val account = start.account ?: return CloudReadResult.NoSteamAccount
        val position = start.position
        val generationAtStart = start.generation
        return when (val result = fetch(credentials.endpoint, credentials.token, account, position)) {
            is RemoteReadResult.Success -> cloudStateMutex.withLock {
                if (generationAtStart != accountGeneration.get() ||
                    credentialsProvider.currentCredentials()?.steamId != account ||
                    settings.cloudReaderGeneration.first() != start.readerGeneration ||
                    credentialsStore.readCloudCredentials() != credentials
                ) {
                    // A success carries A's timeline: returning it would leak A's data to B's
                    // caller, and persisting it would repopulate watermark/audit/snapshot.
                    // The identity check covers a read started after invalidation but before
                    // the new Steam identity was committed/visible.
                    return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
                }
                val opening = pendingEvidence.boundary(account, start.readerGeneration)
                    ?.takeIf { boundary ->
                        result.parsed.transitions.firstOrNull()?.at?.let { boundary.at < it } == true
                    }
                val snapshot = result.parsed.toSnapshot(opening)
                // The consumer runs under the same account-state mutex and before the read
                // watermark advances. A failed ingest therefore leaves the cloud window retryable
                // instead of moving the acquisition cursor past uncommitted derived state.
                consume(snapshot)
                pendingEvidence.retain(
                    account, start.readerGeneration, snapshot.windowStart, snapshot.intervals,
                    result.parsed.transitions.maxByOrNull { it.at },
                )
                persistSuccessfulRead(trigger, result.parsed, credentials)
                CloudReadResult.Success(snapshot)
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

    /**
     * Drains the pending cloud history for the one-time historical re-file.
     *
     * A single [read] returns one page and advances the watermark, so earlier pages would be
     * consumed by the per-page ingest (family-shared sessions) while the owned-game intervals
     * the re-file needs are discarded. Starting from the beginning and accumulating every
     * page's intervals recovers that evidence, including pages a prior single-page read
     * already consumed: the ingest watermark is kept, so re-read pages are skipped for
     * family-shared derivation rather than double-credited. A mid-drain failure returns the
     * failure without applying anything partial; the next attempt restarts from the beginning,
     * so no page is lost to a partially advanced watermark.
     *
     * Holds [cloudReadSequenceMutex] across the reset plus every page, so an ordinary
     * placement read cannot advance the shared watermark between drain pages and steal a
     * page the drain then skips, and so a successful verification cannot clear the
     * watermark and persist its own nextPosition between drain pages and mix generations.
     */
    suspend fun readCompleteHistory(
        trigger: CloudReadTrigger = CloudReadTrigger.SETTINGS_MANUAL,
        consume: suspend (CloudPresenceSnapshot) -> Unit = {},
    ): CloudReadResult = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        // Peek before the destructive reset so an unconfigured app or a missing Steam account
        // does not discard the watermark it never needed to move.
        if (credentialsStore.readCloudCredentials() == null) {
            return@withLock CloudReadResult.Unconfigured
        }
        val account = credentialsProvider.currentCredentials()?.steamId
        if (account == null) {
            return@withLock CloudReadResult.NoSteamAccount
        }
        val generation = settings.cloudReaderGeneration.first()
        settings.clearCloudReadPosition()
        val allIntervals = mutableListOf<CloudPresenceInterval>()
        var totalObservations = 0
        var firstWindowStart: Long? = null
        repeat(MAX_REFILE_PAGES) {
            if (credentialsProvider.currentCredentials()?.steamId != account ||
                settings.cloudReaderGeneration.first() != generation
            ) return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
            when (val result = readPage(trigger, consume)) {
                is CloudReadResult.Unconfigured,
                is CloudReadResult.NoSteamAccount,
                is CloudReadResult.Failed,
                -> return@withLock result
                is CloudReadResult.Success -> {
                    val snapshot = result.snapshot
                    if (firstWindowStart == null) firstWindowStart = snapshot.windowStart
                    allIntervals += snapshot.intervals
                    totalObservations += snapshot.observationCount
                    if (!snapshot.hasMore) {
                        if (credentialsProvider.currentCredentials()?.steamId != account ||
                            settings.cloudReaderGeneration.first() != generation
                        ) return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
                        val combined = snapshot.copy(
                            windowStart = firstWindowStart ?: snapshot.windowStart,
                            intervals = allIntervals.sortedWith(
                                compareBy(
                                    { interval -> interval.startAt },
                                    { interval -> interval.endAt ?: Long.MAX_VALUE },
                                ),
                            ),
                            observationCount = totalObservations,
                            hasMore = false,
                        )
                        return@withLock CloudReadResult.Success(combined)
                    }
                }
            }
        }
        return@withLock CloudReadResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
    }

    /**
     * Drains the still-unread cloud history forward from the current watermark without resetting
     * it, for callers that must place a diff earned over a window they did not observe.
     *
     * A single [read] returns one page: returning that page as placement evidence would
     * distribute the whole Steam delta across a suffix when the cursor was already advanced
     * inside the diff window, and discarding a page with `hasMore` after [read] already
     * persisted its cursor would lose it once the Steam baseline advances past the delta.
     * Accumulating every unread page recovers the suffix without losing earlier pages of this
     * drain; the caller still refuses placement unless the first page starts at or before its
     * diff window. Shared-game ingest runs per page through [consume], so no page is lost to
     * that mechanism either.
     *
     * Holds [cloudReadSequenceMutex] across every page, so the historical re-file drain cannot
     * reset the watermark between these pages and steal one this drain then skips, and vice
     * versa, and so a successful verification cannot clear the watermark and persist its
     * own nextPosition between these pages and mix generations.
     */
    suspend fun readRemainingHistory(
        trigger: CloudReadTrigger = CloudReadTrigger.SETTINGS_MANUAL,
        consume: suspend (CloudPresenceSnapshot) -> Unit = {},
    ): CloudReadResult = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        if (credentialsStore.readCloudCredentials() == null) {
            return@withLock CloudReadResult.Unconfigured
        }
        val accountAtStart = credentialsProvider.currentCredentials()?.steamId
        if (accountAtStart == null) {
            return@withLock CloudReadResult.NoSteamAccount
        }
        val generationAtStart = settings.cloudReaderGeneration.first()
        val allIntervals = mutableListOf<CloudPresenceInterval>()
        var totalObservations = 0
        var firstWindowStart: Long? = null
        repeat(MAX_REFILE_PAGES) {
            if (credentialsProvider.currentCredentials()?.steamId != accountAtStart ||
                settings.cloudReaderGeneration.first() != generationAtStart
            ) return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
            when (val result = readPage(trigger, consume)) {
                is CloudReadResult.Unconfigured,
                is CloudReadResult.NoSteamAccount,
                is CloudReadResult.Failed,
                -> return@withLock result
                is CloudReadResult.Success -> {
                    val snapshot = result.snapshot
                    if (firstWindowStart == null) firstWindowStart = snapshot.windowStart
                    allIntervals += snapshot.intervals
                    totalObservations += snapshot.observationCount
                    if (!snapshot.hasMore) {
                        if (credentialsProvider.currentCredentials()?.steamId != accountAtStart ||
                            settings.cloudReaderGeneration.first() != generationAtStart
                        ) return@withLock CloudReadResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
                        val retained = pendingEvidence.intervals(accountAtStart, generationAtStart)
                        val combined = snapshot.copy(
                            windowStart = listOfNotNull(
                                firstWindowStart,
                                pendingEvidence.earliestWindowStart(accountAtStart, generationAtStart),
                            ).minOrNull() ?: snapshot.windowStart,
                            intervals = (retained + allIntervals)
                                .distinctBy { it.appId to it.startAt }
                                .sortedWith(
                                compareBy(
                                    { interval -> interval.startAt },
                                    { interval -> interval.endAt ?: Long.MAX_VALUE },
                                ),
                            ),
                            observationCount = totalObservations,
                            hasMore = false,
                        )
                        return@withLock CloudReadResult.Success(combined)
                    }
                }
            }
        }
        return@withLock CloudReadResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
    }

    suspend fun removeConfiguration() {
        cloudStateMutex.withLock {
            // Invalidate in-flight reads so a late response cannot repopulate after removal.
            accountGeneration.incrementAndGet()
            settings.advanceCloudReaderGeneration()
            settings.clearCloudReaderPromotion()
            credentialsStore.clearStagedCloudCredentials()
            pendingEvidence.clear()
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
            credentialsStore.clearCloudCredentials()
            settings.clearCloudReadPosition()
            settings.clearCloudIngestPosition()
            settings.clearCloudRoutinePolicy()
            settings.clearCloudReadSummary()
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
            settings.advanceCloudReaderGeneration()
            settings.clearCloudReaderPromotion()
            credentialsStore.clearStagedCloudCredentials()
            pendingEvidence.clear()
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
            snapshotState.value = null
            settings.clearCloudReadPosition()
            settings.clearCloudIngestPosition()
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
            settings.advanceCloudReaderGeneration()
            settings.clearCloudReaderPromotion()
            credentialsStore.clearStagedCloudCredentials()
            pendingEvidence.clear()
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
            snapshotState.value = null
            settings.clearCloudReadPosition()
            settings.clearCloudIngestPosition()
            cloudReadDao.deleteAll()
            try {
                block()
            } finally {
                accountGeneration.incrementAndGet()
                settings.advanceCloudReaderGeneration()
                settings.clearCloudReaderPromotion()
                credentialsStore.clearStagedCloudCredentials()
                pendingEvidence.clear()
                routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
                snapshotState.value = null
                settings.clearCloudReadPosition()
                settings.clearCloudIngestPosition()
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
        val readerGeneration: Long,
    )

    private suspend fun persistVerifiedConfiguration(
        credentials: CloudCredentials,
        trigger: CloudReadTrigger,
        parsed: ParsedCloudRead,
    ) {
        // Caller holds cloudStateMutex and has already checked generation + identity.
        // The credential promotion itself committed earlier (commitStagedCloudCredentials),
        // before the generation advanced; this only records the read's durable effects.
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
        if (trigger == CloudReadTrigger.SETTINGS_MANUAL || trigger == CloudReadTrigger.SYNC ||
            trigger == CloudReadTrigger.POST_PLAY
        ) settings.recordCloudOtherRead(terminal = !parsed.hasMore)
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
        val at = time.nowMillis()
        cloudReadDao.insert(
            CloudReadRecord(
                at = at,
                trigger = trigger.name,
                outcome = outcome.name,
                windowStart = parsed?.windowStart,
                windowEnd = parsed?.windowEnd,
                observationCount = parsed?.transitions?.size ?: 0,
                nextPosition = parsed?.nextPosition,
            ),
        )
        cloudReadDao.prune(MAX_DIAGNOSTIC_RECORDS)
        val observationAt = parsed?.let { read ->
            listOfNotNull(read.current?.observedAt, read.transitions.maxOfOrNull { it.at }).maxOrNull()
        }
        settings.recordCloudReadSummary(
            at = at,
            trigger = trigger,
            outcome = when {
                outcome != CloudReadOutcome.SUCCESS -> CloudReadSummaryOutcome.FAILED
                parsed!!.hasMore -> CloudReadSummaryOutcome.PARTIAL
                parsed.transitions.isEmpty() -> CloudReadSummaryOutcome.NO_NEW_DATA
                else -> CloudReadSummaryOutcome.COMPLETE
            },
            failure = outcome.takeIf { it != CloudReadOutcome.SUCCESS }?.let {
                outcomeToFailure(it.name)
            },
            observedAt = observationAt,
            hasMore = parsed?.hasMore,
            windowStart = parsed?.windowStart,
            windowEnd = parsed?.windowEnd,
        )
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
        fun toSnapshot(openingBoundary: CloudPresenceTransition? = null): CloudPresenceSnapshot {
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
            val reconstructedTransitions = if (openingBoundary == null) transitions else {
                listOf(openingBoundary) + transitions
            }
            return CloudPresenceSnapshot(
                windowStart = windowStart,
                windowEnd = effectiveWindowEnd,
                readAt = readAt,
                intervals = CloudPresenceReconstruction.reconstruct(reconstructedTransitions, effectiveCurrent),
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
        // Bounds a re-file drain so a server that keeps reporting more never hangs the caller:
        // exceeding it surfaces as a failure rather than applying a partial history.
        private const val MAX_REFILE_PAGES = 50
        private const val MAX_ROUTINE_PAGES = 4

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

private object EmptyPendingEvidence : CloudPendingEvidence {
    override suspend fun boundary(account: String, generation: Long): CloudPresenceTransition? = null
    override suspend fun retain(
        account: String, generation: Long, windowStart: Long,
        intervals: List<CloudPresenceInterval>, lastTransition: CloudPresenceTransition?,
    ) = Unit
    override suspend fun intervals(account: String, generation: Long): List<CloudPresenceInterval> = emptyList()
    override suspend fun earliestWindowStart(account: String, generation: Long): Long? = null
    override suspend fun clear() = Unit
    override suspend fun clearExcept(account: String, generation: Long) = Unit
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
