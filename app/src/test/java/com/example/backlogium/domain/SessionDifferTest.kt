package com.example.backlogium.domain

import com.example.backlogium.domain.SessionDiffer.ClockRollback
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
        assertEquals(30, open.addedMinutes)
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
        assertEquals(1000L, extend.startAt)
        assertEquals(50, extend.minutes) // 30 + 20
        assertEquals(3000L, extend.endAt)
        assertEquals(20, extend.addedMinutes)
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
        assertEquals(1000L, close.startAt)
        assertEquals(3000L, close.endAt) // end = last-increase time, not now
        assertEquals(0, close.addedMinutes)
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
        assertEquals(1000L, close.startAt)
        assertEquals(5000L, close.endAt)
        assertEquals(0, close.addedMinutes)
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
    fun bulkOpenSessions_produceSameDiffAsPerGameReads() {
        // Simulates the N+1 collapse: getAllOpenSessions() returns all open rows, which the
        // caller associates by appId into priorStates. The resulting diff must match what
        // per-game getOpenSession(appId) calls would have produced.
        val openSessions = listOf(
            OpenSession(startAt = 1000L, minutes = 30, lastIncreaseAt = 2000L),
            OpenSession(startAt = 1000L, minutes = 10, lastIncreaseAt = 2000L),
        )
        val prior = mapOf(
            1L to GameDiffState(lastPlaytime = 130, openSession = openSessions[0]),
            2L to GameDiffState(lastPlaytime = 110, openSession = openSessions[1]),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150), PollGame(2L, 115)),
            priorStates = prior,
            now = 3000L,
            previousPollAt = 2000L,
        )

        assertEquals(2, result.actions.size)
        val extend1 = result.actions.first { it.appId == 1L } as SessionAction.Extend
        assertEquals(1000L, extend1.startAt)
        assertEquals(50, extend1.minutes)
        assertEquals(20, extend1.addedMinutes)
        val extend2 = result.actions.first { it.appId == 2L } as SessionAction.Extend
        assertEquals(1000L, extend2.startAt)
        assertEquals(15, extend2.minutes)
        assertEquals(5, extend2.addedMinutes)
        assertEquals(mapOf(1L to 20, 2L to 5), result.playedDeltaByAppId)
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

    // --- auditfix-session-ledger-integrity #115: a synthesized interval is never inverted ---

    @Test
    fun clockRollbackWhileExtending_clampsEndAtRatherThanInverting() {
        // SessionDifferTest previously only exercised increasing timestamps; `now` here is
        // earlier than the open session's own `startAt`.
        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 130,
                openSession = OpenSession(startAt = 1000L, minutes = 30, lastIncreaseAt = 2000L),
            ),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150)),
            priorStates = prior,
            now = 500L,
            previousPollAt = 2000L,
        )

        val extend = result.actions.single() as SessionAction.Extend
        assertEquals(1000L, extend.startAt)
        assertEquals(1000L, extend.endAt) // clamped to startAt, never inverted
        assertTrue(extend.endAt >= extend.startAt)
        // Tracked minutes are unaffected by the clamp — they come from Steam, not the clock.
        assertEquals(50, extend.minutes)
        assertEquals(20, extend.addedMinutes)
        assertEquals(20, result.playedDeltaByAppId[1L])
        assertEquals(
            listOf(ClockRollback(appId = 1L, attemptedEndAt = 500L, clampedEndAt = 1000L)),
            result.clockRollbacks,
        )
    }

    @Test
    fun clockRollbackWhileOpening_clampsEndAtRatherThanInverting() {
        // The audit named Extend; Open in the same loop derives both ends from clock readings
        // and inverts under the same rollback (design.md Decision 2).
        val prior = mapOf(1L to GameDiffState(lastPlaytime = 100))

        val result = differ.diff(
            polls = listOf(PollGame(1L, 130)),
            priorStates = prior,
            now = 500L,
            previousPollAt = 2000L, // clock moved backwards since the previous poll
        )

        val open = result.actions.single() as SessionAction.Open
        assertEquals(2000L, open.startAt)
        assertEquals(2000L, open.endAt) // clamped, never inverted
        assertTrue(open.endAt >= open.startAt)
        assertEquals(30, open.minutes)
        assertEquals(30, result.playedDeltaByAppId[1L])
        assertEquals(
            listOf(ClockRollback(appId = 1L, attemptedEndAt = 500L, clampedEndAt = 2000L)),
            result.clockRollbacks,
        )
    }

    @Test
    fun closeAfterClampedBoundary_staysNonInverted() {
        // A boundary clamped on one poll must not resurface as an inverted interval when a
        // later no-delta poll closes the session — the audit's specific observation that a bad
        // boundary survives the close.
        val opened = differ.diff(
            polls = listOf(PollGame(1L, 130)),
            priorStates = mapOf(1L to GameDiffState(lastPlaytime = 100)),
            now = 500L,
            previousPollAt = 2000L,
        ).actions.single() as SessionAction.Open

        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 130,
                openSession = OpenSession(
                    startAt = opened.startAt,
                    minutes = opened.minutes,
                    lastIncreaseAt = opened.endAt,
                ),
            ),
        )
        val result = differ.diff(
            polls = listOf(PollGame(1L, 130)), // no further increase
            priorStates = prior,
            now = 2600L,
            previousPollAt = 2500L,
        )

        val close = result.actions.single() as SessionAction.Close
        assertEquals(opened.startAt, close.startAt)
        assertEquals(opened.endAt, close.endAt)
        assertTrue(close.endAt >= close.startAt)
    }

    @Test
    fun forwardClockJump_extendsNormally_withNoRollbackRecorded() {
        // The guard must not make ordinary operation conservative: a forward jump is not a
        // rollback and produces none.
        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 130,
                openSession = OpenSession(startAt = 1000L, minutes = 30, lastIncreaseAt = 2000L),
            ),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150)),
            priorStates = prior,
            now = 3_600_000L, // an hour forward, still a valid ordinary jump
            previousPollAt = 2000L,
        )

        val extend = result.actions.single() as SessionAction.Extend
        assertEquals(3_600_000L, extend.endAt)
        assertTrue(result.clockRollbacks.isEmpty())
    }

    @Test
    fun rollbackGuardStaysConsistentWithPresenceSessionDeriversHandling() {
        // Cross-check against
        // PresenceSessionDeriverTest.clockMovingBackwards_closesRatherThanExtendingToAnEarlierEnd:
        // that path closes rather than clamps, a different mechanism for a different shape of
        // input (silence-tolerant observations vs. a fixed session start). Both must guarantee
        // the same outcome — no stored interval with endAt < startAt — which is what this
        // asserts here, not that the two mechanisms are identical (design.md Non-Goals).
        val prior = mapOf(
            1L to GameDiffState(
                lastPlaytime = 130,
                openSession = OpenSession(startAt = 1000L, minutes = 30, lastIncreaseAt = 2000L),
            ),
        )

        val result = differ.diff(
            polls = listOf(PollGame(1L, 150)),
            priorStates = prior,
            now = 1L,
            previousPollAt = 2000L,
        )

        result.actions.forEach { action ->
            val endAt = when (action) {
                is SessionAction.Open -> action.endAt
                is SessionAction.Extend -> action.endAt
                is SessionAction.Close -> action.endAt
            }
            assertTrue("no stored interval may have endAt < startAt", endAt >= action.startAt)
        }
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
