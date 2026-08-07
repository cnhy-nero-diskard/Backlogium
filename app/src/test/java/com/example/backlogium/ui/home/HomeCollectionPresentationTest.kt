package com.example.backlogium.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCollectionPresentationTest {

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
}
