package com.example.backlogium.domain

import com.example.backlogium.domain.SessionDiffer.GameDiffState
import com.example.backlogium.domain.SessionDiffer.OpenSession
import com.example.backlogium.domain.SessionDiffer.PollGame
import com.example.backlogium.domain.SessionDiffer.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDifferTest {

    private val differ = SessionDiffer()

    @Test
    fun baseline_recordsTotals_andCreatesNoSessions() {
        val result = differ.baseline(listOf(PollGame(1L, 500), PollGame(2L, 0)))

        assertTrue(result.actions.isEmpty())
        assertTrue(result.playedDeltaByAppId.isEmpty())
        assertEquals(mapOf(1L to 500, 2L to 0), result.newLastPlaytime)
    }

    @Test
    fun singleIncrease_opensSessionWithDelta() {
        val prior = mapOf(1L to GameDiffState(lastPlaytime = 100))

        val result = differ.diff(
            polls = listOf(PollGame(1L, 130)),
            priorStates = prior,
            now = 2000L,
            previousPollAt = 1000L,
        )

        assertEquals(1, result.actions.size)
        val open = result.actions.single() as SessionAction.Open
        assertEquals(1L, open.appId)
        assertEquals(1000L, open.startAt)
        assertEquals(2000L, open.endAt)
        assertEquals(30, open.minutes)
        assertEquals(130, result.newLastPlaytime[1L])
        assertEquals(30, result.playedDeltaByAppId[1L])
    }

    @Test
    fun multiPollSession_extendsOpenSession() {
        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 130,
                openSession = OpenSession(startAt = 1000L, minutes = 30, lastIncreaseAt = 2000L),
            ),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150)),
            priorStates = prior,
            now = 3000L,
            previousPollAt = 2000L,
        )

        val extend = result.actions.single() as SessionAction.Extend
        assertEquals(50, extend.minutes) // 30 + 20
        assertEquals(3000L, extend.endAt)
        assertEquals(150, result.newLastPlaytime[1L])
        assertEquals(20, result.playedDeltaByAppId[1L])
    }

    @Test
    fun noIncreaseWithOpenSession_closesAtLastIncrease() {
        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 150,
                openSession = OpenSession(startAt = 1000L, minutes = 50, lastIncreaseAt = 3000L),
            ),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150)),
            priorStates = prior,
            now = 4000L,
            previousPollAt = 3000L,
        )

        val close = result.actions.single() as SessionAction.Close
        assertEquals(3000L, close.endAt) // end = last-increase time, not now
        assertEquals(150, result.newLastPlaytime[1L])
        assertTrue(result.playedDeltaByAppId.isEmpty())
    }

    @Test
    fun decrease_emitsNoSession_andKeepsHigherBaseline() {
        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 200,
                openSession = OpenSession(startAt = 1000L, minutes = 10, lastIncreaseAt = 5000L),
            ),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150)), // playtime went DOWN (family sharing / refund)
            priorStates = prior,
            now = 6000L,
            previousPollAt = 5000L,
        )

        // The open session is closed (no forward progress) but no negative playtime is produced.
        val close = result.actions.single() as SessionAction.Close
        assertEquals(5000L, close.endAt)
        assertEquals(200, result.newLastPlaytime[1L]) // baseline NOT lowered
        assertTrue(result.playedDeltaByAppId.isEmpty())
    }

    @Test
    fun missedShortPlay_subMinuteShowsNoDelta_producesNoSession() {
        // A play too short to bump Steam's whole-minute playtime_forever: delta is 0, so it
        // is an accepted miss — no session, no crash, baseline unchanged.
        val prior = mapOf(1L to GameDiffState(lastPlaytime = 100))

        val result = differ.diff(
            polls = listOf(PollGame(1L, 100)),
            priorStates = prior,
            now = 2000L,
            previousPollAt = 1000L,
        )

        assertTrue(result.actions.isEmpty())
        assertTrue(result.playedDeltaByAppId.isEmpty())
        assertEquals(100, result.newLastPlaytime[1L])
    }

    @Test
    fun newlyAppearingGame_isBaselined_notTurnedIntoSession() {
        val result = differ.diff(
            polls = listOf(PollGame(9L, 4242)),
            priorStates = emptyMap(),
            now = 2000L,
            previousPollAt = 1000L,
        )

        assertTrue(result.actions.isEmpty())
        assertEquals(4242, result.newLastPlaytime[9L])
        assertNull(result.playedDeltaByAppId[9L])
    }

    @Test
    fun disappearingGame_keepsStoredStateUntouched() {
        val prior = mapOf(
            1L to GameDiffState(lastPlaytime = 100),
            2L to GameDiffState(lastPlaytime = 55),
        )

        // Only game 1 is present in this poll; game 2 vanished from the response.
        val result = differ.diff(
            polls = listOf(PollGame(1L, 100)),
            priorStates = prior,
            now = 2000L,
            previousPollAt = 1000L,
        )

        assertEquals(55, result.newLastPlaytime[2L]) // untouched
    }
}
