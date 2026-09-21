package com.example.backlogium.ui.analytics

import com.example.backlogium.ui.history.historyWindowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

class AnalyticsWindowTest {

    private val anchor = LocalDate.of(2024, 8, 10)

    @Test
    fun resolverUsesInclusiveBoundsForEveryLength() {
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 7, 28), anchor),
            AnalyticsWindow(anchor, AnalyticsWindowLength.TWO_WEEKS).resolve(),
        )
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 7, 12), anchor),
            AnalyticsWindow(anchor, AnalyticsWindowLength.THIRTY_DAYS).resolve(),
        )
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 8, 1), LocalDate.of(2024, 8, 31)),
            AnalyticsWindow(anchor, AnalyticsWindowLength.ONE_MONTH).resolve(),
        )
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 5, 13), anchor),
            AnalyticsWindow(anchor, AnalyticsWindowLength.NINETY_DAYS).resolve(),
        )
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
            AnalyticsWindow(anchor, AnalyticsWindowLength.ONE_YEAR).resolve(),
        )
    }

    @Test
    fun calendarSteppingCoversWholeFebruaryAndLeapYearInBothDirections() {
        val march = AnalyticsWindow(LocalDate.of(2024, 3, 15), AnalyticsWindowLength.ONE_MONTH)
        val february = march.stepEarlier()

        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)),
            february.resolve(),
        )
        assertEquals(march.resolve(), february.stepLater().resolve())

        val march2023 = AnalyticsWindow(LocalDate.of(2023, 3, 31), AnalyticsWindowLength.ONE_MONTH)
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2023, 2, 1), LocalDate.of(2023, 2, 28)),
            march2023.stepEarlier().resolve(),
        )
    }

    @Test
    fun rollingSteppingIsContiguousWithoutOverlap() {
        val current = AnalyticsWindow(anchor, AnalyticsWindowLength.NINETY_DAYS).resolve()
        val previous = AnalyticsWindow(anchor, AnalyticsWindowLength.NINETY_DAYS)
            .stepEarlier()
            .resolve()

        assertEquals(current.start.minusDays(1), previous.endInclusive)
        assertEquals(current.start, previous.endInclusive.plusDays(1))
        assertEquals(current.dayCount, previous.dayCount)
    }

    @Test
    fun anchorCanReachThePeriodContainingEarliestSessionButNotEarlier() {
        val earliest = LocalDate.of(2024, 7, 1)
        val current = AnalyticsWindow(anchor, AnalyticsWindowLength.THIRTY_DAYS)

        assertTrue(current.canStepEarlier(earliest))
        assertFalse(current.stepEarlier().canStepEarlier(earliest))
    }

    @Test
    fun earlierWindowCanStepLaterUntilItReachesTheCurrentWindow() {
        val today = LocalDate.of(2024, 8, 10)
        val earlier = AnalyticsWindow(today, AnalyticsWindowLength.THIRTY_DAYS).stepEarlier()

        assertTrue(earlier.canStepLater(today))
        assertTrue(earlier.stepLater().isCurrentWindow(today))
        assertFalse(AnalyticsWindow(today, AnalyticsWindowLength.THIRTY_DAYS).canStepLater(today))
    }

    @Test
    fun calendarNavigationUsesWholePeriodsAndStopsAtCurrentMonth() {
        val today = LocalDate.of(2024, 8, 10)
        val earlier = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH).stepEarlier()

        assertTrue(earlier.canStepLater(today))
        assertTrue(earlier.stepLater().isCurrentWindow(today))
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 31)),
            earlier.resolve(),
        )
    }

    @Test
    fun comparablePreviousBoundsUsesSameElapsedSubrangeForCurrentMonth() {
        val today = LocalDate.of(2026, 9, 21)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH)

        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 21)),
            current.comparablePreviousBounds(today),
        )
    }

    @Test
    fun comparablePreviousBoundsUsesSameElapsedSubrangeForCurrentYear() {
        val today = LocalDate.of(2026, 9, 21)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_YEAR)

        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 9, 21)),
            current.comparablePreviousBounds(today),
        )
    }

    @Test
    fun comparablePreviousBoundsFallsBackToFullPeriodsOtherwise() {
        val today = LocalDate.of(2024, 8, 10)
        // Rolling lengths compare equal-length windows even when current.
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 6, 12), LocalDate.of(2024, 7, 11)),
            AnalyticsWindow(today, AnalyticsWindowLength.THIRTY_DAYS)
                .comparablePreviousBounds(today),
        )
        // A past calendar month compares full periods.
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30)),
            AnalyticsWindow(LocalDate.of(2024, 7, 15), AnalyticsWindowLength.ONE_MONTH)
                .comparablePreviousBounds(today),
        )
    }

    @Test
    fun comparablePreviousBoundsOmitsComparisonWhenElapsedExceedsPriorMonth() {
        // March 30 elapsed is 30 days but February only holds 28 in 2023: no equal-duration
        // subrange exists, so the previous-period headline is omitted rather than comparing
        // 30 days against 28.
        val today = LocalDate.of(2023, 3, 30)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH)

        assertNull(current.comparablePreviousBounds(today))
    }

    @Test
    fun comparablePreviousBoundsOmitsComparisonForLeapYearEnd() {
        // Dec 31 in a leap year elapsed 366 days but the prior year holds 365.
        val today = LocalDate.of(2024, 12, 31)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_YEAR)

        assertNull(current.comparablePreviousBounds(today))
    }

    @Test
    fun historyBoundsUseLocalMidnightAndExclusiveNextMidnight() {
        val zone = ZoneId.of("America/New_York")
        val bounds = historyWindowBounds(
            start = LocalDate.of(2024, 3, 9),
            endInclusive = LocalDate.of(2024, 3, 10),
            zone = zone,
        )

        assertEquals(
            Instant.parse("2024-03-09T05:00:00Z").toEpochMilli(),
            bounds.startInclusiveMillis,
        )
        assertEquals(
            Instant.parse("2024-03-11T04:00:00Z").toEpochMilli(),
            bounds.endExclusiveMillis,
        )
    }

    @Test
    fun nonUsLocaleChangesPeriodFormattingWithoutChangingWindowBounds() {
        val window = AnalyticsWindow(anchor, AnalyticsWindowLength.THIRTY_DAYS)
        val bounds = window.resolve()

        val us = formatAnalyticsWindowPeriod(window, bounds, Locale.US)
        val german = formatAnalyticsWindowPeriod(window, bounds, Locale.GERMANY)

        assertTrue(us != german)
        assertTrue(german.contains("2024"))
        assertEquals(bounds, window.resolve())
    }
}
