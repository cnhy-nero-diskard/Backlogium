package com.example.backlogium.ui.review

import com.example.backlogium.data.repo.HltbMatchState
import org.junit.Assert.*
import org.junit.Test

class HltbReviewSessionTest {
    private fun game(id: Long, status: HltbMatchState = HltbMatchState.UNMATCHED) =
        MatchCenterGameUi(id, "Game $id", matchStatus = status, candidates = emptyList())

    private fun HltbReviewSession.ui() = state.value.toUiState()

    @Test fun explicitSkipExcludesGameFromReorderedAndRepeatedQueueEmissions() {
        val session = HltbReviewSession()
        session.updateQueue(listOf(game(1), game(2), game(3)))
        session.skip()
        session.updateQueue(listOf(game(3), game(1, HltbMatchState.NEEDS_REVIEW), game(2)))
        assertEquals(2L, session.ui().selectedGame?.appId)
        assertEquals(listOf(3L, 2L), session.ui().activeGames.map { it.appId })
        assertEquals(setOf(1L), session.ui().deferredAppIds)
        assertEquals(3, session.ui().allGames.size)
    }

    @Test fun nextAndPreviousDeferTheGameTheyLeaveAndDoNotSelectDeferredGames() {
        val session = HltbReviewSession()
        session.updateQueue(listOf(game(1), game(2), game(3), game(4)))
        session.selectIndex(2)
        session.navigate(-1)
        assertEquals(2L, session.ui().selectedGame?.appId)
        assertEquals(setOf(1L, 3L), session.ui().deferredAppIds)
        session.navigate(1)
        assertEquals(4L, session.ui().selectedGame?.appId)
        assertEquals(setOf(1L, 2L, 3L), session.ui().deferredAppIds)
        session.navigate(1) // Disabled boundary action must neither skip nor wrap.
        assertEquals(4L, session.ui().selectedGame?.appId)
    }

    @Test fun selectedIdentityFollowsPartitionReorderingAndRemovalUsesLatestPosition() {
        val session = HltbReviewSession()
        session.updateQueue(listOf(game(1), game(2), game(3)))
        session.navigate(1)
        session.updateQueue(listOf(game(1), game(2, HltbMatchState.NEEDS_REVIEW), game(3)))
        assertEquals(2L, session.ui().selectedGame?.appId)
        assertEquals(0, session.ui().selectedIndex)
        session.updateQueue(listOf(game(1), game(3)))
        assertEquals(3L, session.ui().selectedGame?.appId)
        assertEquals(setOf(1L), session.ui().deferredAppIds)
    }

    @Test fun removalPrefersForwardSurvivorThenEarlierUnprocessedGame() {
        val session = HltbReviewSession()
        session.updateQueue(listOf(game(1), game(2), game(3), game(4)))
        session.selectIndex(2)
        session.updateQueue(listOf(game(1), game(2), game(4)))
        assertEquals(4L, session.ui().selectedGame?.appId)
        session.updateQueue(listOf(game(1), game(2)))
        assertEquals(2L, session.ui().selectedGame?.appId)
    }

    @Test fun lastSkipExhaustsWithoutWrapAndReviewSkippedStartsAnotherPass() {
        val session = HltbReviewSession()
        session.updateQueue(listOf(game(1), game(2)))
        session.skip()
        session.skip()
        assertEquals(0, session.ui().total)
        assertNull(session.ui().selectedGame)
        assertEquals(2, session.ui().deferredCount)
        session.updateQueue(listOf(game(2), game(1)))
        assertNull(session.ui().selectedGame)
        session.reviewSkipped()
        assertEquals(2L, session.ui().selectedGame?.appId)
        assertTrue(session.ui().deferredAppIds.isEmpty())
    }

    @Test fun newQueueItemsAreActionableAfterExhaustionWithoutRevivingDeferredOnes() {
        val session = HltbReviewSession()
        session.updateQueue(listOf(game(1)))
        session.skip()
        session.updateQueue(listOf(game(1), game(2)))
        assertEquals(2L, session.ui().selectedGame?.appId)
        session.updateQueue(listOf(game(1)))
        assertNull(session.ui().selectedGame)
    }

    @Test fun scopedSkipDoesNotCompleteRouteOrSelectAnotherGameAndReentryDoesNotResetIt() {
        val session = HltbReviewSession()
        session.selectGame(2)
        assertTrue(session.ui().loading)
        assertFalse(session.ui().scopedAppMissing)
        session.updateQueue(listOf(game(1), game(2)))
        session.skip()
        assertNull(session.ui().selectedGame)
        assertFalse(session.ui().scopedAppMissing)
        session.selectGame(2) // Re-entering the composition after detail or recreation.
        assertNull(session.ui().selectedGame)
        session.reviewSkipped()
        assertEquals(2L, session.ui().selectedGame?.appId)
        session.updateQueue(listOf(game(2))) // Resolving a different game must not finish.
        assertFalse(session.ui().scopedAppMissing)
        session.updateQueue(listOf(game(1))) // Only the scoped game's removal finishes.
        assertTrue(session.ui().scopedAppMissing)
    }

    @Test fun newRouteOrProcessStartsWithoutDeferrals() {
        val old = HltbReviewSession()
        old.updateQueue(listOf(game(1)))
        old.skip()
        val fresh = HltbReviewSession()
        fresh.updateQueue(old.ui().allGames)
        assertEquals(1L, fresh.ui().selectedGame?.appId)
        assertTrue(fresh.ui().deferredAppIds.isEmpty())
    }
}
