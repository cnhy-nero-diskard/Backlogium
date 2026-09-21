package com.example.backlogium.ui.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsHeadlineTest {

    private val game = AnalyticsGame(
        appId = 1L,
        name = "Game X",
        iconUrl = "",
        minutes = 90,
    )

    @Test
    fun noDataHasPriorityAndDoesNotBorrowAllTimeFigures() {
        assertEquals(
            AnalyticsHeadline.NoData,
            deriveAnalyticsHeadline(
                totalMinutes = 0,
                activeDays = 0,
                totalDays = 30,
                leadingGame = null,
                previousMinutes = 300,
            ),
        )
    }

    @Test
    fun comparableWindowChangeHasPriorityOverLeadingGame() {
        val headline = deriveAnalyticsHeadline(
            totalMinutes = 240,
            activeDays = 4,
            totalDays = 30,
            leadingGame = game,
            previousMinutes = 180,
        )

        assertEquals(AnalyticsHeadline.Compared(240, 180), headline)
        assertEquals(60, (headline as AnalyticsHeadline.Compared).changeMinutes)
    }

    @Test
    fun unavailableComparisonFallsBackToLeadingGame() {
        assertEquals(
            AnalyticsHeadline.LeadingGame("Game X", 90),
            deriveAnalyticsHeadline(
                totalMinutes = 90,
                activeDays = 1,
                totalDays = 30,
                leadingGame = game,
                previousMinutes = null,
            ),
        )
    }

    @Test
    fun noLeadingGameFallsBackToActiveDays() {
        val headline = deriveAnalyticsHeadline(
            totalMinutes = 30,
            activeDays = 1,
            totalDays = 14,
            leadingGame = null,
        )

        assertTrue(headline is AnalyticsHeadline.ActiveDays)
        val activeDays = headline as AnalyticsHeadline.ActiveDays
        assertEquals(1, activeDays.activeDays)
        assertEquals(14, activeDays.totalDays)
    }
}
