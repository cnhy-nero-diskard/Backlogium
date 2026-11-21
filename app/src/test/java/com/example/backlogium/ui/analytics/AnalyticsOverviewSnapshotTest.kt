package com.example.backlogium.ui.analytics

import com.example.backlogium.data.repo.LibraryGame
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class AnalyticsOverviewSnapshotTest {
    private val today = LocalDate.of(2026, 10, 3)
    private val window = AnalyticsWindow(today, AnalyticsWindowLength.ONE_MONTH)
    private val game = AnalyticsGame(9, "Same name", "file:///nine", 100, heroCapsuleUrl = "file:///nine-hero")
    private fun state() = AnalyticsUiState(loading = false, window = window, windowBounds = window.resolve(),
        headline = deriveAnalyticsHeadline(180, 2, 3, game),
        dailyMinutes = listOf(AnalyticsDay(today.minusDays(2), 0), AnalyticsDay(today.minusDays(1), 100), AnalyticsDay(today, 80)),
        topGames = listOf(game))

    @Test fun featuredIdentityArtworkAndMinutesComeFromHeadlineRatherThanAnotherSelection() {
        val other = game.copy(appId = 8, iconUrl = "file:///eight", minutes = 80)
        val snapshot = analyticsOverviewSnapshot(state().copy(topGames = listOf(other, game)))
        assertEquals(game, snapshot.featuredGame)
        assertEquals(180, snapshot.totalMinutes)
        assertEquals(2, snapshot.activeDays)
        assertEquals(90, snapshot.averageMinutes)
    }
    @Test fun comparisonAndNoDataNeverCarryAFeaturedPanel() {
        for (headline in listOf(AnalyticsHeadline.NoData, AnalyticsHeadline.Compared(180, 90))) {
            assertNull(analyticsOverviewSnapshot(state().copy(headline = headline)).featuredGame)
        }
    }
    @Test fun updatingRetainsAllOldSnapshotLabelsAndElapsedCalendarDates() {
        val snapshot = analyticsOverviewSnapshot(state().copy(updating = true, isCurrentWindow = false))
        assertEquals(window, snapshot.window)
        assertEquals(today.minusDays(2), snapshot.representedStart)
        assertEquals(today, snapshot.representedEnd)
        assertTrue(snapshot.elapsedToDate)
        val next = state().copy(window = window.stepEarlier(), windowBounds = window.stepEarlier().resolve(),
            dailyMinutes = listOf(AnalyticsDay(today.minusDays(3), 50)), headline = AnalyticsHeadline.NoData)
        assertNull(analyticsOverviewSnapshot(next).featuredGame)
        assertEquals(today.minusDays(3), analyticsOverviewSnapshot(next).representedEnd)
    }
    @Test fun equalTotalsKeepExistingNameTieOrderWithMatchingIdentity() {
        val games = mapOf(2L to LibraryGame(2, "Beta", "b", playtimeForever = 0),
            1L to LibraryGame(1, "Alpha", "a", playtimeForever = 0))
        val ranked = joinGameMinutes(linkedMapOf(2L to 100, 1L to 100), games)
        assertEquals(listOf(1L, 2L), ranked.map { it.appId })
        assertEquals(ranked.first(), (deriveAnalyticsHeadline(200, 1, 3, ranked.first()) as AnalyticsHeadline.LeadingGame).game)
    }
}
