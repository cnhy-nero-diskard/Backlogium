package com.example.backlogium.data.repo

import com.example.backlogium.data.local.LiveSessionState

/**
 * Decides the next persisted [LiveSessionState] from an observed [NowPlaying], with no I/O of its
 * own — pure so the none/in-game/same-game/different-game/not-in-game transitions are directly
 * unit-testable (mirroring [com.example.backlogium.domain.SessionDiffer]'s split of pure decision
 * logic from the repository that persists it).
 */
object LiveSessionTracker {

    /**
     * @param previous the currently persisted session, or the all-null default when none is tracked
     * @param now the timestamp to record if this observation starts a new session
     */
    fun next(previous: LiveSessionState, nowPlaying: NowPlaying, now: Long): LiveSessionState =
        when (nowPlaying) {
            NowPlaying.NotPlaying -> LiveSessionState()

            is NowPlaying.InGame ->
                if (previous.startedAt != null && previous.appId == nowPlaying.gameId) {
                    // Same game still running: keep the original start time.
                    previous
                } else {
                    // First observation, or a different game than the one last recorded.
                    LiveSessionState(appId = nowPlaying.gameId, startedAt = now)
                }
        }
}
