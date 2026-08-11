package com.example.backlogium.work

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

/**
 * Owns WorkManager scheduling for [SteamSyncWorker]: a 15-minute periodic poll that
 * requires connectivity and survives restarts/reboots via WorkManager's own persistence,
 * plus a manual expedited "Sync now".
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

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
     */
    fun reconcileNow(force: Boolean = false) {
        val builder = OneTimeWorkRequestBuilder<ReconciliationWorker>()
            .setConstraints(if (force) networkConstraints else reconciliationConstraints)
            .setInputData(workDataOf(ReconciliationWorker.KEY_FORCE to force))
        if (force) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }

        workManager.enqueueUniqueWork(
            ReconciliationWorker.ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            builder.build(),
        )
    }

    /** Emits true while a HowLongToBeat refresh sweep is enqueued or running. */
    val hltbRefreshInProgress: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(HltbRefreshWorker.ONE_TIME_NAME)
        .map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }

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
        workManager.cancelUniqueWork(HltbRefreshWorker.ONE_TIME_NAME)
    }

    private fun enqueueHltbRefresh(input: Data) {
        val request = OneTimeWorkRequestBuilder<HltbRefreshWorker>()
            .setConstraints(networkConstraints)
            .setInputData(input)
            .build()

        workManager.enqueueUniqueWork(
            HltbRefreshWorker.ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
