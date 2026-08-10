package com.example.backlogium.ui.shell

import com.example.backlogium.data.repo.NowPlaying
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileHeaderViewModelTest {

    @Test
    fun resolvedSteamTitleReachesTheHeader() {
        assertEquals(
            "Portal",
            NowPlaying.InGame(gameId = 10L, name = "Portal", iconUrl = null).profileHeaderName(),
        )
    }

    @Test
    fun appIdFallbackDoesNotLookLikeAResolvedTitle() {
        assertNull(
            NowPlaying.InGame(gameId = 10L, name = "App 10", iconUrl = null).profileHeaderName(),
        )
    }

    @Test
    fun genericFallbackDoesNotLookLikeAResolvedTitle() {
        assertNull(
            NowPlaying.InGame(gameId = null, name = "In game", iconUrl = null).profileHeaderName(),
        )
    }

    @Test
    fun blankTitleDoesNotReachTheHeader() {
        assertNull(
            NowPlaying.InGame(gameId = 10L, name = "   ", iconUrl = null).profileHeaderName(),
        )
    }
}
