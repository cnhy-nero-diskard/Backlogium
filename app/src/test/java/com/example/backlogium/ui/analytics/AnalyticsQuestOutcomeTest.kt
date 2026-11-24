package com.example.backlogium.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AnalyticsQuestOutcomeTest {

    @Test
    fun consistencyDenominatorIncludesOnlyDatesWithStoredOutcomes() {
        val dates = (1..4).map { LocalDate.of(2026, 9, it) }

        val summary = deriveAnalyticsQuestOutcomeSummary(
            representedDates = dates,
            outcomesByDate = mapOf(
                dates[0] to true,
                dates[2] to false,
            ),
        )

        assertEquals(1, summary.metDays)
        assertEquals(2, summary.availableDays)
        assertEquals(2, summary.unavailableDays)
    }
}
