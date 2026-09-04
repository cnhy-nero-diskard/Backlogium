package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.HltbMatchState
import org.junit.Assert.assertEquals
import org.junit.Test

/** add-shared-game-playtime-and-filter: the "Family Shared" Library filter chip. */
class LibraryFamilySharedFilterTest {

    private val rows = listOf(
        row(1, isFamilyShared = true),
        row(2, isFamilyShared = false),
        row(3, isFamilyShared = true),
        row(4, isFamilyShared = false),
    )

    @Test
    fun familySharedFilterIncludesOnlyFamilySharedGames() {
        val visible = rows.filterByFamilySharedOnly(familySharedOnly = true) { it.isFamilyShared }

        assertEquals(listOf(1L, 3L), visible.map { it.appId })
    }

    @Test
    fun disablingTheFilterRestoresEveryGame() {
        val visible = rows.filterByFamilySharedOnly(familySharedOnly = false) { it.isFamilyShared }

        assertEquals(rows, visible)
    }

    @Test
    fun combinesWithHltbCoverageAsAnd() {
        val mixed = listOf(
            row(1, isFamilyShared = true, hltbStatus = HltbMatchState.NOT_COVERED),
            row(2, isFamilyShared = true, hltbStatus = HltbMatchState.RESOLVED),
            row(3, isFamilyShared = false, hltbStatus = HltbMatchState.NOT_COVERED),
        )

        val visible = mixed
            .filterByHltbCoverage(notCoveredOnly = true) { it.hltbStatus }
            .filterByFamilySharedOnly(familySharedOnly = true) { it.isFamilyShared }

        assertEquals(listOf(1L), visible.map { it.appId })
    }

    private fun row(
        appId: Long,
        isFamilyShared: Boolean,
        hltbStatus: HltbMatchState = HltbMatchState.NOT_COVERED,
    ) = BacklogGameUi(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = 0,
        hltbStatus = hltbStatus,
        isFamilyShared = isFamilyShared,
    )
}
