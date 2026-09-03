package com.example.backlogium.ui.gamedetail

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.GameSource
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `Content.toSummary()` and `GameSummaryUi.headlineMinutes` independently duplicate the
 * backfill/tracked XP sum other layers already combine (see design.md's Context) — this covers
 * their own copies specifically, so the detail screen that hosts the "Set hours played" control
 * stays consistent with what it just let the player set (add-shared-game-playtime-and-filter).
 */
class GameSummaryUiTest {

    @Test
    fun sharedGamesManualMinutesAddToTheHeadlineFigure() {
        val summary = content(
            source = GameSource.FAMILY_SHARED,
            trackedMinutes = 30,
            manualSharedMinutes = 60,
        ).toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(90, summary.headlineMinutes)
    }

    @Test
    fun ownedGamesHeadlineIgnoresManualMinutes() {
        val summary = content(
            source = GameSource.STEAM_OWNED,
            playtimeForever = 500,
            manualSharedMinutes = 0,
        ).toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(500, summary.headlineMinutes)
    }

    @Test
    fun sharedGamesManualMinutesContributeToXp() {
        val withoutEstimate = content(
            source = GameSource.FAMILY_SHARED,
            trackedMinutes = 30,
            manualSharedMinutes = 0,
        ).toSummary(rows = emptyList(), activePlayers = null)
        val withEstimate = content(
            source = GameSource.FAMILY_SHARED,
            trackedMinutes = 30,
            manualSharedMinutes = 60,
        ).toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(
            "a manual estimate must move the same XP figure the Library shows",
            true,
            withEstimate.xpContributed > withoutEstimate.xpContributed,
        )
    }

    private fun content(
        source: GameSource,
        playtimeForever: Int = 0,
        trackedMinutes: Int = 0,
        manualSharedMinutes: Int = 0,
    ) = Content(
        game = LibraryGame(
            appId = 1L,
            name = "Game",
            iconUrl = "",
            playtimeForever = playtimeForever,
            source = source,
            manualSharedMinutes = manualSharedMinutes,
            completionistMinutes = 600,
        ),
        achievements = emptyList(),
        trackedMinutes = trackedMinutes,
        config = RuleConfig(),
    )
}
