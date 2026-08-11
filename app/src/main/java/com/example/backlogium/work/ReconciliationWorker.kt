package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Deferred, library-scale refresh of cold-tier achievement data. Runs when the device is
 * charging and on an unmetered network, so it can safely cover hundreds of games over several
 * minutes without affecting the periodic/manual sync experience.
 *
 * The pass orders games by oldest `playerStateFetchedAt` and refreshes each one. Because each
 * refresh writes a fresh timestamp back, an interrupted pass resumes where it left off rather
 * than restarting from the beginning.
 */
@HiltWorker
class ReconciliationWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val credentials: CredentialsRepository,
    private val achievementRepository: AchievementRepository,
    private val diagnostics: SyncRunRecorder,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val creds = credentials.currentCredentials()
        if (creds == null) {
            Timber.tag(TAG).i("Skipping reconciliation: no Steam credentials")
            return Result.success()
        }

        val apiKey = creds.apiKey
        val steamId = creds.steamId
        val force = inputData.getBoolean(KEY_FORCE, false)

        val scope = diagnostics.begin(if (force) "reconciliation:forced" else "reconciliation:scheduled")
        // Updated by reconcileLibraryGames's onProgress after every game, so a cancelled pass
        // still has an accurate refreshed/total count to persist in the `finally` below — the
        // ReconciliationResult return value is never reached if the coroutine is cancelled mid-sweep.
        var refreshedSoFar = 0
        var totalSoFar = 0
        var outcome = SyncOutcome.SUCCESS
        var errorMessage: String? = null

        return try {
            setProgress(workDataOf(KEY_RUNNING to true))
            val result = achievementRepository.reconcileLibraryGames(apiKey, steamId) { refreshed, total ->
                refreshedSoFar = refreshed
                totalSoFar = total
            }
            refreshedSoFar = result.refreshed
            totalSoFar = result.total
            val uncovered = result.total - result.refreshed
            if (uncovered > 0) {
                Timber.tag(TAG).w("Reconciliation stopped early: ${result.refreshed}/${result.total} games refreshed, $uncovered uncovered")
            } else {
                Timber.tag(TAG).i("Reconciliation complete: ${result.refreshed}/${result.total} games refreshed")
            }
            Result.success(workDataOf(KEY_REFRESHED to result.refreshed, KEY_TOTAL to result.total))
        } catch (e: CancellationException) {
            outcome = SyncOutcome.INCOMPLETE
            Timber.tag(TAG).i("Reconciliation cancelled early: $refreshedSoFar/$totalSoFar games refreshed")
            throw e
        } catch (e: Exception) {
            outcome = SyncOutcome.FAILED
            errorMessage = e.message ?: "Reconciliation failed"
            Timber.tag(TAG).e(e, "Reconciliation failed")
            Result.retry()
        } finally {
            // NonCancellable: once the coroutine is cancelled, an ordinary suspend call here would
            // throw immediately at its first suspension point and never actually write the record —
            // exactly the "chronically interrupted reconciliation reads as reconciled" gap this
            // diagnostics call exists to close.
            withContext(NonCancellable) {
                runCatching {
                    diagnostics.finish(scope, outcome, errorMessage, gamesExamined = totalSoFar, gamesUpdated = refreshedSoFar)
                }
            }
        }
    }

    companion object {
        const val PERIODIC_NAME = "steam_achievement_reconciliation"
        const val ONE_TIME_NAME = "steam_achievement_reconciliation_once"
        const val KEY_FORCE = "force"
        const val KEY_RUNNING = "running"
        const val KEY_REFRESHED = "refreshed"
        const val KEY_TOTAL = "total"
        private const val TAG = "Reconciliation"
    }
}
