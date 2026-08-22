package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers both halves of the recency capability: the write-time dormancy evaluation and the
 * read-time state derivation.
 *
 * The two are tested together because the split between them *is* the design — anything a poll
 * fails to record here can never be recovered by the derivation, and anything the derivation can
 * compute must not be recorded.
 */
class LibraryRecencyTest {

    private val day = 24L * 60 * 60 * 1_000
    private val now = 1_700_000_000_000L

    private fun daysAgo(count: Long) = now - count * day

    // ---------------------------------------------------------------- derivation

    @Test
    fun `newly arrived and unplayed is newly added`() {
        assertEquals(
            GameRecencyState.NEWLY_ADDED,
            LibraryRecency.derive(
                firstSeenAt = daysAgo(2),
                returnedToPlayAt = null,
                playtimeForever = 0,
                firstSessionAt = null,
                now = now,
            ),
        )
    }

    @Test
    fun `first recorded session is newly played`() {
        assertEquals(
            GameRecencyState.NEWLY_PLAYED,
            LibraryRecency.derive(
                firstSeenAt = null,
                returnedToPlayAt = null,
                playtimeForever = 600,
                firstSessionAt = daysAgo(1),
                now = now,
            ),
        )
    }

    @Test
    fun `recorded return is returned to play`() {
        assertEquals(
            GameRecencyState.RETURNED,
            LibraryRecency.derive(
                firstSeenAt = null,
                returnedToPlayAt = daysAgo(3),
                playtimeForever = 6_000,
                firstSessionAt = daysAgo(400),
                now = now,
            ),
        )
    }

    @Test
    fun `bought and played today is newly played not newly added`() {
        assertEquals(
            GameRecencyState.NEWLY_PLAYED,
            LibraryRecency.derive(
                firstSeenAt = daysAgo(0),
                returnedToPlayAt = null,
                playtimeForever = 90,
                firstSessionAt = daysAgo(0),
                now = now,
            ),
        )
    }

    @Test
    fun `long owned game played for the first time is newly played not returned`() {
        assertEquals(
            GameRecencyState.NEWLY_PLAYED,
            LibraryRecency.derive(
                firstSeenAt = null,
                returnedToPlayAt = daysAgo(1),
                playtimeForever = 120,
                firstSessionAt = daysAgo(1),
                now = now,
            ),
        )
    }

    @Test
    fun `return outranks a recent arrival`() {
        assertEquals(
            GameRecencyState.RETURNED,
            LibraryRecency.derive(
                firstSeenAt = daysAgo(1),
                returnedToPlayAt = daysAgo(1),
                playtimeForever = 500,
                firstSessionAt = daysAgo(400),
                now = now,
            ),
        )
    }

    @Test
    fun `newly added requires zero playtime`() {
        // Not outranked — genuinely left: the two "new" states are successive phases, and with
        // no session recorded (pre-install play) there is nothing else to report either.
        assertNull(
            LibraryRecency.derive(
                firstSeenAt = daysAgo(1),
                returnedToPlayAt = null,
                playtimeForever = 30,
                firstSessionAt = null,
                now = now,
            ),
        )
    }

    @Test
    fun `newly played fires once per game and never again`() {
        // A game played continuously for a year: its first session is long past, and playing again
        // does not reset it, because the input is the *earliest* session and nothing else.
        assertNull(
            LibraryRecency.derive(
                firstSeenAt = null,
                returnedToPlayAt = null,
                playtimeForever = 60_000,
                firstSessionAt = daysAgo(365),
                now = now,
            ),
        )
    }

    @Test
    fun `states expire by arithmetic with no write in between`() {
        val states = listOf(
            LibraryRecency.derive(daysAgo(6), null, 0, null, now) to
                LibraryRecency.derive(daysAgo(8), null, 0, null, now),
            LibraryRecency.derive(null, null, 100, daysAgo(6), now) to
                LibraryRecency.derive(null, null, 100, daysAgo(8), now),
            LibraryRecency.derive(null, daysAgo(6), 100, daysAgo(400), now) to
                LibraryRecency.derive(null, daysAgo(8), 100, daysAgo(400), now),
        )
        states.forEach { (atDaySix, atDayEight) ->
            assertEquals(true, atDaySix != null)
            assertNull(atDayEight)
        }
    }

    @Test
    fun `regularly played settled game carries nothing`() {
        assertNull(
            LibraryRecency.derive(
                firstSeenAt = null,
                returnedToPlayAt = null,
                playtimeForever = 12_000,
                firstSessionAt = daysAgo(90),
                now = now,
            ),
        )
    }

    @Test
    fun `a game with nothing recorded carries nothing`() {
        assertNull(LibraryRecency.derive(null, null, 0, null, now))
    }

    @Test
    fun `every combination resolves to at most one state`() {
        val instants = listOf(null, daysAgo(1), daysAgo(20))
        val playtimes = listOf(0, 240)
        // The signature returns a single nullable state, so "never two" is a type-level property.
        // What this sweeps for is a combination that throws or that contradicts the precedence
        // order — the exhaustive matrix the derivation is supposed to total over.
        for (firstSeen in instants) {
            for (returned in instants) {
                for (firstSession in instants) {
                    for (playtime in playtimes) {
                        val state = LibraryRecency.derive(
                            firstSeenAt = firstSeen,
                            returnedToPlayAt = returned,
                            playtimeForever = playtime,
                            firstSessionAt = firstSession,
                            now = now,
                        )
                        val recentSession = firstSession == daysAgo(1)
                        val recentReturn = returned == daysAgo(1)
                        val recentArrival = firstSeen == daysAgo(1)
                        val expected = when {
                            recentSession -> GameRecencyState.NEWLY_PLAYED
                            recentReturn -> GameRecencyState.RETURNED
                            playtime == 0 && recentArrival -> GameRecencyState.NEWLY_ADDED
                            else -> null
                        }
                        assertEquals(
                            "firstSeen=$firstSeen returned=$returned session=$firstSession play=$playtime",
                            expected,
                            state,
                        )
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------- dormancy evaluation

    @Test
    fun `previous play taken from the session when it is the later source`() {
        assertNull(
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = daysAgo(200),
                mostRecentSessionEndAt = daysAgo(2),
                observedPlayAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `previous play taken from the stored last played time when it is the later source`() {
        assertEquals(
            now,
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = daysAgo(40),
                mostRecentSessionEndAt = daysAgo(200),
                observedPlayAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `no return when neither source knows anything`() {
        assertNull(
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = null,
                mostRecentSessionEndAt = null,
                observedPlayAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `no return when the observer has no play time at all`() {
        assertNull(
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = daysAgo(100),
                mostRecentSessionEndAt = null,
                observedPlayAt = null,
                now = now,
            ),
        )
    }

    @Test
    fun `a delayed observation does not manufacture a return`() {
        // Previous play at day 0, Steam reports the next play at day 29, the poll runs at day 32.
        // Poll-time arithmetic would see 32 days and invent a return; event time sees 29 and does
        // not. This is the defect the whole event-time rule exists for.
        val previousPlay = daysAgo(32)
        assertNull(
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = previousPlay,
                mostRecentSessionEndAt = null,
                observedPlayAt = previousPlay + 29 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `a return is anchored to the play rather than to the poll`() {
        // The play ended the dormancy 3 days before the poll found out, so the recorded time is
        // the play's — leaving the badge 4 days of life, not 7.
        val playAt = daysAgo(3)
        val recorded = LibraryRecency.evaluateReturn(
            previousLastPlayedAt = daysAgo(60),
            mostRecentSessionEndAt = null,
            observedPlayAt = playAt,
            now = now,
        )
        assertEquals(playAt, recorded)
        assertEquals(
            GameRecencyState.RETURNED,
            LibraryRecency.derive(null, recorded, 500, daysAgo(400), now),
        )
    }

    @Test
    fun `a return discovered after its window has passed is recorded and yields no state`() {
        val playAt = daysAgo(10)
        val recorded = LibraryRecency.evaluateReturn(
            previousLastPlayedAt = daysAgo(90),
            mostRecentSessionEndAt = null,
            observedPlayAt = playAt,
            now = now,
        )
        assertEquals(playAt, recorded)
        assertNull(LibraryRecency.derive(null, recorded, 500, daysAgo(400), now))
    }

    @Test
    fun `an observed play time in the future is clamped to the present`() {
        assertEquals(
            now,
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = daysAgo(60),
                mostRecentSessionEndAt = null,
                observedPlayAt = now + 5 * day,
                now = now,
            ),
        )
    }

    @Test
    fun `play inside the dormancy threshold records no return`() {
        assertNull(
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = daysAgo(29),
                mostRecentSessionEndAt = null,
                observedPlayAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `the threshold boundary counts as dormant`() {
        assertEquals(
            now,
            LibraryRecency.evaluateReturn(
                previousLastPlayedAt = now - LibraryRecency.DORMANCY_THRESHOLD_MILLIS,
                mostRecentSessionEndAt = null,
                observedPlayAt = now,
                now = now,
            ),
        )
    }

    @Test
    fun `the evaluation derives no time of its own`() {
        // Identical stored state, two different supplied play times, two different recorded
        // returns — which is only possible if the supplied value is the sole source.
        val stored = daysAgo(90)
        val first = daysAgo(5)
        val second = daysAgo(1)
        assertEquals(first, LibraryRecency.evaluateReturn(stored, null, first, now))
        assertEquals(second, LibraryRecency.evaluateReturn(stored, null, second, now))
    }
}
