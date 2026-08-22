package com.example.backlogium.domain

import javax.inject.Inject

/**
 * Derives play sessions from observed Steam presence, for games Steam reports no
 * `playtime_forever` for — family-shared titles, whose sessions cannot be diffed because there is
 * no cumulative total to diff.
 *
 * Pure by construction: no Room types, no Android, no clock. The caller supplies each observation
 * and the state of the game's currently open session, and persists the returned actions through the
 * same writer [SessionDiffer]'s actions go through, so a derived session is indistinguishable from a
 * diffed one downstream.
 *
 * **This is the second session mechanism in the app, and it must never apply to a game the first
 * one applies to.** `CLAUDE.md` states that two independent detectors produce records with
 * disagreeing boundaries that cannot be deduplicated. The partition is enforced by wiring:
 * [com.example.backlogium.data.local.dao.GameDao.ownedGamesForDiffing] feeds the differ, and the
 * caller of this deriver passes an app id only when the observed game's source is
 * [GameSource.FAMILY_SHARED]. An owned game must arrive here as [Observation.appId] `= null`.
 *
 * Boundaries are coarser than a diffed session's, by nature: they are bounded by when the app
 * happened to look, not by minute-accurate totals. A session's minutes are the span between its
 * first and last observation, so a game played entirely while unobserved records nothing at all —
 * which is why every surface presenting this playtime discloses it as observed rather than total.
 *
 * A launch shorter than the observation cadence yields a legitimate zero-minute session: the play
 * happened, and the app saw it, but saw less than a minute of it. Zero-minute sessions contribute
 * no XP and no daily minutes, so they cost nothing beyond an honest row in history.
 */
class PresenceSessionDeriver @Inject constructor() {

    /**
     * One presence observation. [appId] is the family-shared game observed, or `null` for every
     * outcome that is not "a shared game is running" — not in a game, in an owned game, or in a
     * game that has not been admitted. A failed presence fetch is **not** an observation and must
     * not be passed here: it says nothing about whether play stopped, and reporting it as `null`
     * would close a live session on a transient network error.
     */
    data class Observation(val appId: Long?, val at: Long)

    /** The open derived session, reconstructed from the stored session row by the caller. */
    data class OpenSession(
        val appId: Long,
        val startAt: Long,
        val minutes: Int,
        /** The last observation folded into this session — the session row's `endAt`. */
        val lastObservedAt: Long,
    )

    /**
     * @param actions to persist, in order, through the shared session writer.
     * @param openSession the open session after these actions, for a caller carrying state in
     *   memory rather than re-reading it. Null when nothing is open.
     */
    data class DerivationResult(
        val actions: List<SessionDiffer.SessionAction>,
        val openSession: OpenSession?,
    )

    /**
     * Fold one observation into the open session, if any.
     *
     * @param gapToleranceMillis how long a silence may last before the open session is closed at
     *   its last observation rather than extended across the gap. Defaults to
     *   [DEFAULT_GAP_TOLERANCE_MILLIS].
     */
    fun derive(
        observation: Observation,
        openSession: OpenSession?,
        gapToleranceMillis: Long = DEFAULT_GAP_TOLERANCE_MILLIS,
    ): DerivationResult {
        val actions = mutableListOf<SessionDiffer.SessionAction>()
        var open = openSession

        if (open != null) {
            val silence = observation.at - open.lastObservedAt
            val staleAfterSilence = silence > gapToleranceMillis
            // A clock that moved backwards (device time change, restored backup) makes the
            // silence meaningless; close rather than extend a session to an earlier end.
            val outOfOrder = silence < 0
            val switchedGame = observation.appId != open.appId

            if (staleAfterSilence || outOfOrder || switchedGame) {
                actions += SessionDiffer.SessionAction.Close(
                    appId = open.appId,
                    startAt = open.startAt,
                    endAt = open.lastObservedAt,
                )
                open = null
            }
        }

        val appId = observation.appId
        if (appId == null) return DerivationResult(actions, open)

        val current = open
        if (current == null) {
            actions += SessionDiffer.SessionAction.Open(
                appId = appId,
                startAt = observation.at,
                endAt = observation.at,
                minutes = 0,
            )
            return DerivationResult(
                actions,
                OpenSession(appId, observation.at, minutes = 0, lastObservedAt = observation.at),
            )
        }

        // Same game, still within tolerance: the session spans from its first observation to this
        // one. Extending unconditionally (even when the span has not yet crossed a whole minute)
        // is what keeps `endAt` current, and `endAt` is the timestamp the tolerance above is
        // measured from — an extend skipped for adding no minutes would make the session look
        // staler than it is.
        val minutes = ((observation.at - current.startAt) / MILLIS_PER_MINUTE).toInt()
            .coerceAtLeast(current.minutes)
        actions += SessionDiffer.SessionAction.Extend(
            appId = appId,
            startAt = current.startAt,
            minutes = minutes,
            endAt = observation.at,
            addedMinutes = minutes - current.minutes,
        )
        return DerivationResult(
            actions,
            current.copy(minutes = minutes, lastObservedAt = observation.at),
        )
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        /**
         * Ten minutes of silence closes a derived session.
         *
         * The reasoning, since this number decides where one session ends and the next begins:
         *
         * - Observations arrive every 30 seconds while the live monitor runs
         *   ([com.example.backlogium.data.repo.LiveStatusRepository.POLL_INTERVAL_MS]), so a
         *   tolerance in the tens of seconds would split a continuous session on a single dropped
         *   poll — and a transient network failure is not an observation that play stopped.
         * - The gaps this must bridge are longer than one poll: a foreground service killed for
         *   memory and restarted, Android deferring work, a phone off Wi-Fi for a few minutes. Ten
         *   minutes covers twenty consecutive missed polls.
         * - The cost of being too generous is worse than being too strict. Time inside a bridged
         *   gap is credited as played, because the game was running before and after it. A
         *   tolerance of an hour would silently credit a lunch break to whatever was left running;
         *   ten minutes bounds that error to something smaller than the app's own display
         *   granularity for a long session.
         * - Two genuinely separate play sessions less than ten minutes apart merge into one. That
         *   is the accepted trade: the total is right either way, and for a game whose only
         *   alternative record is *nothing*, a merged boundary is a far cheaper error than a
         *   fragmented history.
         */
        const val DEFAULT_GAP_TOLERANCE_MILLIS = 10L * 60 * 1000
    }
}
