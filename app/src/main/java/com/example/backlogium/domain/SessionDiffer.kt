package com.example.backlogium.domain

import javax.inject.Inject

/**
 * Synthesizes play sessions from successive Steam polls. The Steam Web API exposes only
 * cumulative `playtime_forever`, so sessions are derived by diffing consecutive readings.
 *
 * This class is pure (no Android / Room / clock dependencies) so it is fully unit-testable;
 * the caller supplies timestamps and persists the returned [DiffResult].
 *
 * Session-row convention used by the caller:
 * - `startAt` = the previous poll's time (best estimate of when play began)
 * - `endAt`   = the last-increase timestamp (kept current while the session is open)
 * - `open`    = true while the session is still being extended
 */
class SessionDiffer @Inject constructor() {

    data class PollGame(val appId: Long, val playtimeForever: Int)

    data class OpenSession(val startAt: Long, val minutes: Int, val lastIncreaseAt: Long)

    /** Per-game state carried between polls, reconstructed from Room by the caller. */
    data class GameDiffState(
        val lastPlaytime: Int,
        val openSession: OpenSession? = null,
    )

    sealed interface SessionAction {
        val appId: Long

        /** Create a new open session. */
        data class Open(
            override val appId: Long,
            val startAt: Long,
            val endAt: Long,
            val minutes: Int,
        ) : SessionAction

        /** Extend the game's currently-open session to the given absolute values. */
        data class Extend(
            override val appId: Long,
            val minutes: Int,
            val endAt: Long,
        ) : SessionAction

        /** Close the game's open session with the given end time. */
        data class Close(
            override val appId: Long,
            val endAt: Long,
        ) : SessionAction
    }

    data class DiffResult(
        val actions: List<SessionAction>,
        /** appId -> the `lastPlaytime` baseline to persist for the next poll. */
        val newLastPlaytime: Map<Long, Int>,
        /** appId -> minutes gained this poll (positive deltas only), for day attribution. */
        val playedDeltaByAppId: Map<Long, Int>,
    )

    /**
     * First-sync baseline: record current totals, create no sessions. Only deltas observed
     * *after* the baseline become sessions.
     */
    fun baseline(polls: List<PollGame>): DiffResult = DiffResult(
        actions = emptyList(),
        newLastPlaytime = polls.associate { it.appId to it.playtimeForever },
        playedDeltaByAppId = emptyMap(),
    )

    /**
     * Diff a poll against prior per-game state.
     *
     * @param now the current poll timestamp (epoch millis)
     * @param previousPollAt the previous poll timestamp, used as a new session's start
     */
    fun diff(
        polls: List<PollGame>,
        priorStates: Map<Long, GameDiffState>,
        now: Long,
        previousPollAt: Long,
    ): DiffResult {
        val actions = mutableListOf<SessionAction>()
        val newLastPlaytime = mutableMapOf<Long, Int>()
        val deltas = mutableMapOf<Long, Int>()

        for (poll in polls) {
            val prior = priorStates[poll.appId]
            if (prior == null) {
                // A game seen for the first time is baselined, never turned into a session.
                newLastPlaytime[poll.appId] = poll.playtimeForever
                continue
            }

            val delta = poll.playtimeForever - prior.lastPlaytime
            if (delta > 0) {
                if (prior.openSession == null) {
                    actions += SessionAction.Open(
                        appId = poll.appId,
                        startAt = previousPollAt,
                        endAt = now,
                        minutes = delta,
                    )
                } else {
                    actions += SessionAction.Extend(
                        appId = poll.appId,
                        minutes = prior.openSession.minutes + delta,
                        endAt = now,
                    )
                }
                newLastPlaytime[poll.appId] = poll.playtimeForever
                deltas[poll.appId] = delta
            } else {
                // delta == 0 (no play) or delta < 0 (family sharing / refund): no forward
                // progress. Close any open session; never emit negative playtime, and keep
                // the existing higher baseline so a decrease can't later double-count.
                if (prior.openSession != null) {
                    actions += SessionAction.Close(
                        appId = poll.appId,
                        endAt = prior.openSession.lastIncreaseAt,
                    )
                }
                newLastPlaytime[poll.appId] = prior.lastPlaytime
            }
        }

        // Games present before but absent from this poll keep their stored state untouched.
        for ((appId, state) in priorStates) {
            newLastPlaytime.putIfAbsent(appId, state.lastPlaytime)
        }

        return DiffResult(actions, newLastPlaytime, deltas)
    }
}
