package com.example.backlogium.data.local.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure arithmetic behind recover-missed-playtime storage: stable derived identities and exact
 * earliest-date-first residual distribution. These are the acceptance numbers from design.md
 * Decision 5 — running them here pins the storage-level helpers the planner will call.
 */
class PlaytimeRecoveryIdentityTest {

    // ---------------------------------------------------------------- identities

    @Test
    fun contributionIdIsDeterministicAndEmbedsAccountEpochAndRange() {
        val a = PlaytimeRecoveryIdentity.contributionId("7656", 440L, 1L, 500L, 605L)
        val b = PlaytimeRecoveryIdentity.contributionId("7656", 440L, 1L, 500L, 605L)
        assertEquals("Replaying the same observation must reuse the same identity", a, b)
        assertNotEquals(
            "A different cumulative range is a different funded contribution",
            a,
            PlaytimeRecoveryIdentity.contributionId("7656", 440L, 1L, 500L, 535L),
        )
        assertNotEquals(
            "A re-established baseline epoch must never alias the previous range",
            a,
            PlaytimeRecoveryIdentity.contributionId("7656", 440L, 2L, 500L, 605L),
        )
        assertNotEquals(
            "Another account's identical range is not credit to this one",
            a,
            PlaytimeRecoveryIdentity.contributionId("9999", 440L, 1L, 500L, 605L),
        )
    }

    @Test
    fun placementIdsAreIdempotentWithinRevisionAndDistinctAcrossRevisions() {
        val base = PlaytimeRecoveryIdentity.contributionId("7656", 440L, 1L, 500L, 605L)
        val v1 = PlaytimeRecoveryIdentity.allocationPlacementId(base, 1L, "2026-08-13")
        val v1Again = PlaytimeRecoveryIdentity.allocationPlacementId(base, 1L, "2026-08-13")
        assertEquals("A retried plan collides on the same deterministic key", v1, v1Again)

        val v2 = PlaytimeRecoveryIdentity.allocationPlacementId(base, 2L, "2026-08-13")
        assertNotEquals("A late-evidence revision builds new rows, never edits old ones", v1, v2)

        val pending = PlaytimeRecoveryIdentity.allocationPlacementId(base, 1L, null)
        assertNotEquals("Pending date-unknown is keyed apart from any dated row", v1, pending)
    }

    @Test
    fun pendingMarkerNeverCollidesWithARealCalendarDate() {
        assertTrue(
            "The pending marker must be unambiguous against any ISO date",
            PlaytimeRecoveryIdentity.PENDING_DATE_MARKER.startsWith("__"),
        )
    }

    @Test
    fun journalOperationIdIsStableForTheSameEvidenceAndRevision() {
        val base = PlaytimeRecoveryIdentity.contributionId("7656", 440L, 1L, 500L, 605L)
        val first = PlaytimeRecoveryIdentity.journalOperationId(base, 2L, "interval-1")
        val replay = PlaytimeRecoveryIdentity.journalOperationId(base, 2L, "interval-1")
        assertEquals("Reconciling the same evidence again is a no-op by identity", first, replay)
        assertNotEquals(
            "Different evidence is a different journal operation",
            first,
            PlaytimeRecoveryIdentity.journalOperationId(base, 2L, "interval-2"),
        )
    }

    // ---------------------------------------------------------------- distribution

    @Test
    fun fourDayGapDistributes105As27_26_26_26() {
        val shares = UnknownTimeDistribution.distributeExactly(
            residual = 105L,
            eligibleDates = listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04"),
        )
        assertEquals(
            listOf("2026-09-01" to 27L, "2026-09-02" to 26L, "2026-09-03" to 26L, "2026-09-04" to 26L),
            shares,
        )
        assertEquals(105L, shares.sumOf { it.second })
    }

    @Test
    fun twoMinutesOverFourEligibleDatesCreditsOnlyTheEarliestTwo() {
        val shares = UnknownTimeDistribution.distributeExactly(
            residual = 2L,
            eligibleDates = listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04"),
        )
        assertEquals(
            listOf("2026-09-01" to 1L, "2026-09-02" to 1L),
            shares,
        )
    }

    @Test
    fun singleDayResidualIsThatOneDate() {
        assertEquals(
            listOf("2026-08-14" to 70L),
            UnknownTimeDistribution.distributeExactly(70L, listOf("2026-08-14")),
        )
    }

    @Test
    fun zeroRemainderGivesEveryDateAnEqualShare() {
        val shares = UnknownTimeDistribution.distributeExactly(100L, listOf("d1", "d2", "d3", "d4"))
        assertEquals(listOf("d1" to 25L, "d2" to 25L, "d3" to 25L, "d4" to 25L), shares)
    }

    @Test
    fun zeroSharesAreAbsentAndZeroResidualPutsNothingAnywhere() {
        assertTrue(
            UnknownTimeDistribution.distributeExactly(
                residual = 0L,
                eligibleDates = listOf("d1", "d2", "d3", "d4"),
            ).isEmpty(),
        )
        assertTrue(
            UnknownTimeDistribution.distributeExactly(residual = 10L, eligibleDates = emptyList()).isEmpty(),
        )
    }

    @Test
    fun quotientAndRemainderMatchesTheRecipeForm() {
        assertEquals(26L to 1L, UnknownTimeDistribution.quotientAndRemainder(105L, 4))
        assertEquals(0L to 2L, UnknownTimeDistribution.quotientAndRemainder(2L, 4))
        assertEquals(0L to 0L, UnknownTimeDistribution.quotientAndRemainder(0L, 0))
    }
}