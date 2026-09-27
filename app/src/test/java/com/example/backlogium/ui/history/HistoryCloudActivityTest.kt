package com.example.backlogium.ui.history

import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.data.repo.SessionCloudContribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryCloudActivityTest {
    private fun session(id: Long, recovery: ContributionState, timing: ContributionState) =
        HistorySessionUi(id, id * 1000L, 10, false, SessionCloudContribution(recovery, timing))

    private fun day(date: String, vararg sessions: HistorySessionUi): HistoryDayGroup =
        HistoryDayGroup(date, 0, 0, false,
            listOf(HistoryGameGroup(10, "Visible", "", 10, sessions.toList())),
            achievements = HistoryAchievements(emptyList(), 0))

    @Test fun visibleWindowCountsFactsIndependentlyAndHidesEntryWhenEmptyOrUnconfigured() {
        val dual = session(1, ContributionState.PARTIAL, ContributionState.FULL)
        val ordinary = session(2, ContributionState.NONE, ContributionState.UNKNOWN)
        val older = session(3, ContributionState.FULL, ContributionState.NONE)
        val current = listOf(day("2026-09-23", dual, ordinary))
        val activity = historyCloudActivity(current, true)
        assertTrue(activity.hasContributions)
        assertEquals(listOf(1L), activity.recovered.map { it.session.id })
        assertEquals(listOf(1L), activity.timed.map { it.session.id })
        assertEquals(2, historyCloudActivity(current + day("2026-08-01", older), true).recovered.size)
        assertFalse(historyCloudActivity(listOf(day("2026-09-23", ordinary)), true).hasContributions)
        assertFalse(historyCloudActivity(current, false).hasContributions)
        assertFalse(historyCloudActivity(emptyList(), true).hasContributions)
    }
}
