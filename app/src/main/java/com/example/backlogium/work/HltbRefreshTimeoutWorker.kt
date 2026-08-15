package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Persistent watchdog for a refresh that cannot start because the device has no validated
 * internet. Unlike the library screen's countdown, this worker survives the ViewModel and app
 * process being destroyed.
 */
@HiltWorker
class HltbRefreshTimeoutWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val workManager = WorkManager.getInstance(applicationContext)
        val refreshInfos = workManager
            .getWorkInfosForUniqueWorkFlow(HltbRefreshWorker.ONE_TIME_NAME)
            .first()
        val firstAttemptStillQueued = refreshInfos.any {
            it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 0
        }

        val state = HltbOfflineWaitStore(applicationContext)
        if (!firstAttemptStillQueued) {
            state.clear()
            return Result.success()
        }

        val now = System.currentTimeMillis()
        if (applicationContext.hasValidatedInternet()) {
            state.clear()
            // Keep a recovery check alive while WorkManager leaves the first attempt queued.
            enqueueHltbRefreshTimeout(workManager, WATCHDOG_POLL_MILLIS)
            return Result.success()
        }

        val offlineSince = state.markOffline(now)
        val remainingMillis = hltbTimeoutDelayMillis(now, offlineSince)
        if (shouldCancelHltbRefresh(
                firstAttemptStillQueued = remainingMillis == 0L,
                hasValidatedNetwork = false,
            )
        ) {
            workManager.cancelUniqueWork(HltbRefreshWorker.ONE_TIME_NAME)
        } else {
            // A watchdog that fired before the full offline window must wait only for the
            // remaining duration, not restart or shorten the window.
            enqueueHltbRefreshTimeout(workManager, remainingMillis)
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hltb_refresh_timeout"
        const val TIMEOUT_MILLIS = 30_000L
        const val TIMEOUT_SECONDS = TIMEOUT_MILLIS / 1_000L
        const val WATCHDOG_POLL_MILLIS = TIMEOUT_MILLIS
    }
}

internal fun enqueueHltbRefreshTimeout(workManager: WorkManager, delayMillis: Long) {
    val request = OneTimeWorkRequestBuilder<HltbRefreshTimeoutWorker>()
        .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .build()
    workManager.enqueueUniqueWork(
        HltbRefreshTimeoutWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        request,
    )
}

internal fun hltbTimeoutDelayMillis(nowMillis: Long, offlineSinceMillis: Long?): Long =
    if (offlineSinceMillis == null) {
        HltbRefreshTimeoutWorker.TIMEOUT_MILLIS
    } else {
        (offlineSinceMillis + HltbRefreshTimeoutWorker.TIMEOUT_MILLIS - nowMillis)
            .coerceAtLeast(0L)
    }

internal class HltbOfflineWaitStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun markOffline(nowMillis: Long): Long {
        val existing = offlineSince()
        if (existing != null) return existing
        preferences.edit().putLong(KEY_OFFLINE_SINCE, nowMillis).commit()
        return nowMillis
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_OFFLINE_SINCE).commit()
    }

    @Synchronized
    fun offlineSince(): Long? =
        if (preferences.contains(KEY_OFFLINE_SINCE)) {
            preferences.getLong(KEY_OFFLINE_SINCE, 0L)
        } else {
            null
        }

    private companion object {
        const val PREFERENCES_NAME = "hltb_refresh_timeout"
        const val KEY_OFFLINE_SINCE = "offline_since"
    }
}

internal fun shouldCancelHltbRefresh(
    firstAttemptStillQueued: Boolean,
    hasValidatedNetwork: Boolean,
): Boolean = firstAttemptStillQueued && !hasValidatedNetwork
