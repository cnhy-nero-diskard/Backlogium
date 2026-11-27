package com.example.backlogium.ui.analytics

import com.example.backlogium.domain.DatedCreditedTotal
import java.time.LocalDate

/** Counts use canonical visible dated credit in both periods, including allocations. */
data class AnalyticsPeriodFacts(val minutes: Long, val activeDays: Int, val distinctGames: Int)

data class AnalyticsComparison(
    val currentBounds: AnalyticsWindowBounds,
    val previousBounds: AnalyticsWindowBounds,
    val current: AnalyticsPeriodFacts,
    val previous: AnalyticsPeriodFacts,
) {
    val minutesChangePercent: Double?
        get() = previous.minutes.takeIf { it > 0 }?.let {
            (current.minutes - it).toDouble() * 100 / it
        }
}

internal fun periodFacts(totals: List<DatedCreditedTotal>): AnalyticsPeriodFacts =
    AnalyticsPeriodFacts(
        minutes = totals.sumOf { it.minutes },
        activeDays = totals.filter { it.minutes > 0 }.map { it.date }.distinct().size,
        distinctGames = totals.flatMap { it.minutesByGame.entries }
            .filter { it.value > 0 }.map { it.key }.distinct().size,
    )

internal fun deriveAnalyticsComparison(
    window: AnalyticsWindow,
    today: LocalDate,
    earliestTrackedDate: LocalDate?,
    currentTotals: List<DatedCreditedTotal>,
    previousTotals: List<DatedCreditedTotal>,
): AnalyticsComparison? {
    val current = window.resolveActivityBounds(today, earliestTrackedDate) ?: return null
    val previous = window.comparablePreviousBoundsIfFullyObserved(today, earliestTrackedDate) ?: return null
    return AnalyticsComparison(current, previous, periodFacts(currentTotals), periodFacts(previousTotals))
}

data class AnalyticsPlayShare(val appId: Long?, val minutes: Long, val percent: Double)

/** Keep the remainder in minutes; independently rounded percentages never authorize quantity. */
internal fun derivePlayMix(totals: List<DatedCreditedTotal>, limit: Int = 5): List<AnalyticsPlayShare> {
    val minutesByGame = totals.flatMap { it.minutesByGame.entries }
        .groupBy({ it.key }, { it.value }).mapValues { (_, values) -> values.sum() }
        .filterValues { it > 0 }
    val total = minutesByGame.values.sum()
    if (total == 0L) return emptyList()
    val leaders = minutesByGame.entries.sortedWith(
        compareByDescending<Map.Entry<Long, Long>> { it.value }.thenBy { it.key },
    ).take(limit)
    val shares = leaders.map { AnalyticsPlayShare(it.key, it.value, it.value.toDouble() * 100 / total) }
    val remaining = total - leaders.sumOf { it.value }
    return shares + listOfNotNull(remaining.takeIf { it > 0 }?.let {
        AnalyticsPlayShare(null, it, it.toDouble() * 100 / total)
    })
}

data class AnalyticsVariety(
    val distinctGames: Int = 0,
    val firstRecorded: List<Long> = emptyList(),
    val returned: List<Long> = emptyList(),
)

internal fun deriveVariety(
    totals: List<DatedCreditedTotal>,
    history: Map<Long, com.example.backlogium.data.repo.RecordedActivityBounds>,
): AnalyticsVariety {
    val datesByGame = totals.flatMap { total ->
        total.minutesByGame.filterValues { it > 0 }.keys.map { it to total.date }
    }.groupBy({ it.first }, { it.second })
    val firstRecorded = datesByGame.keys.filter { it !in history }.sorted()
    val returned = datesByGame.filter { (appId, dates) ->
        val ordered = dates.distinct().sorted()
        val prior = listOfNotNull(history[appId]?.last) + ordered
        prior.zipWithNext().any { (a, b) -> java.time.temporal.ChronoUnit.DAYS.between(a, b) >= 31 }
    }.keys.sorted()
    return AnalyticsVariety(datesByGame.size, firstRecorded, returned)
}

internal fun deriveSessionInsights(sessions: List<com.example.backlogium.data.repo.PlaySession>): SessionInsights {
    val durations = sessions.filter { !it.open && it.minutes > 0 }.map { it.minutes }.sorted()
    if (durations.isEmpty()) return SessionInsights()
    val middle = durations.size / 2
    val median = if (durations.size % 2 == 1) durations[middle].toDouble()
        else (durations[middle - 1].toLong() + durations[middle]) / 2.0
    return SessionInsights(
        sessionCount = durations.size,
        averageMinutes = (durations.sumOf { it.toLong() } / durations.size).toInt(),
        longestMinutes = durations.last(),
        medianMinutes = median,
        durationBuckets = listOf(
            durations.count { it < 30 }, durations.count { it in 30..59 },
            durations.count { it in 60..119 }, durations.count { it >= 120 },
        ),
    )
}

/** Membership deliberately reflects today's tags even for historical windows. */
data class AnalyticsFocusShare(val minutes: Long = 0, val visibleMinutes: Long = 0) {
    val percent: Double? get() = visibleMinutes.takeIf { it > 0 }?.let { minutes.toDouble() * 100 / it }
}

internal fun deriveFocusShare(totals: List<DatedCreditedTotal>, currentFocusIds: Set<Long>): AnalyticsFocusShare =
    AnalyticsFocusShare(
        minutes = totals.sumOf { day -> day.minutesByGame.filterKeys { it in currentFocusIds }.values.sum() },
        visibleMinutes = totals.sumOf { it.minutes },
    )

/** Missing outcomes on evidenced active dates remain unknown rather than becoming failures. */
internal fun deriveActiveQuestConsistency(days: List<AnalyticsDay>): AnalyticsQuestOutcomeSummary =
    deriveAnalyticsQuestOutcomeSummary(days.filter { it.minutes > 0 })
