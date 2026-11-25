package com.example.backlogium.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AnalyticsQuestOutcomeTest {

    @Test
    fun consistencyDenominatorIncludesOnlyDatesWithStoredOutcomes() {
        val dates = (1..4).map { LocalDate.of(2026, 9, it) }

        val summary = deriveAnalyticsQuestOutcomeSummary(
            days = listOf(
                AnalyticsDay(dates[0], 0, questMet = true),
                AnalyticsDay(dates[1], 90),
                AnalyticsDay(dates[2], 90, questMet = false),
                AnalyticsDay(dates[3], 0),
            ),
        )

        assertEquals(1, summary.metDays)
        assertEquals(2, summary.availableDays)
        assertEquals(2, summary.unavailableDays)
    }
}
