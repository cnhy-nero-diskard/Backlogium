package com.example.backlogium.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalPaceRecoveryEligibilityTest {
    private val today = LocalDate.of(2026, 6, 15)

    @Test
    fun `unresolved dates are neither active nor synthesized zeros`() {
        val unknown = (2L..5L).mapTo(mutableSetOf()) { today.minusDays(it) }
        val totals = listOf(
            DatedPlayTotal(today.minusDays(14), 60),
            DatedPlayTotal(today.minusDays(3), 120),
        )
        val profile = PersonalPace.derive(totals, today, unknown)
        assertEquals(10, profile.coveredDates)
        assertEquals(1, profile.activeDates)
        assertTrue(profile.dailyTotals.none { it.date in unknown })
        assertFalse(profile.isReliable)
    }

    @Test
    fun `an excluded earlier estimate cannot extend covered span`() {
        val estimateDate = today.minusDays(30)
        val profile = PersonalPace.derive(
            listOf(DatedPlayTotal(estimateDate, 1), DatedPlayTotal(today.minusDays(2), 60)),
            today,
            setOf(estimateDate),
        )
        assertEquals(2, profile.coveredDates)
        assertEquals(1, profile.activeDates)
    }

    @Test
    fun `complete dated evidence restores eligibility without changing supplied totals`() {
        val totals = (1L..6L).map { DatedPlayTotal(today.minusDays(it), 60) } +
            DatedPlayTotal(today.minusDays(14), 60)
        val unknown = setOf(today.minusDays(3))
        val before = PersonalPace.derive(totals, today, unknown)
        val after = PersonalPace.derive(totals, today, emptySet())
        assertEquals(13, before.coveredDates)
        assertFalse(before.isReliable)
        assertEquals(14, after.coveredDates)
        assertTrue(after.isReliable)
        assertEquals(7, after.activeDates)
        assertEquals(420, totals.sumOf { it.minutes })
    }

    @Test
    fun `completed single date totals count while current and future remain excluded`() {
        val profile = PersonalPace.derive(
            listOf(
                DatedPlayTotal(today.minusDays(1), 70),
                DatedPlayTotal(today, 70),
                DatedPlayTotal(today.plusDays(1), 70),
            ),
            today,
        )
        assertEquals(listOf(DatedPlayTotal(today.minusDays(1), 70)), profile.dailyTotals)
    }
}
