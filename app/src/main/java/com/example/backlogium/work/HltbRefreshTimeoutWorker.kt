package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

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

        if (shouldCancelHltbRefresh(
                firstAttemptStillQueued = firstAttemptStillQueued,
                hasValidatedNetwork = applicationContext.hasValidatedInternet(),
            )
        ) {
            workManager.cancelUniqueWork(HltbRefreshWorker.ONE_TIME_NAME)
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hltb_refresh_timeout"
        const val TIMEOUT_SECONDS = 30L
    }
}

internal fun shouldCancelHltbRefresh(
    firstAttemptStillQueued: Boolean,
    hasValidatedNetwork: Boolean,
): Boolean = firstAttemptStillQueued && !hasValidatedNetwork
