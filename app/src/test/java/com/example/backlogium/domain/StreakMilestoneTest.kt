package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure [isStreakMilestone] rule (restyle-visual-identity, task 6.3). */
class StreakMilestoneTest {

    @Test
    fun interval_isSeven() {
        assertEquals(7, STREAK_MILESTONE_INTERVAL_DAYS)
    }

    @Test
    fun exactMultiplesOfSeven_areMilestones() {
        assertTrue(isStreakMilestone(7))
        assertTrue(isStreakMilestone(14))
        assertTrue(isStreakMilestone(21))
        assertTrue(isStreakMilestone(70))
    }

    @Test
    fun nonMultiplesOfSeven_areNotMilestones() {
        assertFalse(isStreakMilestone(1))
        assertFalse(isStreakMilestone(6))
        assertFalse(isStreakMilestone(8))
        assertFalse(isStreakMilestone(13))
        assertFalse(isStreakMilestone(15))
    }

    @Test
    fun zeroAndNegativeStreaks_areNotMilestones() {
        assertFalse(isStreakMilestone(0))
        assertFalse(isStreakMilestone(-7))
        assertFalse(isStreakMilestone(-1))
    }
}
