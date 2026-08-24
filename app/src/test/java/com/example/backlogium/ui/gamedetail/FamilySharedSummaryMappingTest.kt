package com.example.backlogium.ui.gamedetail

import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.domain.GameSource
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the detail screen is handed for a family-shared game versus an owned one.
 *
 * The composables gate every piece of family-sharing presentation — the badge, the coverage notice,
 * the removal action — on `GameSummaryUi.isFamilyShared`, and choose the playtime figure from
 * `headlineMinutes`. So this mapping decides both halves of the promise: that a shared game is
 * marked and its coverage disclosed, and that an owned game is presented exactly as it was before
 * this feature existed.
 */
class FamilySharedSummaryMappingTest {

    private fun content(source: GameSource, playtimeForever: Int, tracked: Int, monitor: Boolean = false, latestTrackedAt: Long? = null) =
        Content(
            game = LibraryGame(
                appId = 620L,
                name = "Portal 2",
                iconUrl = "",
                playtimeForever = playtimeForever,
                source = source,
            ),
            achievements = emptyList(),
            trackedMinutes = tracked,
            latestTrackedAt = latestTrackedAt,
            config = RuleConfig(),
            liveMonitorEnabled = monitor,
        )

    @Test
    fun aSharedGame_isMarkedAndLeadsWithWhatWasObserved() {
        // Steam reports no lifetime playtime for a borrowed game, so playtimeForever is
        // structurally 0 and leading with it would read "0m" beside a real session history.
        val summary = content(GameSource.FAMILY_SHARED, playtimeForever = 0, tracked = 95)
            .toSummary(rows = emptyList(), activePlayers = null)

        assertTrue(summary.isFamilyShared)
        assertEquals(95, summary.headlineMinutes)
    }
    @Test
    fun sharedPlaytimeWithoutLatestSession_isUnknownNotNever() {
        val summary = content(GameSource.FAMILY_SHARED, playtimeForever = 0, tracked = 95)
            .toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(LastPlayed.Unknown, summary.lastPlayed)
    }

    @Test
    fun sharedPlaytimeUsesLatestTrackedSessionForRecency() {
        val summary = content(
            GameSource.FAMILY_SHARED,
            playtimeForever = 0,
            tracked = 95,
            latestTrackedAt = 1_700_000_000_000L,
        ).toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(LastPlayed.At(1_700_000_000_000L), summary.lastPlayed)
    }


    @Test
    fun anOwnedGame_carriesNoMarkingAndLeadsWithSteamsTotal() {
        val summary = content(GameSource.STEAM_OWNED, playtimeForever = 480, tracked = 95)
            .toSummary(rows = emptyList(), activePlayers = null)

        assertFalse(summary.isFamilyShared)
        assertEquals(480, summary.headlineMinutes)
    }

    @Test
    fun theMonitorPreferenceIsCarried_soTheDisclosureCanNameTheRemedy() {
        // Enabling the monitor is the actionable remedy for partial coverage, so the notice points
        // at it rather than merely apologising -- and says nothing about it when it is already on.
        val off = content(GameSource.FAMILY_SHARED, playtimeForever = 0, tracked = 10)
            .toSummary(rows = emptyList(), activePlayers = null)
        val on = content(GameSource.FAMILY_SHARED, playtimeForever = 0, tracked = 10, monitor = true)
            .toSummary(rows = emptyList(), activePlayers = null)

        assertFalse(off.liveMonitorEnabled)
        assertTrue(on.liveMonitorEnabled)
    }

    @Test
    fun aSharedGameWithNoAchievements_getsNoAchievementCounts() {
        // Where Steam reports nothing, the screen shows no achievement surface rather than an
        // empty or zeroed one -- the same treatment an owned game with no achievements gets.
        val summary = content(GameSource.FAMILY_SHARED, playtimeForever = 0, tracked = 30)
            .toSummary(rows = emptyList(), activePlayers = null)

        assertEquals(0, summary.achievementsTotal)
        assertEquals(0, summary.achievementsUnlocked)
    }
}
