package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.credentials.maskCredential
import com.example.backlogium.data.local.dao.CloudReadDao
import com.example.backlogium.data.local.entity.CloudHistoricalBoundary
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.data.local.entity.CloudHistoricalStates
import com.example.backlogium.data.local.entity.CloudReadRecord
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.domain.CloudPresenceCurrentState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresenceReconstruction
import com.example.backlogium.domain.CloudPresenceTransition
import com.example.backlogium.domain.CloudReaderIdentity
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.CloudRoutineWorkCancellation
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
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
import kotlinx.coroutines.yield
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

sealed interface CloudRoutineAttempt {
    data class NotAdmitted(val admission: CloudRoutineAdmission) : CloudRoutineAttempt
    data class Admitted(val at: Long, val outcome: CloudCatchUpResult) : CloudRoutineAttempt
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

data class CloudPresenceRangeMetadata(
    val account: String,
    val earliestObservedAt: Long?,
    val current: CloudPresenceCurrentState?,
    val readAt: Long,
    val readerGeneration: Long? = null,
    val endpointIdentity: String? = null,
    val accountGeneration: Long? = null,
)

sealed interface CloudHistoricalStartResult {
    data class Started(val operation: CloudHistoricalOperation) : CloudHistoricalStartResult
    data class Existing(val operation: CloudHistoricalOperation) : CloudHistoricalStartResult
    data class Failed(val failure: CloudReadFailure) : CloudHistoricalStartResult
    data object Unconfigured : CloudHistoricalStartResult
    data object NoSteamAccount : CloudHistoricalStartResult
    data object NoAvailableRange : CloudHistoricalStartResult
}

sealed interface CloudHistoricalPageResult {
    data class Success(
        val operation: CloudHistoricalOperation,
        val snapshot: CloudPresenceSnapshot,
    ) : CloudHistoricalPageResult
    data class Failed(val failure: CloudReadFailure) : CloudHistoricalPageResult
    data object OperationNotFound : CloudHistoricalPageResult
    data object AlreadyComplete : CloudHistoricalPageResult
    data object Unconfigured : CloudHistoricalPageResult
    data object NoSteamAccount : CloudHistoricalPageResult
}

enum class CloudHistoricalBatchStopReason {
    PAGE_LIMIT,
    TIME_LIMIT,
}

sealed interface CloudHistoricalBatchResult {
    data class Complete(
        val operation: CloudHistoricalOperation,
        val pagesFetched: Int,
        val transitionsFetched: Int,
    ) : CloudHistoricalBatchResult

    /** A successful, durable partial batch that requires a separate explicit continuation. */
    data class Partial(
        val operation: CloudHistoricalOperation,
        val pagesFetched: Int,
        val transitionsFetched: Int,
        val stopReason: CloudHistoricalBatchStopReason,
    ) : CloudHistoricalBatchResult

    data class Failed(
        val operation: CloudHistoricalOperation?,
        val pagesFetched: Int,
        val transitionsFetched: Int,
        val failure: CloudReadFailure,
    ) : CloudHistoricalBatchResult

    data object OperationNotFound : CloudHistoricalBatchResult
    data object Unconfigured : CloudHistoricalBatchResult
    data object NoSteamAccount : CloudHistoricalBatchResult
}

sealed interface CloudHistoricalApplyGuardResult<out T> {
    data class Ready<T>(val operation: CloudHistoricalOperation, val value: T) : CloudHistoricalApplyGuardResult<T>
    data object OperationNotFound : CloudHistoricalApplyGuardResult<Nothing>
    data object AcquisitionIncomplete : CloudHistoricalApplyGuardResult<Nothing>
    data object Unconfigured : CloudHistoricalApplyGuardResult<Nothing>
    data object NoSteamAccount : CloudHistoricalApplyGuardResult<Nothing>
    data object StaleIdentity : CloudHistoricalApplyGuardResult<Nothing>
}

data class CloudPresenceHistoricalRange(
    val fromAt: Long,
    val throughAt: Long,
    val positionAt: Long? = null,
) {
    init {
        require(fromAt <= throughAt)
        require(positionAt == null || positionAt in fromAt..throughAt)
    }
}

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
    val readerIdentity: CloudReaderIdentity? = null,
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

sealed interface CloudPresenceRangeLookupResult {
    data object Unconfigured : CloudPresenceRangeLookupResult
    data object NoSteamAccount : CloudPresenceRangeLookupResult
    data class Success(val metadata: CloudPresenceRangeMetadata) : CloudPresenceRangeLookupResult
    data class Failed(val failure: CloudReadFailure) : CloudPresenceRangeLookupResult
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
    private val routineWorkCanceller: CloudRoutineWorkCancellation? = null,
    private val readerStateMutex: CloudReaderStateMutex = CloudReaderStateMutex(),
    private val historicalStore: CloudHistoricalStore = EmptyCloudHistoricalStore,
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
    private val cloudStateMutex = readerStateMutex.mutex
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
            // The initial re-read is serialized with the same state mutex that removal and
            // promotion hold, so a blocked or slow read cannot publish a stale endpoint after
            // the durable credentials changed under it: unsynchronized, a read of old pair A
            // could finish after removeConfiguration() committed its credential clear and
            // retired the removal marker, resurrecting the removed endpoint in memory with no
            // tombstone left for recovery (and likewise republishing A across an A->B
            // replacement). Under the mutex the read either sees the pre-change credentials or
            // the post-change emptiness — never a value it can write back after the change
            // committed.
            cloudStateMutex.withLock {
                configurationState.value = credentialsStore.readCloudCredentials()?.toConfiguration()
            }
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

    /** Authenticated, cursor-independent lookup of the earliest retained cloud evidence. */
    suspend fun lookupRangeMetadata(): CloudPresenceRangeLookupResult {
        val start = cloudStateMutex.withLock {
            RangeMetadataStart(
                credentials = credentialsStore.readCloudCredentials(),
                account = credentialsProvider.currentCredentials()?.steamId,
                generation = accountGeneration.get(),
                readerGeneration = settings.cloudReaderGeneration.first(),
            )
        }
        val credentials = start.credentials ?: return CloudPresenceRangeLookupResult.Unconfigured
        val account = start.account ?: return CloudPresenceRangeLookupResult.NoSteamAccount
        val response = try {
            api.read(
                endpoint = credentials.endpoint,
                authorization = "Bearer " + credentials.token.trim(),
                position = null,
                mode = "range",
                from = null,
                through = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (http: HttpException) {
            return CloudPresenceRangeLookupResult.Failed(
                when {
                    http.code() == 401 || http.code() == 403 -> CloudReadFailure.REJECTED_CREDENTIAL
                    http.isUnusableResponse() -> CloudReadFailure.UNUSABLE_RESPONSE
                    else -> CloudReadFailure.UNREACHABLE
                },
            )
        } catch (_: SerializationException) {
            return CloudPresenceRangeLookupResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
        } catch (_: Exception) {
            return CloudPresenceRangeLookupResult.Failed(CloudReadFailure.UNREACHABLE)
        }
        if (response.account?.trim() != account) {
            return CloudPresenceRangeLookupResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
        }
        val metadata = runCatching { response.toRangeMetadata(account) }
            .getOrElse { return CloudPresenceRangeLookupResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE) }
        return cloudStateMutex.withLock {
            if (start.generation != accountGeneration.get() ||
                credentialsProvider.currentCredentials()?.steamId != account ||
                settings.cloudReaderGeneration.first() != start.readerGeneration ||
                credentialsStore.readCloudCredentials() != credentials
            ) {
                CloudPresenceRangeLookupResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
            } else {
                val endpointIdentity = normalizeEndpoint(credentials.endpoint)
                    ?: return@withLock CloudPresenceRangeLookupResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
                CloudPresenceRangeLookupResult.Success(
                    metadata.copy(
                        readerGeneration = start.readerGeneration,
                        endpointIdentity = endpointIdentity,
                        accountGeneration = start.generation,
                    ),
                )
            }
        }
    }

    /** Creates the immutable historical operation from a fresh, identity-bound range lookup. */
    suspend fun beginHistoricalAcquisition(
        selectedStartAt: Long,
        effectiveStartAt: Long,
        metadata: CloudPresenceRangeMetadata,
        startChoice: String = "RECENT_31_DAYS",
        zoneId: String = "UTC",
        confirmedCutoffAt: Long? = null,
    ): CloudHistoricalStartResult = cloudStateMutex.withLock {
        val credentials = credentialsStore.readCloudCredentials()
            ?: return@withLock CloudHistoricalStartResult.Unconfigured
        val account = credentialsProvider.currentCredentials()?.steamId
            ?: return@withLock CloudHistoricalStartResult.NoSteamAccount
        val endpointIdentity = normalizeEndpoint(credentials.endpoint)
            ?: return@withLock CloudHistoricalStartResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)

        if (metadata.earliestObservedAt == null) {
            return@withLock CloudHistoricalStartResult.NoAvailableRange
        }
        if (selectedStartAt > effectiveStartAt ||
            effectiveStartAt > metadata.readAt || selectedStartAt > metadata.readAt
        ) {
            return@withLock CloudHistoricalStartResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
        }
        if (confirmedCutoffAt != null &&
            (confirmedCutoffAt <= effectiveStartAt || confirmedCutoffAt > metadata.readAt)
        ) {
            return@withLock CloudHistoricalStartResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
        }
        if (metadata.account != account ||
            metadata.readerGeneration == null ||
            metadata.readerGeneration != settings.cloudReaderGeneration.first() ||
            metadata.endpointIdentity != endpointIdentity ||
            metadata.accountGeneration == null ||
            metadata.accountGeneration != accountGeneration.get()
        ) {
            return@withLock CloudHistoricalStartResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
        }

        val existing = historicalStore.operationForIdentity(
            account = account,
            readerGeneration = metadata.readerGeneration,
            endpointIdentity = endpointIdentity,
            state = CloudHistoricalStates.ACQUIRING,
        ) ?: historicalStore.operationForIdentity(
            account = account,
            readerGeneration = metadata.readerGeneration,
            endpointIdentity = endpointIdentity,
            state = CloudHistoricalStates.COMPLETE,
        )
        if (existing != null) return@withLock CloudHistoricalStartResult.Existing(existing)

        val frozenCurrent = metadata.current?.takeIf { current ->
            val observedAt = current.observedAt
            observedAt != null && observedAt <= metadata.readAt &&
                (current.since == null || current.since <= observedAt)
        }
        val now = time.nowMillis()
        val operation = CloudHistoricalOperation(
            operationId = UUID.randomUUID().toString(),
            account = account,
            readerGeneration = metadata.readerGeneration,
            endpointIdentity = endpointIdentity,
            startChoice = startChoice,
            zoneId = zoneId,
            selectedStartAt = selectedStartAt,
            fromAt = effectiveStartAt,
            throughAt = metadata.readAt,
            confirmedCutoffAt = confirmedCutoffAt,
            frozenCurrentObservedAt = frozenCurrent?.observedAt,
            frozenCurrentAppId = frozenCurrent?.appId,
            frozenCurrentGameName = frozenCurrent?.gameName,
            frozenCurrentPersonastate = frozenCurrent?.personastate,
            frozenCurrentSince = frozenCurrent?.since,
            frozenCurrentCoverageLapseFrom = frozenCurrent?.coverageLapseFrom,
            frozenCurrentCoverageLapseRecoveredAt = frozenCurrent?.coverageLapseRecoveredAt,
            frozenCurrentSchemaVersion = frozenCurrent?.schemaVersion,
            createdAt = now,
            updatedAt = now,
        )
        try {
            if (historicalStore.insertOperation(operation)) {
                CloudHistoricalStartResult.Started(operation)
            } else {
                CloudHistoricalStartResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CloudHistoricalStartResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
        }
    }

    /** Returns durable acquisition progress for the currently configured account and reader. */
    suspend fun currentHistoricalOperation(): CloudHistoricalOperation? = cloudStateMutex.withLock {
        val credentials = credentialsStore.readCloudCredentials() ?: return@withLock null
        val account = credentialsProvider.currentCredentials()?.steamId ?: return@withLock null
        val endpointIdentity = normalizeEndpoint(credentials.endpoint) ?: return@withLock null
        val readerGeneration = settings.cloudReaderGeneration.first()
        historicalStore.operationForIdentity(
            account, readerGeneration, endpointIdentity, CloudHistoricalStates.ACQUIRING,
        ) ?: historicalStore.operationForIdentity(
            account, readerGeneration, endpointIdentity, CloudHistoricalStates.COMPLETE,
        )
    }

    /** Fetches and commits exactly one historical page under the shared per-page read sequence. */
    suspend fun acquireHistoricalPage(
        operationId: String,
        consume: suspend (CloudPresenceSnapshot) -> Unit,
    ): CloudHistoricalPageResult = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        val captureResult = cloudStateMutex.withLock {
            val operation = historicalStore.operation(operationId)
                ?: return@withLock HistoricalPageCaptureResult.Rejected(
                    CloudHistoricalPageResult.OperationNotFound,
                )
            if (operation.acquisitionComplete || operation.state != CloudHistoricalStates.ACQUIRING) {
                return@withLock HistoricalPageCaptureResult.Rejected(
                    CloudHistoricalPageResult.AlreadyComplete,
                )
            }
            val credentials = credentialsStore.readCloudCredentials()
                ?: return@withLock HistoricalPageCaptureResult.Rejected(CloudHistoricalPageResult.Unconfigured)
            val account = credentialsProvider.currentCredentials()?.steamId
                ?: return@withLock HistoricalPageCaptureResult.Rejected(CloudHistoricalPageResult.NoSteamAccount)
            val endpointIdentity = normalizeEndpoint(credentials.endpoint)
                ?: return@withLock HistoricalPageCaptureResult.Rejected(
                    CloudHistoricalPageResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE),
                )
            val readerGeneration = settings.cloudReaderGeneration.first()
            if (account != operation.account || readerGeneration != operation.readerGeneration ||
                endpointIdentity != operation.endpointIdentity
            ) {
                return@withLock HistoricalPageCaptureResult.Rejected(
                    CloudHistoricalPageResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH),
                )
            }
            HistoricalPageCaptureResult.Ready(
                HistoricalPageStart(
                    operation = operation,
                    credentials = credentials,
                    accountGeneration = accountGeneration.get(),
                ),
            )
        }
        if (captureResult is HistoricalPageCaptureResult.Rejected) return@withLock captureResult.result
        val capture = (captureResult as HistoricalPageCaptureResult.Ready).start
        val operation = capture.operation
        val requestedRange = CloudPresenceHistoricalRange(
            fromAt = operation.fromAt,
            throughAt = operation.throughAt,
            positionAt = operation.lastPositionAt,
        )
        val remote = fetch(
            endpoint = capture.credentials.endpoint,
            token = capture.credentials.token,
            expectedAccount = operation.account,
            position = operation.lastPositionAt?.let(::formatCloudPresenceUtcInstant),
            from = formatCloudPresenceUtcInstant(operation.fromAt),
            through = formatCloudPresenceUtcInstant(operation.throughAt),
            requestedRange = requestedRange,
        )
        when (remote) {
            is RemoteReadResult.AccountMismatch ->
                CloudHistoricalPageResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
            is RemoteReadResult.Failure -> CloudHistoricalPageResult.Failed(remote.failure)
            is RemoteReadResult.Success -> cloudStateMutex.withLock {
                val endpointIdentity = normalizeEndpoint(capture.credentials.endpoint)
                if (capture.accountGeneration != accountGeneration.get() ||
                    credentialsProvider.currentCredentials()?.steamId != operation.account ||
                    settings.cloudReaderGeneration.first() != operation.readerGeneration ||
                    credentialsStore.readCloudCredentials() != capture.credentials ||
                    endpointIdentity != operation.endpointIdentity
                ) {
                    return@withLock CloudHistoricalPageResult.Failed(CloudReadFailure.ACCOUNT_MISMATCH)
                }

                val parsed = remote.parsed
                val priorBoundary = if (operation.lastPositionAt == null) {
                    parsed.predecessor
                } else {
                    historicalStore.boundary(operation.operationId, CloudHistoricalStates.BOUNDARY_LAST_TRANSITION)
                        ?.toTransition()
                }
                val openingBoundary = priorBoundary?.takeIf { boundary ->
                    parsed.transitions.firstOrNull()?.at?.let { boundary.at < it }
                        ?: (parsed.current != null)
                }
                val fixedRead = parsed.copy(current = operation.frozenCurrent())
                val snapshot = fixedRead.toSnapshot(openingBoundary).copy(
                    readerIdentity = CloudReaderIdentity(operation.account, operation.readerGeneration),
                )
                val pageNumber = operation.pagesFetched + 1
                if (operation.lastIngestedPageNumber > pageNumber) {
                    return@withLock CloudHistoricalPageResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
                }
                val stagedIntervals = snapshot.intervals.map { interval ->
                    interval.toHistoricalEntity(operation)
                }
                val stagedBoundaries = buildList {
                    if (operation.lastPositionAt == null && parsed.predecessor != null) {
                        add(parsed.predecessor.toHistoricalEntity(operation, CloudHistoricalStates.BOUNDARY_PREDECESSOR))
                    }
                    parsed.transitions.maxByOrNull { it.at }?.let { last ->
                        add(last.toHistoricalEntity(operation, CloudHistoricalStates.BOUNDARY_LAST_TRANSITION))
                    }
                }
                val progress = operation.copy(
                    lastPositionAt = parsed.nextPosition?.let(::requiredStrictUtcInstant) ?: operation.lastPositionAt,
                    pagesFetched = operation.pagesFetched + 1,
                    lastIngestedPageNumber = maxOf(operation.lastIngestedPageNumber, pageNumber),
                    transitionsFetched = operation.transitionsFetched + parsed.transitions.size,
                    coveredStartAt = listOfNotNull(
                        operation.coveredStartAt,
                        parsed.transitions.minOfOrNull { it.at },
                        operation.frozenCurrentObservedAt?.takeIf { parsed.transitions.isEmpty() },
                    ).filter { it >= operation.fromAt }.minOrNull(),
                    coveredEndAt = maxOf(operation.coveredEndAt ?: Long.MIN_VALUE, snapshot.windowEnd)
                        .takeIf { it != Long.MIN_VALUE },
                    acquisitionComplete = !parsed.hasMore,
                    state = if (parsed.hasMore) CloudHistoricalStates.ACQUIRING else CloudHistoricalStates.COMPLETE,
                    updatedAt = time.nowMillis(),
                )

                try {
                    if (operation.lastIngestedPageNumber < pageNumber) {
                        consume(snapshot)
                        if (!historicalStore.markPageIngested(operation, pageNumber)) {
                            return@withLock CloudHistoricalPageResult.Failed(
                                CloudReadFailure.UNUSABLE_RESPONSE,
                            )
                        }
                    }
                    pendingEvidence.retain(
                        operation.account,
                        operation.readerGeneration,
                        operation.fromAt,
                        snapshot.intervals,
                        parsed.transitions.maxByOrNull { it.at },
                    )
                    val committed = historicalStore.commitPage(
                        previous = operation,
                        intervals = stagedIntervals,
                        boundaries = stagedBoundaries,
                        progress = progress,
                    ) ?: return@withLock CloudHistoricalPageResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
                    CloudHistoricalPageResult.Success(committed, snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    CloudHistoricalPageResult.Failed(CloudReadFailure.UNUSABLE_RESPONSE)
                }
            }
        }
    }

    /**
     * Performs one user-initiated batch. Every page releases the shared read sequence before the
     * next page, and a capped batch returns durable progress for an explicit later continuation.
     */
    suspend fun acquireHistoricalBatch(
        operationId: String,
        onProgress: suspend (CloudHistoricalOperation, Int, Int) -> Unit = { _, _, _ -> },
        consume: suspend (CloudPresenceSnapshot) -> Unit,
    ): CloudHistoricalBatchResult = acquireHistoricalBatchWithLimits(
        operationId = operationId,
        pageLimit = MAX_HISTORICAL_BATCH_PAGES,
        timeBudgetNanos = MAX_HISTORICAL_BATCH_NANOS,
        monotonicNowNanos = System::nanoTime,
        consume = consume,
        onProgress = onProgress,
    )

    internal suspend fun acquireHistoricalBatchWithLimits(
        operationId: String,
        pageLimit: Int,
        timeBudgetNanos: Long,
        monotonicNowNanos: () -> Long,
        onProgress: suspend (CloudHistoricalOperation, Int, Int) -> Unit = { _, _, _ -> },
        consume: suspend (CloudPresenceSnapshot) -> Unit,
    ): CloudHistoricalBatchResult {
        require(pageLimit in 1..MAX_HISTORICAL_BATCH_PAGES)
        require(timeBudgetNanos in 1L..MAX_HISTORICAL_BATCH_NANOS)
        val initial = historicalStore.operation(operationId)
            ?: return CloudHistoricalBatchResult.OperationNotFound
        if (initial.acquisitionComplete) {
            return CloudHistoricalBatchResult.Complete(initial, pagesFetched = 0, transitionsFetched = 0)
        }
        if (initial.state != CloudHistoricalStates.ACQUIRING) {
            return CloudHistoricalBatchResult.Failed(
                operation = initial,
                pagesFetched = 0,
                transitionsFetched = 0,
                failure = CloudReadFailure.UNUSABLE_RESPONSE,
            )
        }

        val startedAtNanos = monotonicNowNanos()
        var operation = initial
        var pagesFetched = 0
        var transitionsFetched = 0
        while (true) {
            when (val page = acquireHistoricalPage(operationId, consume)) {
                is CloudHistoricalPageResult.Success -> {
                    operation = page.operation
                    pagesFetched++
                    transitionsFetched += page.snapshot.observationCount
                    onProgress(operation, pagesFetched, transitionsFetched)
                    if (operation.acquisitionComplete) {
                        return CloudHistoricalBatchResult.Complete(
                            operation, pagesFetched, transitionsFetched,
                        )
                    }
                    if (pagesFetched >= pageLimit) {
                        return CloudHistoricalBatchResult.Partial(
                            operation,
                            pagesFetched,
                            transitionsFetched,
                            CloudHistoricalBatchStopReason.PAGE_LIMIT,
                        )
                    }
                    yield()
                    if (monotonicNowNanos() - startedAtNanos >= timeBudgetNanos) {
                        return CloudHistoricalBatchResult.Partial(
                            operation,
                            pagesFetched,
                            transitionsFetched,
                            CloudHistoricalBatchStopReason.TIME_LIMIT,
                        )
                    }
                }
                is CloudHistoricalPageResult.Failed -> return CloudHistoricalBatchResult.Failed(
                    operation = historicalStore.operation(operationId) ?: operation,
                    pagesFetched = pagesFetched,
                    transitionsFetched = transitionsFetched,
                    failure = page.failure,
                )
                CloudHistoricalPageResult.OperationNotFound ->
                    return CloudHistoricalBatchResult.OperationNotFound
                CloudHistoricalPageResult.AlreadyComplete -> {
                    val latest = historicalStore.operation(operationId) ?: operation
                    return if (latest.acquisitionComplete) {
                        CloudHistoricalBatchResult.Complete(latest, pagesFetched, transitionsFetched)
                    } else {
                        CloudHistoricalBatchResult.Failed(
                            latest,
                            pagesFetched,
                            transitionsFetched,
                            CloudReadFailure.UNUSABLE_RESPONSE,
                        )
                    }
                }
                CloudHistoricalPageResult.Unconfigured -> return CloudHistoricalBatchResult.Unconfigured
                CloudHistoricalPageResult.NoSteamAccount -> return CloudHistoricalBatchResult.NoSteamAccount
            }
        }
    }

    /**
     * Runs application of a completed stage only while its captured account, reader generation,
     * and endpoint are still active. The caller must acquire the Steam-sync and derived-write
     * locks before entering; this method then takes the read-sequence and reader-state locks in
     * the established order and holds both until the application block returns.
     */
    suspend fun <T> withCurrentHistoricalOperationForApply(
        operationId: String,
        apply: suspend (CloudHistoricalOperation) -> T,
    ): CloudHistoricalApplyGuardResult<T> = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        cloudStateMutex.withLock {
            val operation = historicalStore.operation(operationId)
                ?: return@withLock CloudHistoricalApplyGuardResult.OperationNotFound
            if (!operation.acquisitionComplete || operation.state != CloudHistoricalStates.COMPLETE) {
                return@withLock CloudHistoricalApplyGuardResult.AcquisitionIncomplete
            }
            val credentials = credentialsStore.readCloudCredentials()
                ?: return@withLock CloudHistoricalApplyGuardResult.Unconfigured
            val account = credentialsProvider.currentCredentials()?.steamId
                ?: return@withLock CloudHistoricalApplyGuardResult.NoSteamAccount
            val endpointIdentity = normalizeEndpoint(credentials.endpoint)
            if (account != operation.account ||
                settings.cloudReaderGeneration.first() != operation.readerGeneration ||
                endpointIdentity != operation.endpointIdentity
            ) {
                return@withLock CloudHistoricalApplyGuardResult.StaleIdentity
            }
            CloudHistoricalApplyGuardResult.Ready(operation, apply(operation))
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
     * discarded. The marker survives the generation commit itself and is removed only once the
     * post-commit cleanup has run, so a death between the commit and that cleanup still finds a
     * recovery signal and re-runs the same steps idempotently; this path therefore also tolerates
     * the generation already equalling the target. The replacement page's ingest runs only after
     * that commit, so its derived sessions and ingest watermark never exist without a marker: a
     * marker-less restart cannot inherit them, and a marker-covered death always completes the
     * promotion that owns them. The old reader's ingest watermark is cleared inside that same
     * marker-covered phase, just before the generation commit, so a marker-less restart keeps the
     * old reader's ingest fence intact for its next read; this recovery repeats that clear only
     * when it performs the commit itself, because once the generation has advanced the watermark
     * is either already gone or the replacement's own ingest fence. This is what makes endpoint
     * replacement one
     * logical transaction across the pending-evidence Room store, Settings DataStore, and the
     * encrypted credential DataStore: no read can observe the replacement reader's evidence paired
     * with the old credentials, or the old generation with its evidence already retired.
     *
     * A marker older than the persisted generation is refused rather than completed: removal and
     * account-change fencing clears the marker in the same edit that advances the generation (and
     * jumps past a marked target), so a surviving older marker can only come from state an older
     * build persisted mid-fence. Completing it would resurrect a replacement that fencing already
     * retired or write the older target back, decreasing the generation. The abandoned attempt's
     * staged credentials and page rows are discarded with it; evidence bound to the current
     * generation is kept. A marker with neither staged nor active credentials is abandoned the
     * same way: it can only be a removal whose atomic credential clear landed before its fence,
     * and completing it would run promotion effects for a reader that was being removed.
     *
     * Caller must hold [cloudReadSequenceMutex]; the account-state mutex is taken here so
     * invalidation cannot interleave with the completed promotion's writes.
     */
    private suspend fun recoverStagedPromotionLocked() {
        cloudStateMutex.withLock {
            // An interrupted removal's post-credential cleanup runs before promotion recovery,
            // so the removal's fence sees any surviving promotion marker intact and can advance
            // past its target in one edit; promotion recovery then finds no marker and performs
            // its ordinary housekeeping on state the removal already cleared.
            recoverInterruptedRemovalLocked()
            val marker = settings.cloudReaderPromotionTarget.first()
            val staged = credentialsStore.readStagedCloudCredentials()
            if (marker == null) {
                if (staged != null) credentialsStore.clearStagedCloudCredentials()
                // A staged page without a marker is an abandoned attempt: discard its rows so no
                // later read or verification can combine them with the active reader's evidence.
                // Only rows bound to the current generation survive, which also retires evidence
                // a removal/account-change fence left staged when it died before its own clear.
                // This is best-effort housekeeping: every read looks up evidence by the current
                // generation, so a row bound to any other generation is inert even if the clear
                // fails, and a transient Room failure must not fail the read itself.
                val account = credentialsProvider.currentCredentials()?.steamId
                if (account != null) {
                    runCatching {
                        pendingEvidence.clearExcept(account, settings.cloudReaderGeneration.first())
                    }
                }
                runCatching {
                    clearUnappliedHistoricalForCurrentReader(
                        account,
                        settings.cloudReaderGeneration.first(),
                    )
                }
                return@withLock
            }
            val account = credentialsProvider.currentCredentials()?.steamId ?: return@withLock
            val generationBefore = settings.cloudReaderGeneration.first()
            if (marker < generationBefore) {
                // The durable generation has moved past the marker: removal/account-change
                // fencing has already retired this promotion. Completing it would resurrect the
                // fenced replacement or write the older marker back, decreasing the generation,
                // so the attempt is abandoned and the newer fence is kept. The staged page's
                // rows are inert once the generation has passed them, so discarding them is
                // best-effort; the refusal itself does not depend on it.
                if (staged != null) credentialsStore.clearStagedCloudCredentials()
                runCatching { pendingEvidence.clearExcept(account, generationBefore) }
                runCatching { clearUnappliedHistoricalForCurrentReader(account, generationBefore) }
                settings.clearCloudReaderPromotion()
                return@withLock
            }
            if (staged == null && credentialsStore.readCloudCredentials() == null) {
                // Both credential pairs are gone while the marker survives: a removal destroyed
                // them in one atomic store edit and died before its generation fence. Nothing
                // remains to promote and no active reader exists to keep, so the attempt is
                // abandoned like the pre-marker window instead of completed, which would run
                // promotion effects (generation commit, ingest-cursor clear, work re-cancel,
                // routine-policy init) for a reader that was being removed.
                runCatching { pendingEvidence.clearExcept(account, generationBefore) }
                historicalStore.deleteUnappliedOperations()
                settings.clearCloudReaderPromotion()
                return@withLock
            }
            if (staged != null) credentialsStore.commitStagedCloudCredentials()
            // Mirror the normal path: the old reader's ingest fence is retired before the
            // generation commit. While the persisted generation still names the old reader,
            // this promotion's ingest cannot have run and the watermark is the old reader's,
            // so clearing it first makes a death after the clear but before the commit safely
            // repeatable; a death after the commit then always finds the watermark either
            // already gone or the replacement's own ingest fence. Once the generation already
            // equals the target the clear must be skipped, because a re-read of the committed
            // page relies on the replacement's own fence to avoid re-crediting its fold.
            if (generationBefore != marker) settings.clearCloudIngestPosition()
            val target = settings.finishCloudReaderPromotion() ?: return@withLock
            // Re-apply the post-commit effects a crash may have skipped, so the replacement
            // never resumes the old reader's watermark. The marker is the recovery signal for
            // a death between the generation commit and this cleanup, so it stays until every
            // step below has run; the final clear retires it.
            pendingEvidence.clearExcept(account, target)
            clearUnappliedHistoricalForCurrentReader(account, target)
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = false)
            settings.clearCloudReadPosition()
            settings.clearCloudReadSummary()
            settings.initializeCloudRoutinePolicy()
            settings.clearCloudReaderPromotion()
        }
    }

    /**
     * Finishes a reader removal whose credential-clear commit point landed but whose
     * post-credential cleanup was interrupted by process death. The removal marker is written
     * before the credential clear and stays until every cleanup step below has run, so the
     * persisted routine policy/cooldown, ordering watermarks, cursors, and read summary cannot
     * survive to be inherited by a later reader: without this, a successful reconfiguration
     * would call [initializeCloudRoutinePolicy][com.example.backlogium.data.repo.SettingsRepository.initializeCloudRoutinePolicy],
     * which deliberately preserves policy and cooldown for endpoint replacement and cannot tell
     * the removed reader's state from a same-reader replacement's.
     *
     * The marker records the fence target computed before the commit point, so the fence here
     * runs only when the persisted generation has not already reached it: the interrupted
     * removal's own fence may have landed before the crash, and re-running it would advance the
     * generation a second time. When credentials are still present the removal died before its
     * commit point and is an abandoned attempt: the reader remains configured and only the
     * marker is retired. Once the commit point has landed the in-memory configuration and
     * snapshot are cleared here too, mirroring the normal path's cleanup: they are dropped as
     * soon as the commit point is confirmed, before the durable cleanup below, so a failure in
     * any later step — which keeps the marker for a retry — cannot leave the same-process
     * flows observing a reader the durable store has already removed. Any failure below keeps
     * the marker, so the next recovery retries the idempotent cleanup instead of losing it.
     *
     * Caller must hold [cloudStateMutex] (via [recoverStagedPromotionLocked], or
     * [runAccountChangeTransition] so the account reset cannot clear the marker first).
     */
    private suspend fun recoverInterruptedRemovalLocked() {
        val target = settings.cloudReaderRemovalTarget.first() ?: return
        if (credentialsStore.readCloudCredentials() == null) {
            // The commit point has landed: nothing remains that a read can use or a restart
            // can promote, so the reader is unconfigured whatever happens below. Clear the
            // same-process configuration and snapshot before the fallible durable cleanup so
            // an exception there cannot leave them reporting the removed endpoint while the
            // marker survives for a retry.
            configurationState.value = null
            snapshotState.value = null
            if (settings.cloudReaderGeneration.first() < target) {
                settings.abandonCloudReaderPromotion()
            }
            pendingEvidence.clear()
            historicalStore.deleteUnappliedOperations()
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
            settings.clearCloudReadPosition()
            settings.clearCloudIngestPosition()
            settings.clearCloudRoutinePolicy()
            settings.clearCloudReadSummary()
        }
        settings.clearCloudReaderRemoval()
    }

    /**
     * Recovery, identity validation, admission, and the complete routine catch-up are one
     * serialized operation. In particular, endpoint promotion cannot land after this reader has
     * consumed its cooldown but before its first page is read. [onAdmitted] runs under the
     * sequence lock so the worker can still record a failed outcome if a later persistence step
     * throws.
     */
    suspend fun runRoutineCatchUp(
        account: String,
        generation: Long,
        consume: suspend (CloudPresenceSnapshot) -> Unit,
        onAdmitted: (Long) -> Unit = {},
    ): CloudRoutineAttempt = cloudReadSequenceMutex.withLock {
        recoverStagedPromotionLocked()
        val (admission, admittedAt) = cloudStateMutex.withLock {
            if (credentialsProvider.currentCredentials()?.steamId != account ||
                credentialsStore.readCloudCredentials() == null ||
                settings.cloudReaderGeneration.first() != generation
            ) {
                CloudRoutineAdmission.UNAVAILABLE to null
            } else {
                val at = time.nowMillis()
                val result = settings.admitCloudRoutine(at)
                result to if (result == CloudRoutineAdmission.ADMITTED) at else null
            }
        }
        if (admission != CloudRoutineAdmission.ADMITTED) {
            return@withLock CloudRoutineAttempt.NotAdmitted(admission)
        }
        val at = checkNotNull(admittedAt)
        onAdmitted(at)
        CloudRoutineAttempt.Admitted(
            at = at,
            outcome = readRoutineCatchUpLocked(account, generation, consume),
        )
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
                        // Discard rows an interrupted replacement left staged, so this
                        // verification's page can never combine with a previous attempt's
                        // evidence under the same target generation. A's ingest cursor is
                        // deliberately left untouched here: it is cleared only after the
                        // durable promotion commit below, so an attempt abandoned before the
                        // marker leaves A fully intact for its next read.
                        pendingEvidence.clearExcept(account, persistedGeneration)
                        try {
                            pendingEvidence.retain(
                                account, readerGeneration, snapshot.windowStart,
                                snapshot.intervals, result.parsed.transitions.maxByOrNull { it.at },
                            )
                            credentialsStore.stageCloudCredentials(normalizedEndpoint, normalizedToken)
                            settings.markCloudReaderPromotion(readerGeneration)
                        } catch (failure: Throwable) {
                            // Nothing passed the durable marker: the old verified reader is
                            // still the active one, so abandon the staged page. The ingest has
                            // not run yet and its cursor was never touched, so there are no
                            // derived effects to roll back.
                            credentialsStore.clearStagedCloudCredentials()
                            pendingEvidence.clearExcept(account, persistedGeneration)
                            throw failure
                        }
                        // The marker is the commit point. Every step below it is idempotent,
                        // so a persistence failure or process death after it is finished by
                        // recoverStagedPromotionLocked on the next access rather than rolled
                        // back: the old credentials are already replaced. The marker stays
                        // until the final step below, so a death mid-cleanup still leaves the
                        // recovery signal that points at these idempotent effects.
                        credentialsStore.commitStagedCloudCredentials()
                        // Retire A's ingest fence once B's credentials are active, but before
                        // the generation commit: the persisted generation then tells recovery
                        // which watermark the cursor holds. While it still names A, this
                        // promotion's ingest cannot have run and the cursor is A's fence, so
                        // recovery clears it when it performs the commit; once it names B, the
                        // cursor is either already cleared or B's own ingest fence, which
                        // recovery must keep so a re-read of the committed page cannot
                        // re-credit B's fold.
                        settings.clearCloudIngestPosition()
                        settings.finishCloudReaderPromotion()
                        // Only now that the credential+generation promotion is durably
                        // committed is the old generation's evidence retired.
                        pendingEvidence.clearExcept(account, readerGeneration)
                        clearUnappliedHistoricalExcept(
                            account,
                            readerGeneration,
                            normalizedEndpoint,
                        )
                        // The ingest is part of the committed promotion: it runs only after
                        // the marker and the credential+generation commit, and before the
                        // read watermark advances below, so its derived sessions and ingest
                        // cursor can never exist without a promotion marker. A's ingest
                        // cursor was cleared just before the generation commit, so a death
                        // anywhere before the marker abandons the attempt with A's watermark
                        // intact, while a death after the marker is finished by recovery as
                        // the replacement's promotion. A death after the clear but before the
                        // ingest returns leaves no ingest watermark, so the replacement
                        // re-ingests the page; a death after it keeps the effects as the
                        // replacement's own. A failed ingest also leaves the page retryable,
                        // since the read position has not advanced.
                        consume(snapshot)
                        // Cancel only after acquiring the sequence lock: an admitted routine
                        // must finish its page commits before this advisory cleanup can stop it.
                        routineWorkCanceller?.cancelOldReaderWork(removePeriodic = false)
                        settings.clearCloudReadPosition()
                        settings.clearCloudReadSummary()
                        persistVerifiedConfiguration(
                            credentials = CloudCredentials(normalizedEndpoint, normalizedToken),
                            trigger = CloudReadTrigger.SETTINGS_VERIFICATION,
                            parsed = result.parsed,
                        )
                        settings.initializeCloudRoutinePolicy()
                        // Every post-promotion effect above is durable; only now is the
                        // recovery marker retired, so a death at any earlier point re-runs
                        // them idempotently instead of leaving the old reader's state behind.
                        settings.clearCloudReaderPromotion()
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

    private suspend fun clearUnappliedHistoricalForCurrentReader(
        account: String?,
        readerGeneration: Long,
    ) {
        val endpointIdentity = credentialsStore.readCloudCredentials()
            ?.endpoint
            ?.let(::normalizeEndpoint)
        if (account == null || endpointIdentity == null) {
            historicalStore.deleteUnappliedOperations()
        } else {
            historicalStore.deleteUnappliedOperationsNotMatching(
                account,
                readerGeneration,
                endpointIdentity,
            )
        }
    }

    private suspend fun clearUnappliedHistoricalExcept(
        account: String,
        readerGeneration: Long,
        endpoint: String,
    ) {
        val endpointIdentity = normalizeEndpoint(endpoint)
        if (endpointIdentity == null) {
            historicalStore.deleteUnappliedOperations()
        } else {
            historicalStore.deleteUnappliedOperationsNotMatching(
                account,
                readerGeneration,
                endpointIdentity,
            )
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
        readRoutineCatchUpLocked(expectedAccount, expectedGeneration, consume)
    }

    /** Caller holds [cloudReadSequenceMutex], after staged promotion recovery. */
    private suspend fun readRoutineCatchUpLocked(
        expectedAccount: String?,
        expectedGeneration: Long?,
        consume: suspend (CloudPresenceSnapshot) -> Unit,
    ): CloudCatchUpResult {
        val account = credentialsProvider.currentCredentials()?.steamId ?: return CloudCatchUpResult.Unavailable
        if (credentialsStore.readCloudCredentials() == null) return CloudCatchUpResult.Unavailable
        val generation = settings.cloudReaderGeneration.first()
        if ((expectedAccount != null && account != expectedAccount) ||
            (expectedGeneration != null && generation != expectedGeneration)
        ) return CloudCatchUpResult.Unavailable
        var transitions = 0
        for (page in 0 until MAX_ROUTINE_PAGES) {
            if (settings.cloudRoutineState.first().policy == CloudRoutinePolicy.OFF_MANUAL_ONLY) {
                // Finish the current page's effects and cursor before observing Off here. This
                // boundary check lets a disabled routine attempt stop without dropping committed
                // page effects or starting another request.
                return if (page == 0) CloudCatchUpResult.Unavailable
                else CloudCatchUpResult.Partial(page, transitions)
            }
            if (credentialsProvider.currentCredentials()?.steamId != account ||
                settings.cloudReaderGeneration.first() != generation
            ) return CloudCatchUpResult.Failed(page, transitions, CloudReadFailure.ACCOUNT_MISMATCH)
            val result = try {
                readPage(CloudReadTrigger.ROUTINE, consume)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return CloudCatchUpResult.Failed(page, transitions, CloudReadFailure.UNUSABLE_RESPONSE)
            }
            when (result) {
                is CloudReadResult.Success -> {
                    transitions += result.snapshot.observationCount
                    if (!result.snapshot.hasMore) {
                        return CloudCatchUpResult.Complete(
                            page + 1, transitions, noNewData = transitions == 0,
                        )
                    }
                }
                is CloudReadResult.Failed ->
                    return CloudCatchUpResult.Failed(page, transitions, result.failure)
                else -> return CloudCatchUpResult.Unavailable
            }
        }
        return CloudCatchUpResult.Partial(MAX_ROUTINE_PAGES, transitions)
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
                val snapshot = result.parsed.toSnapshot(opening).copy(
                    readerIdentity = CloudReaderIdentity(account, start.readerGeneration),
                )
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
            // The credential clear is the removal's commit point and must run before the
            // generation fence: it atomically destroys both the active and any staged pair,
            // so a crash anywhere after it — including one that skips the fence below —
            // leaves no credentials for recovery to promote or for any read to use, and the
            // reader stays unconfigured after restart. The earlier ordering fenced first, so
            // a death between the fence and the credential clear left the active pair behind
            // with no promotion marker left to recover from, and the restart treated the
            // removed reader as still configured.
            //
            // The removal marker is recorded before the commit point so the opposite crash
            // window is covered too: a death after the credential clear but before the
            // Settings cleanup below would otherwise leave the routine policy/cooldown,
            // ordering watermarks, cursors, and read summary durable with no reader, and a
            // later reconfiguration would inherit the removed reader's policy and cooldown.
            // The marker names the fence target computed from the same persisted inputs, so
            // recovery can finish this cleanup idempotently and skip the fence when it
            // already ran. If the death lands before the commit point, the marker is an
            // abandoned attempt that recovery retires without touching the still-configured
            // reader.
            settings.markCloudReaderRemoval()
            credentialsStore.clearAllCloudCredentials()
            settings.abandonCloudReaderPromotion()
            pendingEvidence.clear()
            historicalStore.deleteUnappliedOperations()
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
            settings.clearCloudReadPosition()
            settings.clearCloudIngestPosition()
            settings.clearCloudRoutinePolicy()
            settings.clearCloudReadSummary()
            configurationState.value = null
            snapshotState.value = null
            // Every durable cleanup step above has run; only now is the removal marker
            // retired, so a death at any earlier point re-runs the idempotent cleanup via
            // recovery instead of leaving the removed reader's policy/cooldown behind.
            settings.clearCloudReaderRemoval()
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
            settings.abandonCloudReaderPromotion()
            credentialsStore.clearStagedCloudCredentials()
            pendingEvidence.clear()
            historicalStore.deleteUnappliedOperations()
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
            // The durable reset inside [block] removes the removal marker
            // (SettingsDataStore.clearAccountDerivedState), which is the only recovery signal
            // [recoverInterruptedRemovalLocked] can observe. Finish or abandon an interrupted
            // removal here first, under the same mutex, so the reset cannot destroy the marker
            // before the same-process configuration/snapshot are cleared: a removal whose
            // cleanup failed after its commit point would otherwise leave the removed reader
            // exposed in configurationState forever, with no tombstone left for recovery.
            recoverInterruptedRemovalLocked()
            accountGeneration.incrementAndGet()
            settings.abandonCloudReaderPromotion()
            credentialsStore.clearStagedCloudCredentials()
            pendingEvidence.clear()
            historicalStore.deleteUnappliedOperations()
            routineWorkCanceller?.cancelOldReaderWork(removePeriodic = true)
            snapshotState.value = null
            settings.clearCloudReadPosition()
            settings.clearCloudIngestPosition()
            cloudReadDao.deleteAll()
            try {
                block()
            } finally {
                accountGeneration.incrementAndGet()
                settings.abandonCloudReaderPromotion()
                credentialsStore.clearStagedCloudCredentials()
                pendingEvidence.clear()
                historicalStore.deleteUnappliedOperations()
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

    private data class RangeMetadataStart(
        val credentials: CloudCredentials?,
        val account: String?,
        val generation: Long,
        val readerGeneration: Long,
    )

    private data class HistoricalPageStart(
        val operation: CloudHistoricalOperation,
        val credentials: CloudCredentials,
        val accountGeneration: Long,
    )

    private sealed interface HistoricalPageCaptureResult {
        data class Ready(val start: HistoricalPageStart) : HistoricalPageCaptureResult
        data class Rejected(val result: CloudHistoricalPageResult) : HistoricalPageCaptureResult
    }

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
        mode: String? = null,
        from: String? = null,
        through: String? = null,
        requestedRange: CloudPresenceHistoricalRange? = null,
    ): RemoteReadResult {
        val response = try {
            api.read(endpoint, "Bearer " + token.trim(), position, mode, from, through)
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
        return runCatching { response.toParsedRead(expectedAccount, requestedRange) }
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
        val predecessor: CloudPresenceTransition? = null,
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
        private const val MAX_HISTORICAL_BATCH_PAGES = 50
        private const val MAX_HISTORICAL_BATCH_NANOS = 30_000_000_000L
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

private fun CloudHistoricalOperation.frozenCurrent(): CloudPresenceCurrentState? {
    val observedAt = frozenCurrentObservedAt ?: return null
    return CloudPresenceCurrentState(
        observedAt = observedAt,
        appId = frozenCurrentAppId,
        gameName = frozenCurrentGameName,
        personastate = frozenCurrentPersonastate,
        since = frozenCurrentSince,
        coverageLapseFrom = frozenCurrentCoverageLapseFrom,
        coverageLapseRecoveredAt = frozenCurrentCoverageLapseRecoveredAt,
        schemaVersion = frozenCurrentSchemaVersion,
    )
}

private fun CloudPresenceInterval.toHistoricalEntity(
    operation: CloudHistoricalOperation,
) = CloudHistoricalInterval(
    operationId = operation.operationId,
    account = operation.account,
    readerGeneration = operation.readerGeneration,
    endpointIdentity = operation.endpointIdentity,
    appId = appId,
    startAt = startAt,
    endAt = endAt,
    ongoing = ongoing,
    coverage = coverage.name,
    observedUntil = observedUntil,
    coverageLapseFrom = coverageLapseFrom,
    coverageLapseRecoveredAt = coverageLapseRecoveredAt,
    mayHaveStartedBefore = mayHaveStartedBefore,
    gameName = gameName,
    windowStart = operation.fromAt,
)

private fun CloudPresenceTransition.toHistoricalEntity(
    operation: CloudHistoricalOperation,
    kind: String,
) = CloudHistoricalBoundary(
    operationId = operation.operationId,
    account = operation.account,
    readerGeneration = operation.readerGeneration,
    endpointIdentity = operation.endpointIdentity,
    kind = kind,
    at = at,
    appId = appId,
    gameName = gameName,
    personastate = personastate,
    previousLastObservedAt = previousLastObservedAt,
    previousCoverageLapseFrom = previousCoverageLapseFrom,
    previousCoverageLapseRecoveredAt = previousCoverageLapseRecoveredAt,
    schemaVersion = schemaVersion,
)

private fun CloudHistoricalBoundary.toTransition() = CloudPresenceTransition(
    at = at,
    appId = appId,
    gameName = gameName,
    personastate = personastate,
    previousLastObservedAt = previousLastObservedAt,
    previousCoverageLapseFrom = previousCoverageLapseFrom,
    previousCoverageLapseRecoveredAt = previousCoverageLapseRecoveredAt,
    schemaVersion = schemaVersion,
)

private enum class CloudReadOutcome {
    SUCCESS,
    UNREACHABLE,
    REJECTED_CREDENTIAL,
    ACCOUNT_MISMATCH,
    UNUSABLE_RESPONSE,
}

internal fun CloudPresenceResponseDto.toRangeMetadata(
    expectedAccount: String,
): CloudPresenceRangeMetadata {
    val actualAccount = requiredAccount(account)
    if (actualAccount != expectedAccount) error("Cloud range metadata account does not match")
    if (mode != "range" || transitions.isNotEmpty() || predecessor != null || hasMore || nextPosition != null) {
        error("Cloud reader returned an invalid range metadata response")
    }
    val readAt = requiredStrictUtcInstant(readAt)
    val earliest = earliestObservedAt?.let(::requiredStrictUtcInstant)
    if (earliest != null && earliest > readAt) {
        error("Cloud reader returned range metadata beyond its read time")
    }
    val parsedCurrent = current?.toStrictDomainCurrent()
    val usableCurrent = parsedCurrent?.takeIf { state ->
        val observedAt = state.observedAt ?: return@takeIf false
        observedAt <= readAt && (state.since == null || state.since <= observedAt)
    }
    if (earliest == null && usableCurrent != null) {
        error("Cloud reader omitted available current-only range evidence")
    }
    return CloudPresenceRangeMetadata(
        account = actualAccount,
        earliestObservedAt = earliest,
        current = usableCurrent,
        readAt = readAt,
    )
}

internal fun CloudPresenceResponseDto.toParsedRead(
    expectedAccount: String,
    requestedRange: CloudPresenceHistoricalRange? = null,
): CloudPresenceRepository.ParsedCloudRead {
    val actualAccount = requiredAccount(account)
    if (actualAccount != expectedAccount) error("Cloud reader account does not match")
    if (mode != null) error("Cloud reader returned an unexpected response mode")
    val windowStart = if (requestedRange == null) requiredInstant(windowStart)
    else requiredStrictUtcInstant(windowStart)
    val windowEnd = if (requestedRange == null) requiredInstant(windowEnd)
    else requiredStrictUtcInstant(windowEnd)
    val readAt = if (requestedRange == null) requiredInstant(readAt)
    else requiredStrictUtcInstant(readAt)
    val parsedTransitions = transitions.map { transition ->
        if (requestedRange != null) transition.validateStrictUtcFields()
        transition.toDomainTransition()
    }
    val serverPosition = nextPosition?.let { raw ->
        if (requestedRange != null) {
            requiredStrictUtcInstant(raw)
            raw
        } else {
            raw.trim().takeIf { it.isNotBlank() }?.also { requiredInstant(it) }
        }
    }
    if (hasMore && serverPosition == null) error("Cloud reader returned more data without a position")
    val parsedPredecessor = predecessor?.let { transition ->
        if (requestedRange == null) error("Cloud reader returned an unexpected predecessor")
        transition.validateStrictUtcFields()
        transition.toDomainTransition()
    }
    val parsedCurrent = if (requestedRange == null) current?.toDomainCurrent()
    else current?.toStrictDomainCurrent()

    if (requestedRange != null) {
        validateHistoricalResponse(
            requestedRange = requestedRange,
            windowStart = windowStart,
            windowEnd = windowEnd,
            readAt = readAt,
            transitions = parsedTransitions,
            current = parsedCurrent,
            predecessor = parsedPredecessor,
            serverPosition = serverPosition,
            hasMore = hasMore,
        )
    }
    // Preserve one transition of overlap across pages: resuming strictly after the last
    // transition would drop the interval it opens (t250 -> t251), since page 1 reconstructs
    // only through t249 -> t250 and page 2 only from t251 onward. Persisting the second-last
    // transition causes the last to be returned again, so every adjacent pair still exists
    // in some page's reconstruction. Document keys are unique ISO timestamps, so the
    // second-last instant is strictly before the last and re-fetches exactly one transition.
    val resumePosition = if (hasMore && parsedTransitions.size >= 2) {
        val ordered = parsedTransitions.sortedBy { it.at }
        if (requestedRange != null) {
            formatCloudPresenceUtcInstant(ordered[ordered.size - 2].at)
        } else {
            Instant.ofEpochMilli(ordered[ordered.size - 2].at).toString()
        }
    } else {
        serverPosition
    }
    return CloudPresenceRepository.ParsedCloudRead(
        account = actualAccount,
        transitions = parsedTransitions,
        current = parsedCurrent,
        windowStart = windowStart,
        windowEnd = windowEnd,
        readAt = readAt,
        nextPosition = resumePosition,
        hasMore = hasMore,
        predecessor = parsedPredecessor,
    )
}

private fun validateHistoricalResponse(
    requestedRange: CloudPresenceHistoricalRange,
    windowStart: Long,
    windowEnd: Long,
    readAt: Long,
    transitions: List<CloudPresenceTransition>,
    current: CloudPresenceCurrentState?,
    predecessor: CloudPresenceTransition?,
    serverPosition: String?,
    hasMore: Boolean,
) {
    val fromAt = requestedRange.fromAt
    val throughAt = requestedRange.throughAt
    if (windowStart != fromAt || windowEnd !in fromAt..throughAt || readAt < throughAt) {
        error("Cloud reader returned a page outside the requested range")
    }
    if (transitions.size > MAX_HISTORICAL_RESPONSE_TRANSITIONS) {
        error("Cloud reader returned too many historical transitions")
    }
    if (transitions.zipWithNext().any { (before, after) -> before.at >= after.at }) {
        error("Cloud reader returned unordered historical transitions")
    }
    val lowerExclusive = requestedRange.positionAt
    if (transitions.any { transition ->
            transition.at < fromAt || transition.at > throughAt ||
                (lowerExclusive != null && transition.at <= lowerExclusive)
        }
    ) {
        error("Cloud reader returned a transition outside the requested range")
    }
    if (current != null) {
        val observedAt = current.observedAt ?: error("Cloud reader returned current state without an observation")
        if (observedAt !in fromAt..throughAt) {
            error("Cloud reader returned current state outside the requested range")
        }
    }
    if (predecessor != null && (
            requestedRange.positionAt != null || predecessor.at >= fromAt
        )
    ) {
        error("Cloud reader returned an invalid historical predecessor")
    }
    val expectedPositionAt = transitions.lastOrNull()?.at ?: requestedRange.positionAt
    val actualPositionAt = serverPosition?.let(::requiredStrictUtcInstant)
    if (actualPositionAt != expectedPositionAt) {
        error("Cloud reader returned an invalid historical position")
    }
    if (hasMore && (
            transitions.size != MAX_HISTORICAL_RESPONSE_TRANSITIONS ||
                transitions.lastOrNull()?.at != windowEnd
        )
    ) {
        error("Cloud reader returned an invalid historical continuation page")
    }
    if (!hasMore && transitions.lastOrNull()?.at?.let { windowEnd < it } == true) {
        error("Cloud reader ended its evidence before the final transition")
    }
    if (current?.observedAt?.let { windowEnd < it } == true) {
        error("Cloud reader ended its evidence before the current observation")
    }
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

private fun CloudPresenceCurrentDto.toStrictDomainCurrent(): CloudPresenceCurrentState {
    listOf(lastObservedAt, coverageLapseFrom, coverageLapseRecoveredAt, since, updatedAt)
        .forEach { raw -> if (raw != null) requiredStrictUtcInstant(raw) }
    return toDomainCurrent()
}

private fun CloudPresenceTransitionDto.validateStrictUtcFields() {
    requiredStrictUtcInstant(t)
    listOf(prevLastObservedAt, prevCoverageLapseFrom, prevCoverageLapseRecoveredAt)
        .forEach { raw -> if (raw != null) requiredStrictUtcInstant(raw) }
}

private fun requiredAccount(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() } ?: error("Cloud reader omitted its account")

private fun requiredInstant(raw: String?): Long {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: error("Cloud reader omitted a timestamp")
    return Instant.parse(value).toEpochMilli()
}

private fun optionalInstant(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return Instant.parse(raw.trim()).toEpochMilli()
}

private fun requiredStrictUtcInstant(raw: String?): Long {
    val value = raw ?: error("Cloud reader omitted a strict UTC timestamp")
    if (!FULL_UTC_INSTANT.matches(value)) error("Cloud reader returned a malformed UTC timestamp")
    return runCatching { Instant.parse(value).toEpochMilli() }
        .getOrElse { error("Cloud reader returned a malformed UTC timestamp") }
}

private fun parseAppId(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return raw.trim().toLongOrNull()?.takeIf { it >= 0L }
        ?: error("Cloud reader returned a non-numeric app id")
}

private const val MAX_HISTORICAL_RESPONSE_TRANSITIONS = 250
private val FULL_UTC_INSTANT = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z")
private val CLOUD_PRESENCE_UTC_FORMATTER = DateTimeFormatter
    .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
    .withZone(ZoneOffset.UTC)

internal fun formatCloudPresenceUtcInstant(at: Long): String =
    CLOUD_PRESENCE_UTC_FORMATTER.format(Instant.ofEpochMilli(at))

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
