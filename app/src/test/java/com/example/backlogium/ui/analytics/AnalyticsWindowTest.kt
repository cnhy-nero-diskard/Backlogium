package com.example.backlogium.ui.analytics

import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.ui.history.historyWindowBounds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `midnight advances current window and Earlier uses its effective anchor`() = runTest {
        val initialDate = LocalDate.of(2026, 8, 31)
        val nextDate = initialDate.plusDays(1)
        val clock = virtualClock(this, millisAt(initialDate, 23, 50))
        val currentSelection = AnalyticsWindowSelection(
            window = AnalyticsWindow(initialDate, AnalyticsWindowLength.ONE_MONTH),
            followsCurrent = true,
        )

        // No repository or settings flow emits here; the date boundary is the only input change.
        val snapshots = CurrentDateProvider(clock).currentDate
            .take(2)
            .map(currentSelection::forDate)
            .toList()

        assertEquals(nextDate, snapshots[1].anchor)
        assertTrue(snapshots[1].isCurrentWindow(nextDate))

        val earlier = currentSelection.stepEarlierFrom(
            effectiveWindow = snapshots[1],
            earliestTrackedDate = LocalDate.of(2026, 7, 1),
        )
        assertEquals(
            AnalyticsWindow(LocalDate.of(2026, 8, 1), AnalyticsWindowLength.ONE_MONTH),
            earlier.window,
        )
        assertFalse(earlier.followsCurrent)
    }

    @Test
    fun comparablePreviousBoundsOmitsComparisonWhenTrackingStartsInsideEitherRange() {
        val today = LocalDate.of(2026, 9, 22)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH)

        // Aug 15 start leaves the Aug 1-22 previous comparison only partially observed.
        assertNull(
            current.comparablePreviousBoundsIfFullyObserved(
                today = today,
                earliestTrackedDate = LocalDate.of(2026, 8, 15),
            ),
        )
        // A current range that predates tracking is also not comparable.
        assertNull(
            current.comparablePreviousBoundsIfFullyObserved(
                today = today,
                earliestTrackedDate = LocalDate.of(2026, 9, 15),
            ),
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
    fun activityBoundsExcludeFutureDaysInCurrentMonth() {
        val today = LocalDate.of(2026, 9, 21)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH)

        // Period identity stays the full calendar month for labels and navigation.
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)),
            current.resolve(),
        )
        // Represented activity stops at today so Sep 22-30 never appear as zero-minute days.
        val activity = current.resolveActivityBounds(today)
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2026, 9, 1), today),
            activity,
        )
        assertEquals(21, activity.dayCount)
        assertTrue(activity.dates().none { it.isAfter(today) })
    }

    @Test
    fun activityBoundsExcludeFutureDaysInCurrentYear() {
        val today = LocalDate.of(2026, 9, 21)
        val current = AnalyticsWindow(today, AnalyticsWindowLength.ONE_YEAR)

        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)),
            current.resolve(),
        )
        val activity = current.resolveActivityBounds(today)
        assertEquals(
            AnalyticsWindowBounds(LocalDate.of(2026, 1, 1), today),
            activity,
        )
        assertEquals(264, activity.dayCount)
        assertTrue(activity.dates().none { it.isAfter(today) })
    }

    @Test
    fun activityBoundsKeepFullPeriodForPastCalendarsAndRolling() {
        val today = LocalDate.of(2026, 9, 21)
        val pastMonth = AnalyticsWindow(LocalDate.of(2026, 8, 15), AnalyticsWindowLength.ONE_MONTH)
        assertEquals(pastMonth.resolve(), pastMonth.resolveActivityBounds(today))

        val pastYear = AnalyticsWindow(LocalDate.of(2025, 6, 15), AnalyticsWindowLength.ONE_YEAR)
        assertEquals(pastYear.resolve(), pastYear.resolveActivityBounds(today))

        val rolling = AnalyticsWindow(today, AnalyticsWindowLength.THIRTY_DAYS)
        assertEquals(rolling.resolve(), rolling.resolveActivityBounds(today))
    }

    @Test
    fun representedDayCountUsesElapsedDaysWithFallbackToPeriod() {
        val today = LocalDate.of(2026, 9, 21)
        val window = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH)
        val activity = window.resolveActivityBounds(today)
        val days = activity.dates().map { AnalyticsDay(it, 0) }

        val populated = AnalyticsUiState(
            loading = false,
            window = window,
            windowBounds = window.resolve(),
            dailyMinutes = days,
        )
        assertEquals(21, populated.representedDayCount)

        val empty = AnalyticsUiState(
            window = window,
            windowBounds = window.resolve(),
            dailyMinutes = emptyList(),
        )
        assertEquals(30, empty.representedDayCount)
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

    private fun millisAt(date: LocalDate, hour: Int, minute: Int): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).plusMinutes(minute.toLong())
            .toInstant().toEpochMilli()

    private fun virtualClock(scope: TestScope, start: Long) = object : TimeProvider {
        override fun nowMillis(): Long = start + scope.testScheduler.currentTime
        override fun zone(): ZoneId = zone
        override fun today(): LocalDate =
            java.time.Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate()
    }

    private companion object {
        val zone: ZoneId = ZoneId.of("Asia/Manila")
    }
}
