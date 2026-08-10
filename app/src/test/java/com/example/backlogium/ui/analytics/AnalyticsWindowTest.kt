package com.example.backlogium.ui.analytics

import com.example.backlogium.ui.history.historyWindowBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
}
