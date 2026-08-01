package com.example.backlogium.data.repo

import com.example.backlogium.data.local.LiveSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The full none -> in game -> same game -> different game -> not in game cycle, plus the
 * unresolvable-game-id edge case, for the pure decision [LiveSessionTracker] makes on every poll.
 */
class LiveSessionTrackerTest {

    @Test
    fun none_toInGame_startsANewSessionAtNow() {
        val next = LiveSessionTracker.next(
            previous = LiveSessionState(),
            nowPlaying = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null),
            now = 1_000L,
        )
        assertEquals(LiveSessionState(appId = 10L, startedAt = 1_000L), next)
    }

    @Test
    fun sameGameObservedAgain_keepsTheOriginalStartTime() {
        val previous = LiveSessionState(appId = 10L, startedAt = 1_000L)
        val next = LiveSessionTracker.next(
            previous = previous,
            nowPlaying = NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null),
            now = 30_000L,
        )
        assertSame(previous, next)
    }

    @Test
    fun differentGameObserved_replacesTheSessionAtNow() {
        val previous = LiveSessionState(appId = 10L, startedAt = 1_000L)
        val next = LiveSessionTracker.next(
            previous = previous,
            nowPlaying = NowPlaying.InGame(gameId = 20L, name = "Hades", iconUrl = null),
            now = 60_000L,
        )
        assertEquals(LiveSessionState(appId = 20L, startedAt = 60_000L), next)
    }

    @Test
    fun notInGame_clearsTheSession() {
        val next = LiveSessionTracker.next(
            previous = LiveSessionState(appId = 20L, startedAt = 60_000L),
            nowPlaying = NowPlaying.NotPlaying,
            now = 90_000L,
        )
        assertEquals(LiveSessionState(), next)
    }

    @Test
    fun unresolvableGameId_stillStartsASession() {
        // gameId can fail to parse (null) — the session must still track *something* so the
        // elapsed timer works, even though a later different unresolvable game can't be told apart.
        val next = LiveSessionTracker.next(
            previous = LiveSessionState(),
            nowPlaying = NowPlaying.InGame(gameId = null, name = "In game", iconUrl = null),
            now = 5_000L,
        )
        assertEquals(LiveSessionState(appId = null, startedAt = 5_000L), next)
    }

    @Test
    fun unresolvableGameId_doesNotContinueAPriorUnresolvableSession() {
        // Two unidentifiable observations are not evidence of one continuous session: `null ==
        // null` would silently chain a different game onto the previous one's start time, which is
        // a claim neither observation supports. Restarting is the honest answer.
        val previous = LiveSessionState(appId = null, startedAt = 5_000L)
        val next = LiveSessionTracker.next(
            previous = previous,
            nowPlaying = NowPlaying.InGame(gameId = null, name = "In game", iconUrl = null),
            now = 35_000L,
        )
        assertEquals(LiveSessionState(appId = null, startedAt = 35_000L), next)
    }

    @Test
    fun unresolvableGameId_doesNotContinueAPriorIdentifiedSession() {
        val previous = LiveSessionState(appId = 10L, startedAt = 1_000L)
        val next = LiveSessionTracker.next(
            previous = previous,
            nowPlaying = NowPlaying.InGame(gameId = null, name = "In game", iconUrl = null),
            now = 35_000L,
        )
        assertEquals(LiveSessionState(appId = null, startedAt = 35_000L), next)
    }
}
