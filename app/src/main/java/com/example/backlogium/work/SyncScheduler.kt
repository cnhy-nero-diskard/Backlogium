package com.example.backlogium.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.example.backlogium.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** User-facing state for the independent, best-effort Steam Store genre backfill. */
enum class GenreEnrichmentStatus {
    IDLE,
    QUEUED,
    RUNNING,
    RETRYING,
}

/** User-facing state for the WorkManager-backed HLTB batch refresh. */
enum class HltbRefreshStatus {
    IDLE,
    WAITING_FOR_NETWORK,
    QUEUED,
    RUNNING,
    RETRYING,
}

/** Dedicated state for the manual offline Steam artwork batch. */
enum class SteamAssetDownloadStatus { IDLE, QUEUED, PREPARING, RUNNING, FAILED, CANCELLED }

data class SteamAssetDownloadProgress(
    val processed: Int,
    val total: Int,
    val label: String,
    val stored: Int,
    val alreadyPresent: Int,
    val unavailable: Int,
    val failed: Int,
)

internal fun steamAssetStatusFor(infos: List<WorkInfo>): SteamAssetDownloadStatus = when {
    infos.any { it.state == WorkInfo.State.RUNNING && it.progress.getInt(SteamAssetDownloadWorker.KEY_TOTAL, 0) > 0 } -> SteamAssetDownloadStatus.RUNNING
    infos.any { it.state == WorkInfo.State.RUNNING } -> SteamAssetDownloadStatus.PREPARING
    infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED } -> SteamAssetDownloadStatus.QUEUED
    infos.firstOrNull()?.state == WorkInfo.State.FAILED -> SteamAssetDownloadStatus.FAILED
    infos.firstOrNull()?.state == WorkInfo.State.CANCELLED -> SteamAssetDownloadStatus.CANCELLED
    else -> SteamAssetDownloadStatus.IDLE
}

internal fun hltbRefreshStatusFor(
    hasRunning: Boolean,
    hasRetrying: Boolean,
    hasEnqueued: Boolean,
    hasValidatedNetwork: Boolean,
): HltbRefreshStatus = when {
    hasRunning -> HltbRefreshStatus.RUNNING
    hasRetrying -> HltbRefreshStatus.RETRYING
    hasEnqueued && !hasValidatedNetwork -> HltbRefreshStatus.WAITING_FOR_NETWORK
    hasEnqueued -> HltbRefreshStatus.QUEUED
    else -> HltbRefreshStatus.IDLE
}

/**
 * Owns WorkManager scheduling for [SteamSyncWorker]: a 15-minute periodic poll that
 * requires connectivity and survives restarts/reboots via WorkManager's own persistence,
 * plus a manual expedited "Sync now".
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)
    private val hltbOfflineWaitStore = HltbOfflineWaitStore(context)
    private val connectivityManager: ConnectivityManager?
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Marks a [reconcileNow] request as forced, so a later call can tell it apart from a queued unforced one. */
    private val forcedTag = "reconciliation_forced"

    private val networkConstraints: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    /**
     * Constraints for the deferred reconciliation pass: charging + unmetered network so it can
     * safely spend several minutes refreshing the cold tier without draining battery or data.
     */
    private val reconciliationConstraints: Constraints
        get() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .build()

    /**
     * Emits true while a reconciliation pass is enqueued or running — the manual/forced one-time
     * pass and the periodic deferred one alike. The periodic work is watched with the
     * RUNNING-only predicate: it normally sits ENQUEUED for days waiting on charging + unmetered
     * wifi, which must not read as "in progress" (mirrors [syncInProgress]'s reasoning).
     */
    val reconciliationInProgress: Flow<Boolean> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(ReconciliationWorker.ONE_TIME_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(ReconciliationWorker.PERIODIC_NAME),
    ) { oneTime, periodic ->
        isSyncInProgress(oneTime.map { it.state }, periodic.map { it.state })
    }.distinctUntilChanged()

    /**
     * Emits true while *any* Steam poll is in flight — the manual "Sync now" and the periodic
     * background sync alike, so the shell's indicator reflects real activity rather than only
     * button presses. WorkManager is the single source of truth here — no separate in-memory
     * flag that could desync from the actual work.
     *
     * The two works are read with different predicates ([isSyncInProgress]) and the result is
     * held briefly past its falling edge ([holdTrue]) so a sub-second sync stays perceptible.
     */
    val syncInProgress: Flow<Boolean> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SteamSyncWorker.ONE_TIME_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SteamSyncWorker.UNIQUE_PERIODIC_NAME),
    ) { oneTime, periodic ->
        isSyncInProgress(oneTime.map { it.state }, periodic.map { it.state })
    }.holdTrue()

    /**
     * WorkManager-backed status for the genre enrichment chain. The chain is deliberately exposed
     * separately from [syncInProgress]: Store requests run after Steam persistence and must not
     * make the core Steam sync indicator look stuck.
     */
    val genreEnrichmentStatus: Flow<GenreEnrichmentStatus> = workManager
        .getWorkInfosForUniqueWorkFlow(GenreEnrichmentWorker.UNIQUE_WORK_NAME)
        .map { infos ->
            when {
                infos.any { it.state == WorkInfo.State.RUNNING } -> GenreEnrichmentStatus.RUNNING
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0
                } -> GenreEnrichmentStatus.RETRYING
                infos.any {
                    it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED
                } -> GenreEnrichmentStatus.QUEUED
                else -> GenreEnrichmentStatus.IDLE
            }
        }
        .distinctUntilChanged()

    private val assetWorkInfos: Flow<List<WorkInfo>> = workManager
        .getWorkInfosForUniqueWorkFlow(SteamAssetDownloadWorker.UNIQUE_WORK_NAME)

    val steamAssetDownloadStatus: Flow<SteamAssetDownloadStatus> = assetWorkInfos
        .map(::steamAssetStatusFor)
        .distinctUntilChanged()

    val steamAssetDownloadProgress: Flow<SteamAssetDownloadProgress?> = assetWorkInfos.map { infos ->
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { progress ->
            val total = progress.getInt(SteamAssetDownloadWorker.KEY_TOTAL, 0)
            if (total <= 0) null else SteamAssetDownloadProgress(
                progress.getInt(SteamAssetDownloadWorker.KEY_PROCESSED, 0), total,
                progress.getString(SteamAssetDownloadWorker.KEY_CURRENT_LABEL).orEmpty(),
                progress.getInt(SteamAssetDownloadWorker.KEY_STORED, 0),
                progress.getInt(SteamAssetDownloadWorker.KEY_ALREADY_PRESENT, 0),
                progress.getInt(SteamAssetDownloadWorker.KEY_UNAVAILABLE, 0),
                progress.getInt(SteamAssetDownloadWorker.KEY_FAILED, 0),
            )
        }
    }

    /** Enqueue the periodic poll, keeping any already-scheduled work. Idempotent. */
    fun ensurePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<SteamSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            SteamSyncWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Enqueue a one-time expedited poll, independent of the periodic schedule. */
    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<SteamSyncWorker>()
            .setConstraints(networkConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        workManager.enqueueUniqueWork(
            SteamSyncWorker.ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueue a weekly deferred reconciliation pass. Idempotent: keeps any already-scheduled
     * work. The pass refreshes the cold tier only when charging + on unmetered wifi.
     */
    fun ensurePeriodicReconciliation() {
        val request = PeriodicWorkRequestBuilder<ReconciliationWorker>(7, TimeUnit.DAYS)
            .setConstraints(reconciliationConstraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            ReconciliationWorker.PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Starts the independent asset worker; duplicate taps keep the admitted job. */
    fun downloadSteamAssets(mode: com.example.backlogium.data.steamassets.SteamAssetDownloadMode) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<SteamAssetDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(
                SteamAssetDownloadWorker.KEY_MODE to mode.name,
                SteamAssetDownloadWorker.KEY_STARTED_AT to System.currentTimeMillis(),
            ))
            .build()
        workManager.enqueueUniqueWork(SteamAssetDownloadWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelSteamAssetDownload() = workManager.cancelUniqueWork(SteamAssetDownloadWorker.UNIQUE_WORK_NAME)

    /**
     * Enqueue a one-time reconciliation pass. [force] bypasses the charging/unmetered
     * constraints (the Settings "full achievement refresh" action); otherwise the pass waits
     * for the deferred conditions.
     *
     * Enqueued under its own [ReconciliationWorker.ONE_TIME_NAME] rather than sharing
     * [ReconciliationWorker.PERIODIC_NAME]: WorkManager's unique-work names are a single
     * namespace regardless of one-time vs periodic, so sharing a name with
     * [ensurePeriodicReconciliation]'s always-enqueued periodic work would let `KEEP` silently
     * drop this request whenever the periodic work is already sitting enqueued — which is most
     * of the time, since its charging+unmetered constraints are rarely met.
     *
     * Only marked [OneTimeWorkRequest.Builder.setExpedited] when [force] is true. WorkManager
     * rejects any expedited request whose constraints aren't network/storage-only —
     * [reconciliationConstraints]'s `requiresCharging` throws `IllegalArgumentException` at
     * `build()` if combined with it, which is what the unforced path did unconditionally until
     * this was caught by [SyncSchedulerTest]. It is also the conceptually right call: "run this
     * immediately" and "wait for charging + unmetered wifi, however long that takes" describe two
     * different requests, and only the forced one means the former.
     *
     * [force] also decides the [ExistingWorkPolicy] against this method's own prior calls, not
     * just [ensurePeriodicReconciliation]'s: a restore enqueues the unforced, constrained pass
     * (`BackupRepository.importBackup()`), and the player can tap "full refresh" (forced) while it
     * is still sitting `ENQUEUED` waiting for charging + unmetered wifi. `KEEP` for both would let
     * whichever enqueued first block the other forever, so a forced call replaces a *queued
     * unforced* request. But a forced call must not replace an *already forced* request either —
     * `REPLACE` cancels the existing work outright, so tapping "full refresh" twice while the first
     * tap is still running would cancel it and restart from the top rather than leaving it alone.
     * [forcedTag] on the request is how a later call tells the two apart: if a forced request is
     * already `ENQUEUED`/`RUNNING` under this name, this call is `KEEP` (a no-op); otherwise it's
     * `REPLACE` (superseding whatever unforced request might be queued, or enqueuing fresh).
     * Suspends only to read that state — `getWorkInfosForUniqueWorkFlow` never blocks a thread.
     */
    suspend fun reconcileNow(force: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<ReconciliationWorker>()
            .setConstraints(if (force) networkConstraints else reconciliationConstraints)
            .setInputData(workDataOf(ReconciliationWorker.KEY_FORCE to force))
        val policy = if (force) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            builder.addTag(forcedTag)
            val forcedRunAlreadyInFlight = workManager
                .getWorkInfosForUniqueWorkFlow(ReconciliationWorker.ONE_TIME_NAME)
                .first()
                .any { it.state.isInFlight() && forcedTag in it.tags }
            if (forcedRunAlreadyInFlight) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }

        workManager.enqueueUniqueWork(ReconciliationWorker.ONE_TIME_NAME, policy, builder.build())
    }

    private fun WorkInfo.State.isInFlight() = this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.RUNNING

    private val hltbWorkInfos: Flow<List<WorkInfo>> = workManager
        .getWorkInfosForUniqueWorkFlow(HltbRefreshWorker.ONE_TIME_NAME)

    /** Emits whether the default network currently has validated internet access. */
    private val hltbNetworkAvailable: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(manager.hasValidatedInternet())
            }

            override fun onLost(network: Network) {
                trySend(manager.hasValidatedInternet())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                trySend(
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            }
        }

        trySend(manager.hasValidatedInternet())
        var registered = false
        try {
            manager.registerDefaultNetworkCallback(callback)
            registered = true
        } catch (_: RuntimeException) {
            // A missing/unsupported network tracker must not prevent WorkManager observation.
            trySend(false)
        } catch (_: LinkageError) {
            // Older JVM Android shadows may not expose the callback API.
            trySend(false)
        }

        awaitClose {
            if (registered) runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }
        .distinctUntilChanged()
        .shareIn(applicationScope, SharingStarted.Eagerly, replay = 1)

    /**
     * User-facing state for the HLTB sweep. An initial `ENQUEUED` work item is waiting for the
     * connectivity constraint only when the default network lacks validated internet; otherwise
     * it is simply queued. An enqueued item with attempts already made is backing off after a
     * transient failure. Keeping these distinct prevents an offline selection from looking like a
     * worker that has started but stopped reporting progress.
     */
    private val hltbRefreshStatusSource: Flow<HltbRefreshStatus> = combine(
        hltbWorkInfos,
        hltbNetworkAvailable,
    ) { infos, hasValidatedNetwork ->
        hltbRefreshStatusFor(
            hasRunning = infos.any { it.state == WorkInfo.State.RUNNING },
            hasRetrying = infos.any {
                it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0
            },
            hasEnqueued = infos.any { it.state == WorkInfo.State.ENQUEUED },
            hasValidatedNetwork = hasValidatedNetwork,
        )
    }
        .distinctUntilChanged()

    init {
        // Keep timeout ownership at application scope so the countdown is not lost when the
        // library screen/ViewModel is destroyed.
        applicationScope.launch {
            hltbRefreshStatusSource.collect { status -> synchronizeHltbTimeout(status) }
        }
    }

    val hltbRefreshStatus: Flow<HltbRefreshStatus> = hltbRefreshStatusSource

    /** Emits true while a HowLongToBeat refresh sweep is enqueued or running. */
    val hltbRefreshInProgress: Flow<Boolean> = hltbRefreshStatus
        .map { it != HltbRefreshStatus.IDLE }
        .distinctUntilChanged()

    /**
     * The running sweep's own progress, or null when nothing is reporting — kept alongside
     * [hltbRefreshInProgress] rather than replacing it, since existing callers only need the
     * boolean.
     *
     * Null covers three real states that the UI must not render as a stalled `0 / 0`: no sweep at
     * all, a sweep enqueued but not yet past its first game, and a *finished* sweep (WorkManager
     * clears progress on completion).
     */
    val hltbRefreshProgress: Flow<HltbBatchProgress?> = workManager
        .getWorkInfosForUniqueWorkFlow(HltbRefreshWorker.ONE_TIME_NAME)
        .map { infos ->
            infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                ?.let { hltbBatchProgressFrom(it.progress) }
        }

    /**
     * Enqueue the one-shot HowLongToBeat batch refresh. [force] re-fetches every game,
     * ignoring the freshness window (the manual/testing case). Not expedited: the throttled
     * sweep can run long. Keeps any in-flight refresh rather than stacking duplicates.
     */
    fun refreshHltbNow(force: Boolean) {
        enqueueHltbRefresh(workDataOf(HltbRefreshWorker.KEY_FORCE to force))
    }

    /**
     * Enqueue a refresh over just [appIds], always forced (the worker treats a selection as
     * intent, freshness window included).
     *
     * Shares the one unique work name with the whole-library sweep under
     * [ExistingWorkPolicy.KEEP], so a selection tapped *during* a sweep is dropped with no error.
     * Callers must gate the action on [hltbRefreshInProgress] — the alternatives are worse:
     * `REPLACE` would kill a multi-minute sweep unannounced, and a second work name would mean two
     * progress streams to observe and merge.
     */
    fun refreshHltbNow(appIds: Collection<Long>) {
        if (appIds.isEmpty()) return
        enqueueHltbRefresh(workDataOf(HltbRefreshWorker.KEY_APP_IDS to appIds.toLongArray()))
    }

    /**
     * Stop a running sweep. WorkManager cancels the worker's coroutine, which unwinds at the
     * repository's inter-request delay — so at most one in-flight lookup is abandoned, and every
     * game already written keeps its data.
     *
     * There is no separate "pause": a plain (unforced) refresh started afterwards *is* the resume,
     * because everything the stopped run already fetched now sits inside the freshness window and
     * is skipped. Only "Force all" would start over from the beginning.
     */
    fun cancelHltbRefresh() {
        hltbOfflineWaitStore.clear()
        workManager.cancelUniqueWork(HltbRefreshWorker.ONE_TIME_NAME)
        workManager.cancelUniqueWork(HltbRefreshTimeoutWorker.UNIQUE_WORK_NAME)
    }

    private fun enqueueHltbRefresh(input: Data) {
        val request = OneTimeWorkRequestBuilder<HltbRefreshWorker>()
            .setConstraints(networkConstraints)
            .setInputData(input)
            .build()

        // The offline window is deliberately *not* touched here. Under `KEEP` this call may be a
        // no-op — a refresh already pending swallows the new request — and resetting the window
        // from the caller's side cannot tell the two cases apart, so a duplicate tap would hand
        // the original refresh a fresh 30 seconds. [hltbRefreshStatusSource] is the sole owner:
        // a genuinely admitted request moves the status (IDLE -> QUEUED/WAITING_FOR_NETWORK) and
        // the collector in `init` starts the window, while a dropped one changes nothing and so
        // leaves the running window's remaining time intact.
        workManager.enqueueUniqueWork(
            HltbRefreshWorker.ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private fun synchronizeHltbTimeout(status: HltbRefreshStatus) {
        when (status) {
            HltbRefreshStatus.WAITING_FOR_NETWORK -> {
                val now = System.currentTimeMillis()
                val offlineSince = hltbOfflineWaitStore.markOffline(now)
                enqueueHltbRefreshTimeout(
                    workManager,
                    hltbTimeoutDelayMillis(now, offlineSince),
                )
            }

            HltbRefreshStatus.QUEUED -> {
                hltbOfflineWaitStore.clear()
                enqueueHltbRefreshTimeout(
                    workManager,
                    HltbRefreshTimeoutWorker.WATCHDOG_POLL_MILLIS,
                )
            }

            HltbRefreshStatus.IDLE,
            HltbRefreshStatus.RUNNING,
            HltbRefreshStatus.RETRYING,
            -> {
                hltbOfflineWaitStore.clear()
                workManager.cancelUniqueWork(HltbRefreshTimeoutWorker.UNIQUE_WORK_NAME)
            }
        }
    }
}
