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
import com.example.backlogium.data.repo.HltbBatchResult
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
 * the only channel that survives the screen closing. The outcome is encoded as a compact string
 * because `Data` holds no sealed hierarchies.
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
            notifyComplete(HltbBatchResult(0, 0, 0, 0, emptySet()))
            return Result.success()
        }

        return try {
            val result = hltbRepository.refreshBatch(games, force) { done, total, name, outcome ->
                setProgress(
                    workDataOf(
                        KEY_PROGRESS to done,
                        KEY_TOTAL to total,
                        KEY_CURRENT_GAME to name,
                        KEY_OUTCOME to encodeHltbOutcome(outcome),
                    ),
                )
                notifyProgress(done, total, name)
            }
            if (!hltbShouldNotifyComplete(result)) {
                // A retry is not a completed refresh; avoid posting a misleading completion
                // notification while WorkManager is backing off for another attempt.
                Result.retry()
            } else {
                notifyComplete(result)
                Result.success()
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (e: Exception) {
            // Transient failure: keep cached data, let WorkManager back off and retry.
            Result.retry()
        } finally {
            // The ongoing notification is not a completion notice: it must go whichever way the
            // sweep ended, including cancellation and a retry that will post its own later.
            clearProgressNotification()
        }
    }

    /**
     * Ongoing progress in the notification bar, on its own channel and its own id.
     *
     * The sweep is paced and can run for a long time, so first-run setup starts it detached and lets
     * the user into the app while it continues — which only works if it reports where it has got to
     * somewhere the user can see without the app open. Its own id keeps it separate from the asset
     * download's, so two detached stages never overwrite each other's progress.
     *
     * A plain ongoing notification rather than a foreground service: the sweep is deferrable work
     * that WorkManager already schedules correctly, and promoting it would change how it runs in
     * order to change how it looks.
     *
     * Absent the runtime grant this skips silently, exactly as [notifyComplete] does — the Library's
     * batch panel and the setup surface both still show progress, and a declined permission is not a
     * failure of the sweep.
     */
    private fun notifyProgress(done: Int, total: Int, name: String) {
        val manager = progressNotificationManager() ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                "HowLongToBeat progress",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(applicationContext, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Fetching completion times")
            .setContentText(if (name.isBlank()) "$done / $total" else "$done / $total — $name")
            .setProgress(total, done, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun clearProgressNotification() {
        if (progressNotificationManager() == null) return
        NotificationManagerCompat.from(applicationContext).cancel(PROGRESS_NOTIFICATION_ID)
    }

    /** The system service, or null when posting is not permitted — the silent-skip gate. */
    private fun progressNotificationManager(): NotificationManager? {
        if (ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        return applicationContext.getSystemService(NotificationManager::class.java)
    }

    private fun notifyComplete(result: HltbBatchResult) {
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
                hltbCompletionText(result),
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

        /** Own channel and own id, so no other stage's progress can overwrite this one's. */
        private const val PROGRESS_CHANNEL_ID = "hltb_refresh_progress"
        private const val PROGRESS_NOTIFICATION_ID = 4202
    }
}

internal fun hltbCompletionText(result: HltbBatchResult): String {
    val parts = buildList {
        add("Refreshed ${result.refreshed} game${if (result.refreshed == 1) "" else "s"}")
        if (result.noMatch > 0) {
            add("No match for ${result.noMatch} game${if (result.noMatch == 1) "" else "s"}")
        }
        if (result.failed > 0) {
            add("Failed ${result.failed} lookup${if (result.failed == 1) "" else "s"}")
        }
    }
    return parts.joinToString("; ")
}

internal fun hltbShouldNotifyComplete(result: HltbBatchResult): Boolean = !result.shouldRetry
