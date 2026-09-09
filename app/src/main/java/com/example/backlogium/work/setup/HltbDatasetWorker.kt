package com.example.backlogium.work.setup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.backlogium.R
import com.example.backlogium.data.repo.HltbDatasetCheckResult
import com.example.backlogium.data.repo.HltbDatasetProgress
import com.example.backlogium.data.repo.HltbDatasetRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Durable, detached setup work for the shared HowLongToBeat completion-times dataset. */
@HiltWorker
class HltbDatasetWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: HltbDatasetRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        setForeground(createForegroundInfo("Checking for completion times"))
        return try {
            when (val result = repository.checkAndApply { progress ->
                val data = progress.toWorkData()
                setProgress(data)
                setForeground(createForegroundInfo(data.getString(KEY_LABEL) ?: "Updating completion times"))
            }) {
                is HltbDatasetCheckResult.Applied,
                is HltbDatasetCheckResult.UpToDate,
                -> Result.success()
                is HltbDatasetCheckResult.Failed -> Result.failure(
                    workDataOf(KEY_FAILURE_REASON to result.message),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // A process or transport failure should remain a WorkManager retry, with the setup
            // observer free to report its own failure while the durable job keeps trying.
            Result.retry()
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Completion times",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Updating completion times")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "hltb_dataset_check"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_LABEL = "label"
        const val KEY_FAILURE_REASON = "failure_reason"
        private const val CHANNEL_ID = "completion_times"
        private const val NOTIFICATION_ID = 4211
    }
}

/** Converts repository progress into the small, persisted WorkManager data contract. */
internal fun HltbDatasetProgress.toWorkData(): Data = when (this) {
    HltbDatasetProgress.Checking -> workDataOf(
        HltbDatasetWorker.KEY_PROCESSED to 0,
        HltbDatasetWorker.KEY_TOTAL to 0,
        HltbDatasetWorker.KEY_LABEL to "Checking for a completion-times dataset",
    )
    is HltbDatasetProgress.Downloading -> workDataOf(
        HltbDatasetWorker.KEY_PROCESSED to bytesRead.toProgressInt(),
        HltbDatasetWorker.KEY_TOTAL to (totalBytes ?: 0L).toProgressInt(),
        HltbDatasetWorker.KEY_LABEL to "Downloading completion times",
    )
    HltbDatasetProgress.Verifying -> workDataOf(
        HltbDatasetWorker.KEY_PROCESSED to 0,
        HltbDatasetWorker.KEY_TOTAL to 0,
        HltbDatasetWorker.KEY_LABEL to "Verifying the dataset",
    )
    HltbDatasetProgress.Applying -> workDataOf(
        HltbDatasetWorker.KEY_PROCESSED to 0,
        HltbDatasetWorker.KEY_TOTAL to 0,
        HltbDatasetWorker.KEY_LABEL to "Applying completion times",
    )
}

private fun Long.toProgressInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()