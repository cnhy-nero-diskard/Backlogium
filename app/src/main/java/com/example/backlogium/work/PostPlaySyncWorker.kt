package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.repo.PlaytimeObservation
import com.example.backlogium.data.repo.RecentPlaytimeRepository
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.SyncDerivedStateWriter
import com.example.backlogium.domain.TimeProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * One attempt of the play-triggered targeted playtime fetch: ask Steam about the single game whose
 * session just ended, and commit the increase if it has appeared yet.
 *
 * Steam does not publish a game's updated `playtime_forever` when the game exits — it settles over
 * the following minutes — so an attempt that observes nothing is the expected case rather than a
 * failure. Each attempt enqueues only its successor ([PostPlaySyncScheduler]), so the schedule
 * terminates by *not* acting: the attempt that sees the increase enqueues nothing, and nothing has
 * to be cancelled.
 *
 * The worker owns no derivation. It hands its observation to
 * [PlaytimeObservationCommitter] — the same path the periodic poll commits through, whose
 * in-transaction baseline read is what makes double-counting impossible — and then triggers the
 * existing derived-value write. It never returns `Result.retry()`: an exhausted or superseded
 * schedule is concluded, and WorkManager backing off to re-run it would be re-running a decision
 * that has already been made.
 */
@HiltWorker
class PostPlaySyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recentPlaytime: RecentPlaytimeRepository,
    private val database: BacklogiumDatabase,
    private val gameDao: GameDao,
    private val committer: PlaytimeObservationCommitter,
    private val derivedStateWriter: SyncDerivedStateWriter,
    private val generations: PostPlayGenerationCoordinator,
    private val scheduler: PostPlaySyncScheduler,
    private val diagnostics: SyncRunRecorder,
    private val time: TimeProvider,
    private val syncCoordinator: SteamSyncCoordinator,
) : CoroutineWorker(appContext, params) {

    /** What one attempt's request turned out to be, before it is compared to the baseline. */
    private sealed interface Attempt {
        /** Steam answered about one or more recent games. The requested game is selected below. */
        data class Observed(val observations: List<PlaytimeObservation>) : Attempt

        /** A successful request with nothing in it: Steam unconfigured, or an empty response. */
        data object Empty : Attempt

        /** The request failed. Recorded as a failure, but the schedule carries on regardless. */
        data class Failed(val message: String) : Attempt
    }

    override suspend fun doWork(): Result {
        val appId = inputData.getLong(KEY_APP_ID, 0L)
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)
        val sessionEndAt = inputData.getLong(KEY_SESSION_END_AT, 0L)
        val generation = inputData.getLong(KEY_GENERATION, 0L)
        // Nothing to scope the work to. Only reachable if input data were lost, which WorkManager
        // does not do; recorded nowhere, because there is no game to record it against.
        if (appId <= 0L || sessionEndAt <= 0L) return Result.success()

        val scope = diagnostics.begin(trigger(appId, attempt))
        var outcome = SyncOutcome.SUCCESS
        var error: String? = null
        var examined = 0
        var updated = 0
        return try {
            // Cheap early exit before spending a request. Not the guard — the generation can be
            // superseded while the fetch below is in flight — just a way to not pay for an attempt
            // whose schedule is already known to be over.
            if (!generations.isActive(appId, generation)) {
                outcome = SyncOutcome.SKIPPED_SUPERSEDED
                return Result.success()
            }

            // The baseline session synthesis will compare against, read before the fetch: an
            // "increase" means an increase over *that* value rather than over the raw stored
            // playtime, or the two could disagree about whether there is anything to record.
            val baseline = gameDao.getById(appId)?.lastPlaytime

            val result = syncCoordinator.withLock {
                // Opportunistic: this only avoids two concurrent Steam conversations in one
                // process. Waiting is fine, failing or skipping is not — correctness lives in the
                // commit transaction, never in this mutex.
                fetch(scope)
            }

            val observation = when (result) {
                is Attempt.Failed -> {
                    outcome = SyncOutcome.FAILED
                    error = result.message
                    null
                }

                Attempt.Empty -> null

                is Attempt.Observed -> {
                    examined = result.observations.size
                    result.observations.firstOrNull { it.appId == appId } ?: run {
                        // Attributing another game's minutes to this one would be worse than the
                        // staleness this fetch exists to fix. Discard, and let the attempt count
                        // as unproductive.
                        outcome = SyncOutcome.SKIPPED_UNEXPECTED_GAME
                        error = "expected app " + appId +
                            ", Steam answered about " + result.observations.first().appId
                        null
                    }
                }
            }

            // A game with no stored baseline cannot be diffed: there is no increase to observe,
            // only a first reading, and baselining the library is the periodic poll's job.
            val increased = observation != null &&
                baseline != null &&
                observation.playtimeForever > baseline

            if (increased) {
                val recorded = commitIfStillActive(appId, generation, sessionEndAt, observation!!)
                outcome = if (recorded == null) {
                    // Refused: a newer session end took this game over while the fetch was in
                    // flight, and a superseded attempt commits nothing.
                    SyncOutcome.SKIPPED_SUPERSEDED
                } else {
                    // `false` means the transaction found the baseline already advanced — a
                    // periodic poll committed the same increase first. Exactly-once crediting is
                    // the transaction's doing, not this worker's, and the schedule is over either
                    // way: there is nothing left to observe.
                    if (recorded) updated = 1
                    SyncOutcome.SUCCESS
                }
                // Terminating the schedule is the absence of an action: no successor is enqueued,
                // so there is nothing to cancel and no race to lose.
                return Result.success()
            }

            // Nothing to record. Continue the schedule, or let it end: an exhausted schedule is an
            // ordinary outcome, with the periodic poll remaining the backstop for whenever Steam
            // publishes the increase.
            if (!PostPlaySyncScheduler.isLastAttempt(attempt)) {
                val enqueued = generations.ifActive(appId, generation) {
                    scheduler.enqueueSuccessor(
                        appId = appId,
                        attempt = attempt,
                        sessionEndAt = sessionEndAt,
                        generation = generation,
                    )
                    true
                }
                // A superseded attempt must not append into the schedule that replaced it.
                if (enqueued == null) outcome = SyncOutcome.SKIPPED_SUPERSEDED
            }
            Result.success()
        } catch (e: CancellationException) {
            outcome = SyncOutcome.INCOMPLETE
            throw e
        } catch (e: Exception) {
            // An unexpected failure (a database write, say) ends this schedule quietly rather than
            // asking WorkManager to retry it: the periodic poll will observe the same increase, and
            // a backed-off re-run of one attempt would arrive after the schedule was over anyway.
            outcome = SyncOutcome.FAILED
            error = e.message ?: "Post-play attempt failed"
            Result.success()
        } finally {
            withContext(NonCancellable) {
                runCatching { diagnostics.finish(scope, outcome, error, examined, updated) }
            }
        }
    }

    /**
     * A failed request is an attempt that observed nothing, not a reason to end the schedule: the
     * next attempt is a minute or two away and Steam may well have published by then.
     */
    private suspend fun fetch(scope: SyncRunRecorder.RunScope): Attempt = try {
        recentPlaytime.recentlyPlayed(scope).let { observations ->
            if (observations.isEmpty()) Attempt.Empty else Attempt.Observed(observations)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Attempt.Failed(error.message ?: "Post-play fetch failed")
    }

    /**
     * Commit through the ordinary poll path, but only while this schedule is still the live one.
     *
     * The generation re-check and the commit share one critical section, so a session end that
     * supersedes this attempt linearizes either before the check — and nothing is written — or
     * after the commit, in which case the commit belonged to the then-active schedule.
     *
     * @return null when the attempt has been superseded and nothing was written; otherwise
     *   whether the commit actually recorded play, which it does not when another observer
     *   committed the same increase first.
     */
    private suspend fun commitIfStillActive(
        appId: Long,
        generation: Long,
        sessionEndAt: Long,
        observed: PlaytimeObservation,
    ): Boolean? {
        val recordedPlay = generations.ifActive(appId, generation) {
            withContext(NonCancellable) {
                database.withTransaction {
                    committer.commit(
                        observed = listOf(
                            PlaytimeObservationCommitter.ObservedGame(
                                appId = observed.appId,
                                name = observed.name,
                                // This endpoint carries no icon, and blank leaves the stored one
                                // alone. Steam owns the field; the next periodic poll sets it.
                                iconUrl = "",
                                playtimeForever = observed.playtimeForever,
                                playtime2Weeks = observed.playtime2Weeks,
                            ),
                        ),
                        // The session end this schedule was triggered by, not this attempt's own
                        // clock: attempt four runs eight minutes after the play, and recording it
                        // eight minutes late would make one game's history disagree with itself
                        // depending on which attempt happened to see the increase.
                        observedPlayAt = sessionEndAt,
                        syncedAt = time.nowMillis(),
                    ).recordedPlay
                }
            }
        } ?: return null

        // Derived values follow the raw commit through their existing single author, exactly as
        // they do after a periodic poll. Outside the generation lock: the commit is durable, and
        // nothing about this write depends on the schedule still being live.
        if (recordedPlay) derivedStateWriter.persist(time.today(), derivedStateWriter.configuration())
        return recordedPlay
    }

    companion object {
        const val KEY_APP_ID = "app_id"
        const val KEY_ATTEMPT = "attempt"
        const val KEY_SESSION_END_AT = "session_end_at"
        const val KEY_GENERATION = "generation"

        /**
         * The sync-run trigger for one post-play attempt: play-triggered, scoped to a game, and
         * numbered so an exhausted schedule reads as four attempts rather than four identical rows.
         */
        fun trigger(appId: Long, attempt: Int): String = "post_play:$appId#${attempt + 1}"
    }
}
