package com.example.backlogium.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AnalyticsDaySelectionTest {

    private val days = listOf(
        AnalyticsDay(LocalDate.of(2026, 9, 1), 0),
        AnalyticsDay(LocalDate.of(2026, 9, 2), 25),
        AnalyticsDay(LocalDate.of(2026, 9, 3), 0),
    )

    @Test
    fun zeroDayWindow_hasNoSelection() {
        assertNull(initialAnalyticsDaySelection(emptyList()))
        assertNull(stepAnalyticsDaySelection(0, dayCount = 0, delta = 1))
    }

    @Test
    fun firstDay_isBoundedWhenSteppingEarlier() {
        assertEquals(0, analyticsDaySelectionIndex(days.size, -4))
        assertEquals(0, stepAnalyticsDaySelection(0, days.size, delta = -1))
    }

    @Test
    fun middleDay_movesThroughTheSharedSelectionPath() {
        assertEquals(1, analyticsDaySelectionIndex(days.size, 1))
        assertEquals(2, stepAnalyticsDaySelection(1, days.size, delta = 1))
        assertEquals(0, stepAnalyticsDaySelection(1, days.size, delta = -1))
    }

    @Test
    fun lastDay_isBoundedWhenSteppingLater() {
        assertEquals(2, analyticsDaySelectionIndex(days.size, 99))
        assertEquals(2, stepAnalyticsDaySelection(2, days.size, delta = 1))
    }
}
