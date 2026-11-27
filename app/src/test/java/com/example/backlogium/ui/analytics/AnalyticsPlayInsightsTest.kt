package com.example.backlogium.ui.analytics

import com.example.backlogium.domain.DatedCreditedTotal
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AnalyticsPlayInsightsTest {
    private val date = LocalDate.of(2026, 11, 27)
    private fun total(vararg games: Pair<Long, Long>) = DatedCreditedTotal(date, games.sumOf { it.second }, games.toMap())

    @Test fun mixConservesVisibleMinutesAndBreaksTiesById() {
        val totals = listOf(total(2L to 30L, 1L to 30L, 3L to 10L))
        val shares = derivePlayMix(totals, limit = 1)
        assertEquals(listOf(1L, null), shares.map { it.appId })
        assertEquals(70L, shares.sumOf { it.minutes })
        assertEquals(100.0, shares.sumOf { it.percent }, 0.00001)
        assertEquals(AnalyticsPeriodFacts(70, 1, 3), periodFacts(totals))
        assertTrue(derivePlayMix(emptyList()).isEmpty())
    }

    @Test fun zeroBaselineHasCountsWithoutPercentageGrowth() {
        val bounds = AnalyticsWindowBounds(date, date)
        val comparison = AnalyticsComparison(bounds, bounds, AnalyticsPeriodFacts(30, 1, 1), AnalyticsPeriodFacts(0, 0, 0))
        assertNull(comparison.minutesChangePercent)
        assertEquals(100.0, comparison.copy(previous = AnalyticsPeriodFacts(15, 1, 1)).minutesChangePercent!!, 0.0)
    }

    @Test fun partialHistorySuppressesComparison() {
        val window = AnalyticsWindow(date, AnalyticsWindowLength.TWO_WEEKS)
        assertNull(window.comparablePreviousBoundsIfFullyObserved(date, date.minusDays(20)))
        assertNotNull(window.comparablePreviousBoundsIfFullyObserved(date, date.minusDays(28)))
    }
    @Test fun varietyUsesThirtyFullInterveningLocalDatesAndStableIds() {
        val history = mapOf(1L to com.example.backlogium.data.repo.RecordedActivityBounds(date.minusDays(80), date.minusDays(31)),
            2L to com.example.backlogium.data.repo.RecordedActivityBounds(date.minusDays(80), date.minusDays(30)))
        val variety = deriveVariety(listOf(total(2L to 20L, 3L to 20L, 1L to 20L)), history)
        assertEquals(3, variety.distinctGames)
        assertEquals(listOf(3L), variety.firstRecorded)
        assertEquals(listOf(1L), variety.returned)
    }

    @Test fun sessionMedianAndBucketsExcludeOpenAndNonpositiveSessions() {
        val sessions = listOf(10, 30, 59, 60, 119, 120).mapIndexed { i, minutes ->
            com.example.backlogium.data.repo.PlaySession(i.toLong(), 1, 0, minutes, false)
        }
        val insight = deriveSessionInsights(sessions + listOf(
            com.example.backlogium.data.repo.PlaySession(10, 1, 0, 999, true),
            com.example.backlogium.data.repo.PlaySession(11, 1, 0, 0, false)))
        assertEquals(59.5, insight.medianMinutes!!, 0.0)
        assertEquals(listOf(1, 2, 2, 1), insight.durationBuckets)
        assertNull(deriveSessionInsights(emptyList()).medianMinutes)
    }
    @Test fun historicalFocusUsesCurrentMembershipWithoutChangingVisibleDenominator() {
        val totals = listOf(total(1L to 20L, 2L to 60L))
        assertEquals(AnalyticsFocusShare(20, 80), deriveFocusShare(totals, setOf(1)))
        assertEquals(AnalyticsFocusShare(60, 80), deriveFocusShare(totals, setOf(2)))
        assertNull(deriveFocusShare(emptyList(), setOf(1)).percent)
        assertEquals(0.0, deriveFocusShare(totals, emptySet()).percent!!, 0.0)
    }

    @Test fun questConsistencyDoesNotInventOutcomesForActiveOrInactiveDates() {
        val summary = deriveActiveQuestConsistency(listOf(
            AnalyticsDay(date, 20, questMet = true),
            AnalyticsDay(date.minusDays(1), 20, questMet = false),
            AnalyticsDay(date.minusDays(2), 20),
            AnalyticsDay(date.minusDays(3), 0, questMet = true),
            AnalyticsDay(date.minusDays(4), 0)))
        assertEquals(AnalyticsQuestOutcomeSummary(1, 2, 1), summary)
    }
    @Test fun longTrendsConserveMinutesAndUseCalendarBoundaries() {
        val days = (0L..89L).map { AnalyticsDay(date.minusDays(89 - it), 10) }
        val weeks = analyticsTrendDays(days, AnalyticsWindowLength.NINETY_DAYS)
        val months = analyticsTrendDays(days, AnalyticsWindowLength.ONE_YEAR)
        assertEquals(900, weeks.sumOf { it.minutes })
        assertEquals(900, months.sumOf { it.minutes })
        assertTrue(weeks.size in 13..14)
        assertEquals(4, months.size)
        assertEquals(days, analyticsTrendDays(days, AnalyticsWindowLength.THIRTY_DAYS))
    }
}
