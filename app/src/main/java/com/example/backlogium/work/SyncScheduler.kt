package com.example.backlogium.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
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
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Emits true while a HowLongToBeat refresh sweep is enqueued or running. */
    val hltbRefreshInProgress: Flow<Boolean> = workManager
        .getWorkInfosForUniqueWorkFlow(HltbRefreshWorker.ONE_TIME_NAME)
        .map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        }

    /**
     * Enqueue the one-shot HowLongToBeat batch refresh. [force] re-fetches every game,
     * ignoring the freshness window (the manual/testing case). Not expedited: the throttled
     * sweep can run long. Keeps any in-flight refresh rather than stacking duplicates.
     */
    fun refreshHltbNow(force: Boolean) {
        val request = OneTimeWorkRequestBuilder<HltbRefreshWorker>()
            .setConstraints(networkConstraints)
            .setInputData(workDataOf(HltbRefreshWorker.KEY_FORCE to force))
            .build()

        workManager.enqueueUniqueWork(
            HltbRefreshWorker.ONE_TIME_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
