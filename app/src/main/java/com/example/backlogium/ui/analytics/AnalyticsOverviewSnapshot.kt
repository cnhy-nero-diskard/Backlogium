package com.example.backlogium.ui.analytics

import java.time.LocalDate

/** All overview facts travel together with the displayed input window, including while updating. */
data class AnalyticsOverviewSnapshot(
    val window: AnalyticsWindow,
    val headline: AnalyticsHeadline,
    val representedStart: LocalDate,
    val representedEnd: LocalDate,
    val elapsedToDate: Boolean,
    val totalMinutes: Int,
    val activeDays: Int,
    val averageMinutes: Int,
    val familySharedMinutes: Int,
) {
    val featuredGame: AnalyticsGame? get() = (headline as? AnalyticsHeadline.LeadingGame)?.game
}

fun analyticsOverviewSnapshot(state: AnalyticsUiState): AnalyticsOverviewSnapshot {
    val start = state.dailyMinutes.firstOrNull()?.date ?: state.windowBounds.start
    val end = state.dailyMinutes.lastOrNull()?.date ?: state.windowBounds.endInclusive
    val active = state.dailyMinutes.count { it.minutes > 0 }
    val total = state.dailyMinutes.sumOf { it.minutes }
    return AnalyticsOverviewSnapshot(state.window, state.headline, start, end,
        state.window.length.kind == AnalyticsWindowKind.CALENDAR && end < state.windowBounds.endInclusive,
        total, active, if (active == 0) 0 else total / active, state.familySharedMinutes)
}
