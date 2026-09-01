package com.example.backlogium.ui.review

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.local.entity.HltbMatchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for match-center selection stability (task 5.5): the selection tracks a
 * game's appId rather than its index, because the queue reorders across the ambiguous/unmatched
 * partitions whenever a match status changes. Every case below goes through the ViewModel's
 * ordering path — partition into `ambiguous + unmatched` first, then derive — because deriving
 * from raw DAO order (which has no ORDER BY) applies the index to a different list.
 */
class HltbMatchCenterSelectionTest {

    private fun game(appId: Long, status: HltbMatchStatus) = MatchCenterGameUi(
        appId = appId,
        name = "Game $appId",
        matchStatus = status,
        candidates = listOf(HltbCandidate(hltbId = appId * 10, name = "Candidate $appId")),
    )

    private fun queue(vararg games: MatchCenterGameUi) = games.toList()

    /** The ViewModel's ordering path: partition first, derive from the display order second. */
    private fun deriveViewModelSelection(
        prior: MatchCenterSelection,
        rawDaoOrder: List<MatchCenterGameUi>,
    ): MatchCenterSelection = resolveMatchCenterSelection(
        prior,
        rawDaoOrder.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW } +
            rawDaoOrder.filter { it.matchStatus == HltbMatchStatus.UNMATCHED },
    )

    private fun stateFor(rawDaoOrder: List<MatchCenterGameUi>, selection: MatchCenterSelection) =
        HltbMatchCenterUiState(
            ambiguous = rawDaoOrder.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW },
            unmatched = rawDaoOrder.filter { it.matchStatus == HltbMatchStatus.UNMATCHED },
            selectedIndex = selection.index,
        )

    @Test
    fun unmatchedToNeedsReview_reorder_keepsSelectionOnTheSameGame() {
        // Broader search success moves the selected game from the unmatched partition into the
        // review partition: [U1, U2, U3] becomes [N2, U1, U3]. An index would now point at U1.
        val before = queue(
            game(1, HltbMatchStatus.UNMATCHED),
            game(2, HltbMatchStatus.UNMATCHED),
            game(3, HltbMatchStatus.UNMATCHED),
        )
        val initial = deriveViewModelSelection(MatchCenterSelection(0, null), before)
        assertEquals(1L, initial.persistedAppId)
        // The user picks game 2 at its display position.
        val picked = MatchCenterSelection(index = 1, persistedAppId = 2L)

        val after = queue(
            game(2, HltbMatchStatus.NEEDS_REVIEW),
            game(1, HltbMatchStatus.UNMATCHED),
            game(3, HltbMatchStatus.UNMATCHED),
        )
        val reordered = deriveViewModelSelection(picked, after)

        assertEquals(0, reordered.index)
        assertEquals(2L, reordered.persistedAppId)
        // The derived index still selects the same game through the state's own derivation.
        assertEquals(2L, stateFor(after, reordered).selectedGame?.appId)
    }

    @Test
    fun interleavedDaoOrder_derivesSelectionFromTheDisplayOrder() {
        // observeMatchCenter() has no ORDER BY, so raw DAO rows can interleave statuses:
        // [U1, N2, U3] displays as [N2, U1, U3]. Deriving the index from raw order would select
        // N2 when the user picked U1.
        val raw = queue(
            game(1, HltbMatchStatus.UNMATCHED),
            game(2, HltbMatchStatus.NEEDS_REVIEW),
            game(3, HltbMatchStatus.UNMATCHED),
        )
        val derived = deriveViewModelSelection(MatchCenterSelection(index = 1, persistedAppId = 1L), raw)

        val state = stateFor(raw, derived)
        assertEquals(1L, state.selectedGame?.appId)
        assertEquals(2, state.currentPosition)
    }

    @Test
    fun lastGameRemoved_clampsToTheSurvivingNeighbor() {
        // Resolving the last game must clamp onto the neighbor of the old position (B), not
        // jump back to the first game.
        val queue2 = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.NEEDS_REVIEW),
        )
        val clamped = deriveViewModelSelection(MatchCenterSelection(index = 2, persistedAppId = 3L), queue2)

        assertEquals(1, clamped.index)
        assertEquals(2L, clamped.persistedAppId)
        assertEquals(2L, stateFor(queue2, clamped).selectedGame?.appId)
    }

    @Test
    fun middleGameRemoved_keepsTheOldPosition() {
        // Resolving the middle game keeps the old position, which now lands on the next game.
        val queue2 = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(3, HltbMatchStatus.NEEDS_REVIEW),
        )
        val clamped = deriveViewModelSelection(MatchCenterSelection(index = 1, persistedAppId = 2L), queue2)

        assertEquals(1, clamped.index)
        assertEquals(3L, clamped.persistedAppId)
        assertEquals(3L, stateFor(queue2, clamped).selectedGame?.appId)
    }

    @Test
    fun firstGameRemoved_clampsToTheNewFirstGame() {
        val queue2 = queue(
            game(2, HltbMatchStatus.NEEDS_REVIEW),
            game(3, HltbMatchStatus.NEEDS_REVIEW),
        )
        val clamped = deriveViewModelSelection(MatchCenterSelection(index = 0, persistedAppId = 1L), queue2)

        assertEquals(0, clamped.index)
        assertEquals(2L, clamped.persistedAppId)
    }

    @Test
    fun emptyQueue_clearsSelectionEvenFromARemovedPosition() {
        val selection = deriveViewModelSelection(MatchCenterSelection(index = 4, persistedAppId = 2L), emptyList())

        assertEquals(0, selection.index)
        assertNull(selection.persistedAppId)
    }

    @Test
    fun selectionMissingFromQueue_fallsBackToFirstGame() {
        val games = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.UNMATCHED),
        )

        val fromNull = deriveViewModelSelection(MatchCenterSelection(0, null), games)
        assertEquals(0, fromNull.index)
        assertEquals(1L, fromNull.persistedAppId)

        val fromUnknown = deriveViewModelSelection(MatchCenterSelection(index = 0, persistedAppId = 99L), games)
        assertEquals(0, fromUnknown.index)
        assertEquals(1L, fromUnknown.persistedAppId)
    }

    @Test
    fun derivedIndex_feedsTheStateSelection() {
        val games = queue(
            game(1, HltbMatchStatus.NEEDS_REVIEW),
            game(2, HltbMatchStatus.UNMATCHED),
        )
        val selection = deriveViewModelSelection(MatchCenterSelection(index = 1, persistedAppId = 2L), games)
        val state = stateFor(games, selection)

        assertEquals(2L, state.selectedGame?.appId)
        assertEquals(2, state.currentPosition)
    }
}