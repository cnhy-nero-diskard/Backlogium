package com.example.backlogium.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.backlogium.R
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.data.steamassets.SteamAssetRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Long-running, user-triggered Steam CDN prefetch. It never invokes normal Steam data sync. */
@HiltWorker
class SteamAssetDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: SteamAssetRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val mode = runCatching {
            SteamAssetDownloadMode.valueOf(inputData.getString(KEY_MODE) ?: SteamAssetDownloadMode.DOWNLOAD_MISSING.name)
        }.getOrDefault(SteamAssetDownloadMode.DOWNLOAD_MISSING)
        val startedAt = inputData.getLong(KEY_STARTED_AT, System.currentTimeMillis())
        setForeground(createForegroundInfo("Preparing Steam assets"))
        return try {
            repository.run(mode, startedAt) { processed, total, label, counts ->
                setProgress(workDataOf(
                    KEY_PROCESSED to processed,
                    KEY_TOTAL to total,
                    KEY_CURRENT_LABEL to label,
                    KEY_STORED to counts.stored,
                    KEY_ALREADY_PRESENT to counts.alreadyPresent,
                    KEY_UNAVAILABLE to counts.unavailable,
                    KEY_FAILED to counts.failed,
                ))
                setForeground(createForegroundInfo("$processed / $total Steam assets"))
            }
            Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Offline Steam assets", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading Steam assets")
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
        const val UNIQUE_WORK_NAME = "steam_asset_download"
        const val KEY_MODE = "mode"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_PROCESSED = "processed"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_LABEL = "current_label"
        const val KEY_STORED = "stored"
        const val KEY_ALREADY_PRESENT = "already_present"
        const val KEY_UNAVAILABLE = "unavailable"
        const val KEY_FAILED = "failed"
        private const val CHANNEL_ID = "offline_steam_assets"
        private const val NOTIFICATION_ID = 4210
    }
}
