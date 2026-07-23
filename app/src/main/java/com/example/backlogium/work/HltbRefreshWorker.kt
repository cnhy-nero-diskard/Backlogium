package com.example.backlogium.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.backlogium.R
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.repo.HltbRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-shot batch sweep of HowLongToBeat data across the library. Mirrors the "Sync now"
 * worker: survives the screen closing, reports progress, and notifies on completion.
 *
 * Without the [KEY_FORCE] flag it refreshes only stale/missing games (the freshness gate lives
 * in [HltbRepository]); with it, every game. Endpoint/token are reused across the run and
 * requests are throttled by the repository. Last-good cached data is never discarded on error.
 */
@HiltWorker
class HltbRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val gameDao: GameDao,
    private val hltbRepository: HltbRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val games = gameDao.getAll().map { it.appId to it.name }

        if (games.isEmpty()) {
            notifyComplete(0)
            return Result.success()
        }

        return try {
            var completed = 0
            hltbRepository.refreshBatch(games, force) { done, total ->
                completed = done
                setProgress(workDataOf(KEY_PROGRESS to done, KEY_TOTAL to total))
            }
            notifyComplete(completed)
            Result.success()
        } catch (e: Exception) {
            // Transient failure: keep cached data, let WorkManager back off and retry.
            Result.retry()
        }
    }

    private fun notifyComplete(refreshedCount: Int) {
        val context = applicationContext
        // On API 33+ posting requires the runtime POST_NOTIFICATIONS grant; skip silently
        // if it was never granted (the Library screen also reflects completion via WorkManager).
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "HowLongToBeat refresh",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("HowLongToBeat refresh complete")
            .setContentText(
                if (refreshedCount == 0) {
                    "Library already up to date"
                } else {
                    "Refreshed $refreshedCount game${if (refreshedCount == 1) "" else "s"}"
                },
            )
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val ONE_TIME_NAME = "hltb_refresh_now"
        const val KEY_FORCE = "force"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"

        private const val CHANNEL_ID = "hltb_refresh"
        private const val NOTIFICATION_ID = 4201
    }
}
