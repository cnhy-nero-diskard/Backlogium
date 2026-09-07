package com.example.backlogium.ui.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryExpandTodayTest {

    @Test
    fun aDateAdvancingPastMidnightIsAnewAutoExpansion() {
        assertTrue(shouldAutoExpandHistoryDate("2026-09-08", "2026-09-07"))
    }

    @Test
    fun theSameDateDoesNotReopenAdayThePlayerCollapsed() {
        var expanded = setOf("2026-09-07")
        expanded = expanded - "2026-09-07"

        assertFalse(shouldAutoExpandHistoryDate("2026-09-07", "2026-09-07"))
        assertEquals(emptySet<String>(), expanded)
    }

    @Test
    fun aNewDateAddsOnlyTheNewCurrentDay() {
        val previouslyExpanded = setOf("2026-09-06")
        val today = "2026-09-07"

        val expanded = if (shouldAutoExpandHistoryDate(today, "2026-09-06")) {
            previouslyExpanded + today
        } else {
            previouslyExpanded
        }

        assertEquals(setOf("2026-09-06", "2026-09-07"), expanded)
    }

    @Test
    fun firstOpenExpandsTheCurrentDateOnly() {
        val today = "2026-09-07"
        assertTrue(shouldAutoExpandHistoryDate(today, null))
        assertFalse(shouldAutoExpandHistoryDate("", null))
    }
}