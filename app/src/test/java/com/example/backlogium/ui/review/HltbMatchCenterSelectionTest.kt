package com.example.backlogium.ui.review

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.local.entity.HltbMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for match-center selection stability (task 5.5): the selection tracks a
 * game's appId rather than its index, because the queue reorders across the ambiguous/unmatched
 * partitions whenever a match status changes, and clamping must be persisted so a selection that
 * fell out of the queue cannot become active again when the queue later grows.
 */
class HltbMatchCenterSelectionTest {

    private fun game(appId: Long, status: HltbMatchStatus) = MatchCenterGameUi(
        appId = appId,
        name = "Game $appId",
        matchStatus = status,
        candidates = listOf(HltbCandidate(hltbId = appId * 10, name = "Candidate $appId")),
    )

    private fun queue(vararg games: MatchCenterGameUi) = games.toList()

    @Test
    fun unmatchedToNeedsReview_reorder_keepsSelectionOnTheSameGame() {
        // Broader search success moves the selected game from the unmatched partition into the
        // review partition: [A, B, C] becomes [B, A, C]. An index would now point at A.
        val before = queue(
            game(1, HltbMatchStatus.UNMATCHED),
            game(2, HltbMatchStatus.UNMATCHED),
            game(3, HltbMatchStatus.UNMATCHED),
        )
        val selection = resolveMatchCenterSelection(selectedAppId = 2L, games = before)
        assertEquals(1, selection.index)

        val after = queue(
            game(2, HltbMatchStatus.NEEDS_REVIEW),
            game(1, HltbMatchStatus.UNMATCHED),
            game(3, HltbMatchStatus.UNMATCHED),
        )
        val reordered = resolveMatchCenterSelection(selectedAppId = selection.persistedAppId, games = after)

        assertEquals(0, reordered.index)
        assertEquals(2L, reordered.persistedAppId)
        // The derived index still selects the same game through the state's own derivation.
        assertEquals(2L, after[reordered.index].appId)
    }

    @Test
    fun selectedGameRemoved_clampsToFirstAndPersistsTheReplacement() {
        val queue3 = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.NEEDS_REVIEW),
            game(3, HltbMatchStatus.NEEDS_REVIEW),
        )
        assertEquals(2, resolveMatchCenterSelection(selectedAppId = 3L, games = queue3).index)

        // The selected game is resolved away; the clamp lands on the first remaining game and is
        // persisted, so the removed id cannot become active again when the queue later grows.
        val queue2 = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.NEEDS_REVIEW),
        )
        val clamped = resolveMatchCenterSelection(selectedAppId = 3L, games = queue2)

        assertEquals(0, clamped.index)
        assertEquals(1L, clamped.persistedAppId)
    }

    @Test
    fun emptyQueue_clearsSelection() {
        val selection = resolveMatchCenterSelection(selectedAppId = 2L, games = emptyList())

        assertEquals(0, selection.index)
        assertNull(selection.persistedAppId)
    }

    @Test
    fun selectionMissingFromQueue_fallsBackToFirstGame() {
        val games = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.UNMATCHED),
        )

        val fromNull = resolveMatchCenterSelection(selectedAppId = null, games = games)
        assertEquals(0, fromNull.index)
        assertEquals(1L, fromNull.persistedAppId)

        val fromUnknown = resolveMatchCenterSelection(selectedAppId = 99L, games = games)
        assertEquals(0, fromUnknown.index)
        assertEquals(1L, fromUnknown.persistedAppId)
    }

    @Test
    fun derivedIndex_feedsTheStateSelection() {
        val games = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.UNMATCHED),
        )
        val selection = resolveMatchCenterSelection(selectedAppId = 2L, games = games)
        val state = HltbMatchCenterUiState(
            ambiguous = games.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW },
            unmatched = games.filter { it.matchStatus == HltbMatchStatus.UNMATCHED },
            selectedIndex = selection.index,
        )

        assertEquals(2L, state.selectedGame?.appId)
        assertEquals(2, state.currentPosition)
    }
}