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
 *
 * [KEY_APP_IDS] narrows the sweep to an explicit selection, which is always refreshed with
 * `force = true`: choosing three games is an unambiguous statement of intent, so silently
 * skipping them for being inside the freshness window would make the action look broken.
 *
 * Progress is published per game — count, total, the game's name, and its outcome — since that is
 * the only channel that survives the screen closing. The outcome travels as a name string because
 * `Data` holds no enums; its absence means the lookup itself failed.
 */
@HiltWorker
class HltbRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val gameDao: GameDao,
    private val hltbRepository: HltbRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // An explicit selection overrides the freshness gate; a whole-library sweep honors it.
        val selection = inputData.getLongArray(KEY_APP_IDS)?.toSet()
        val force = selection != null || inputData.getBoolean(KEY_FORCE, false)
        val games = gameDao.getAll()
            .filter { selection == null || it.appId in selection }
            .map { it.appId to it.name }

        if (games.isEmpty()) {
            notifyComplete(0)
            return Result.success()
        }

        return try {
            var completed = 0
            hltbRepository.refreshBatch(games, force) { done, total, name, outcome ->
                completed = done
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to done,
                        KEY_TOTAL to total,
                        KEY_CURRENT_GAME to name,
                        // Absent = the lookup failed; a match state means it completed.
                        KEY_OUTCOME to outcome?.name,
                    ),
                )
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

        /** Optional appId subset; absent = sweep the whole library. */
        const val KEY_APP_IDS = "app_ids"
        const val KEY_PROGRESS = "progress"
        const val KEY_TOTAL = "total"
        const val KEY_CURRENT_GAME = "current_game"
        const val KEY_OUTCOME = "outcome"

        private const val CHANNEL_ID = "hltb_refresh"
        private const val NOTIFICATION_ID = 4201
    }
}
