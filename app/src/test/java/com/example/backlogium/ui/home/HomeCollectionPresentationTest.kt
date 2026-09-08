package com.example.backlogium.ui.home

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.CollectionMemberSignals
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCollectionPresentationTest {

    @Test
    fun homeCollectionGames_followSelectedProgressOrderBeforeThumbnailLimit() {
        val gamesById = mapOf(
            1L to LibraryGame(
                appId = 1L,
                name = "Alpha",
                iconUrl = "alpha",
                playtimeForever = 20,
            ),
            2L to LibraryGame(
                appId = 2L,
                name = "Zulu",
                iconUrl = "zulu",
                playtimeForever = 80,
            ),
        )
        val signals = listOf(
            CollectionMemberSignals(
                appId = 1L,
                name = "Alpha",
                playtimeMinutes = 20,
                completionistMinutes = 100,
                achievementsUnlocked = null,
                achievementsTotal = null,
            ),
            CollectionMemberSignals(
                appId = 2L,
                name = "Zulu",
                playtimeMinutes = 80,
                completionistMinutes = 100,
                achievementsUnlocked = null,
                achievementsTotal = null,
            ),
        )

        val games = homeCollectionGamesInDisplayOrder(
            mode = CollectionMode.DEADLINE_GOAL,
            sort = CollectionSort.COMPLETION_FRACTION,
            signals = signals,
            gamesById = gamesById,
        )

        assertEquals(listOf(2L, 1L), games.map { it.appId })
    }

    @Test
    fun thumbnailPreview_keepsMemberOrder_andUsesThreeItemOverflow() {
        val games = (1L..11L).map { appId ->
            HomeCollectionGame(appId, "Game $appId", iconUrl = null)
        }

        val preview = homeCollectionThumbnailPreview(games)

        assertEquals(listOf(1L, 2L, 3L), preview.visibleGames.map { it.appId })
        assertEquals(8, preview.overflowCount)
    }

    @Test
    fun thumbnailPreview_hasNoOverflowForThreeOrFewerGames() {
        val games = (1L..2L).map { appId ->
            HomeCollectionGame(appId, "Game $appId", iconUrl = null)
        }

        val preview = homeCollectionThumbnailPreview(games)

        assertEquals(listOf(1L, 2L), preview.visibleGames.map { it.appId })
        assertEquals(0, preview.overflowCount)
    }

    @Test
    fun activeApp_matchesEveryCollectionThatContainsIt() {
        val sharedGame = HomeCollectionGame(42L, "Shared", iconUrl = null)
        val firstCollection = listOf(sharedGame)
        val secondCollection = listOf(HomeCollectionGame(7L, "Other", iconUrl = null), sharedGame)

        assertEquals(true, homeCollectionContainsPlayingGame(firstCollection, 42L))
        assertEquals(true, homeCollectionContainsPlayingGame(secondCollection, 42L))
        assertEquals(false, homeCollectionContainsPlayingGame(firstCollection, null))
        assertEquals(false, homeCollectionContainsPlayingGame(firstCollection, 7L))
    }

    @Test
    fun cancelledMovedDrag_restoresPersistedOrder() {
        assertEquals(
            listOf(1L, 2L, 3L),
            homeCollectionOrderAfterCancelledDrag(
                persistedCards = listOf(1L, 2L, 3L),
                currentCards = listOf(2L, 3L, 1L),
                initialIndex = 0,
                currentIndex = 2,
            ),
        )
    }

    @Test
    fun cancelledUnmovedDrag_keepsCurrentOrder() {
        assertEquals(
            listOf(1L, 2L, 3L),
            homeCollectionOrderAfterCancelledDrag(
                persistedCards = listOf(1L, 2L, 3L),
                currentCards = listOf(1L, 2L, 3L),
                initialIndex = 1,
                currentIndex = 1,
            ),
        )
    }
}
