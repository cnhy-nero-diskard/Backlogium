package com.example.backlogium.domain

import com.example.backlogium.domain.PresenceSessionDeriver.Observation
import com.example.backlogium.domain.PresenceSessionDeriver.OpenSession
import com.example.backlogium.domain.SessionDiffer.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The deriver as a table of observation sequences, which is the only way its boundaries can be
 * pinned down: a session's start and end are decided entirely by when the app happened to look.
 */
class PresenceSessionDeriverTest {

    private val deriver = PresenceSessionDeriver()
    private val tolerance = PresenceSessionDeriver.DEFAULT_GAP_TOLERANCE_MILLIS
    private val game = 440L
    private val other = 620L

    /** Fold a whole sequence, carrying the open session forward as the caller does. */
    private fun run(vararg observations: Observation): Pair<List<SessionAction>, OpenSession?> {
        var open: OpenSession? = null
        val actions = mutableListOf<SessionAction>()
        for (observation in observations) {
            val result = deriver.derive(observation, open)
            actions += result.actions
            open = result.openSession
        }
        return actions to open
    }

    private fun at(minutes: Long) = minutes * 60_000L

    @Test
    fun firstObservation_opensAZeroMinuteSession() {
        val (actions, open) = run(Observation(game, at(0)))

        assertEquals(1, actions.size)
        val opened = actions.single() as SessionAction.Open
        assertEquals(game, opened.appId)
        assertEquals(at(0), opened.startAt)
        assertEquals(0, opened.minutes)
        assertEquals(OpenSession(game, at(0), minutes = 0, lastObservedAt = at(0)), open)
    }

    @Test
    fun continuousPlay_extendsOneSessionToTheSpanObserved() {
        val (actions, open) = run(
            Observation(game, at(0)),
            Observation(game, at(1)),
            Observation(game, at(2)),
        )

        assertEquals(3, actions.size)
        val last = actions.last() as SessionAction.Extend
        assertEquals(at(0), last.startAt)
        assertEquals(2, last.minutes)
        assertEquals(at(2), last.endAt)
        assertEquals(1, last.addedMinutes)
        assertEquals(2, open?.minutes)
        // One session, not three: the same start is carried forward throughout.
        assertTrue(actions.count { it is SessionAction.Open } == 1)
    }

    @Test
    fun observationStops_closesTheSessionWhereItWasLastSeen() {
        val (actions, open) = run(
            Observation(game, at(0)),
            Observation(game, at(5)),
            Observation(appId = null, at = at(6)),
        )

        val closed = actions.last() as SessionAction.Close
        assertEquals(at(0), closed.startAt)
        // Ends at the last observation, never at "now": the app cannot vouch for a minute it did
        // not see, and the sixth minute is one it saw the game *not* running.
        assertEquals(at(5), closed.endAt)
        assertNull(open)
    }

    @Test
    fun shortGap_bridgesRatherThanSplitting() {
        val gap = tolerance - 60_000L
        val (actions, open) = run(
            Observation(game, 0L),
            Observation(game, gap),
        )

        // A dropped poll is not an observation that play stopped, so this stays one session.
        assertEquals(1, actions.count { it is SessionAction.Open })
        assertEquals(0, actions.count { it is SessionAction.Close })
        assertEquals(0L, open?.startAt)
    }

    @Test
    fun silenceBeyondTolerance_closesAtLastObservationAndStartsFresh() {
        val resumeAt = tolerance + 60_000L
        val (actions, open) = run(
            Observation(game, 0L),
            Observation(game, resumeAt),
        )

        val closed = actions.first { it is SessionAction.Close } as SessionAction.Close
        assertEquals(0L, closed.endAt)
        val reopened = actions.last() as SessionAction.Open
        assertEquals(resumeAt, reopened.startAt)
        // The unobserved stretch is not credited to either session.
        assertEquals(resumeAt, open?.startAt)
        assertEquals(0, open?.minutes)
    }

    @Test
    fun switchingGames_closesTheFirstAndOpensTheSecond() {
        val (actions, open) = run(
            Observation(game, at(0)),
            Observation(game, at(3)),
            Observation(other, at(4)),
        )

        val closed = actions.first { it is SessionAction.Close } as SessionAction.Close
        assertEquals(game, closed.appId)
        assertEquals(at(3), closed.endAt)
        val opened = actions.last() as SessionAction.Open
        assertEquals(other, opened.appId)
        assertEquals(at(4), opened.startAt)
        assertEquals(other, open?.appId)
    }

    @Test
    fun appRestartMidSession_resumesTheStoredSessionRatherThanStartingANewOne() {
        // A restart loses the in-memory state; the caller reconstructs the open session from the
        // stored row, which is what makes the session survive.
        val stored = OpenSession(game, startAt = at(0), minutes = 4, lastObservedAt = at(4))

        val result = deriver.derive(Observation(game, at(5)), stored)

        val extended = result.actions.single() as SessionAction.Extend
        assertEquals(at(0), extended.startAt)
        assertEquals(5, extended.minutes)
        assertEquals(1, extended.addedMinutes)
    }

    @Test
    fun notPlayingWithNothingOpen_producesNoActions() {
        val result = deriver.derive(Observation(appId = null, at = at(1)), openSession = null)

        assertTrue(result.actions.isEmpty())
        assertNull(result.openSession)
    }

    @Test
    fun clockMovingBackwards_closesRatherThanExtendingToAnEarlierEnd() {
        val stored = OpenSession(game, startAt = at(10), minutes = 5, lastObservedAt = at(15))

        val result = deriver.derive(Observation(game, at(1)), stored)

        // The silence is meaningless once the clock has moved; a session must never be extended to
        // an end before its own last observation.
        val closed = result.actions.first() as SessionAction.Close
        assertEquals(at(15), closed.endAt)
        assertEquals(at(1), result.openSession?.startAt)
    }

    @Test
    fun extendNeverReducesStoredMinutes() {
        val stored = OpenSession(game, startAt = at(0), minutes = 9, lastObservedAt = at(9))

        // A recorded row can hold more minutes than the span implies (a restored backup, a clock
        // adjustment). Extending must not walk a total backwards.
        val extended = deriver
            .derive(Observation(game, at(3)), stored)
            .actions
            .single() as SessionAction.Extend

        assertEquals(9, extended.minutes)
        assertEquals(0, extended.addedMinutes)
    }

    @Test
    fun closeStale_leavesARecentlyObservedSessionOpen() {
        val open = OpenSession(game, startAt = 0L, minutes = 1, lastObservedAt = at(1))

        val result = deriver.closeStale(open, now = at(2))

        assertTrue(result.actions.isEmpty())
        assertEquals(open, result.openSession)
    }

    @Test
    fun closeStale_closesASessionAbandonedBeyondTolerance() {
        val open = OpenSession(game, startAt = 0L, minutes = 1, lastObservedAt = at(1))

        val result = deriver.closeStale(open, now = at(1) + tolerance + 1)

        val closed = result.actions.single() as SessionAction.Close
        assertEquals(at(1), closed.endAt)
        assertNull(result.openSession)
    }
}
