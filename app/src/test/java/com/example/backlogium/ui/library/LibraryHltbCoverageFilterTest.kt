package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.HltbMatchState
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryHltbCoverageFilterTest {

    private val rows = listOf(
        row(1, HltbMatchState.NOT_COVERED),
        row(2, HltbMatchState.UNMATCHED),
        row(3, HltbMatchState.RESOLVED),
        row(4, HltbMatchState.NEEDS_REVIEW),
    )

    @Test
    fun uncoveredFilterIncludesOnlyNotCovered_notCompletedNoMatch() {
        val visible = rows.filterByHltbCoverage(notCoveredOnly = true) { it.hltbStatus }

        assertEquals(listOf(1L), visible.map { it.appId })
    }

    @Test
    fun disablingCoverageFilterRestoresEveryStatus() {
        val visible = rows.filterByHltbCoverage(notCoveredOnly = false) { it.hltbStatus }

        assertEquals(rows, visible)
    }

    @Test
    fun filteringDoesNotNarrowSelectionWhenASelectedGameBecomesHidden() {
        val selectedIds = setOf(1L, 2L)

        val visible = rows.filterByHltbCoverage(notCoveredOnly = true) { it.hltbStatus }

        assertEquals(listOf(1L), visible.map { it.appId })
        assertEquals(setOf(1L, 2L), selectedIds)
        assertEquals(2, selectedIds.size)
    }

    private fun row(appId: Long, status: HltbMatchState) = BacklogGameUi(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = 0,
        hltbStatus = status,
    )
}
