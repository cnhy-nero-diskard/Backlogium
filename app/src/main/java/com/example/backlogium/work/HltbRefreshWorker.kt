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
        }
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
