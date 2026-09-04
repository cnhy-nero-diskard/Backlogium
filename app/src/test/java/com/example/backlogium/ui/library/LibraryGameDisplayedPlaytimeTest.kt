package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.GameSource
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `LibraryGame.displayedPlaytimeMinutes(xp: XpInputs)` — not a call site of the domain-level
 * `GameSource.displayedPlaytimeMinutes` despite sharing its name (see
 * add-shared-game-playtime-and-filter design.md) — is what actually drives the Library screen's
 * playtime display, sort order, and completion-progress bar. Bumped from `private` to `internal`
 * (matching `SettingsViewModel.manualImportFeedback`'s existing precedent) so it is directly
 * testable without standing up the whole ViewModel.
 */
class LibraryGameDisplayedPlaytimeTest {

    @Test
    fun ownedGameShowsSteamsLifetimeTotalRegardlessOfTrackedMinutes() {
        val game = game(GameSource.STEAM_OWNED, playtimeForever = 500)

        assertEquals(500, game.displayedPlaytimeMinutes(xp(trackedMinutes = 999)))
    }

    @Test
    fun sharedGameShowsTrackedMinutesWhenNoManualEstimate() {
        val game = game(GameSource.FAMILY_SHARED, manualSharedMinutes = 0)

        assertEquals(30, game.displayedPlaytimeMinutes(xp(trackedMinutes = 30)))
    }

    @Test
    fun sharedGamesManualEstimateIsAdditiveWithTrackedMinutes() {
        val game = game(GameSource.FAMILY_SHARED, manualSharedMinutes = 60)

        assertEquals(90, game.displayedPlaytimeMinutes(xp(trackedMinutes = 30)))
    }

    private fun game(
        source: GameSource,
        playtimeForever: Int = 0,
        manualSharedMinutes: Int = 0,
    ) = LibraryGame(
        appId = 1L,
        name = "Game",
        iconUrl = "",
        playtimeForever = playtimeForever,
        source = source,
        manualSharedMinutes = manualSharedMinutes,
    )

    private fun xp(trackedMinutes: Int) = XpInputs(
        trackedByGame = mapOf(1L to trackedMinutes),
        rarityByGame = emptyMap(),
        cfg = RuleConfig(),
    )
}
