package com.example.backlogium.ui.gamedetail

import com.example.backlogium.data.repo.GameAchievement
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The row invariant enhance-game-detail exists to protect: **the percent a row displays is the
 * percent that produced the tier beside it.**
 *
 * Showing the live global percent on unlocked rows would routinely contradict the tier — a
 * 0.8%-at-unlock achievement now sitting at 6% would read "6.0% of players have this · Legendary",
 * which looks like a bug and invites someone to "fix" the tier. Display and tier are pinned to the
 * same frozen snapshot; the live percent is used only for locked rows, which have no snapshot.
 */
class AchievementRowMappingTest {

    private val config = RuleConfig()

    @Test
    fun unlocked_displaysTheSnapshotNotTheLivePercent() {
        // The achievement has become far more common since it was unlocked.
        val ui = achievement(unlocked = true, snapshot = 0.8, global = 6.0).toUi(config)

        assertEquals(0.8, ui.unlockPercent!!, 0.0001)
    }

    @Test
    fun unlocked_theDisplayedPercentIsTheOneThatProducedTheTier() {
        val snapshot = 0.8
        val ui = achievement(unlocked = true, snapshot = snapshot, global = 6.0).toUi(config)

        // Both halves of the status line derive from one number, by construction.
        assertEquals(Gamification.tierFor(snapshot), ui.tier)
        assertEquals(Gamification.tierFor(ui.unlockPercent!!), ui.tier)
    }

    @Test
    fun aLegendaryRowNeverShowsACommonPercentage() {
        val ui = achievement(unlocked = true, snapshot = 0.8, global = 60.0).toUi(config)

        assertEquals(RarityTier.LEGENDARY, ui.tier)
        // The tier's own boundary must contain the displayed figure — 60.0 would not.
        assertEquals(RarityTier.LEGENDARY, Gamification.tierFor(ui.unlockPercent!!))
    }

    @Test
    fun locked_fallsBackToTheGlobalPercent() {
        // A locked achievement never has a snapshot, so the live percent is its only rarity signal.
        val ui = achievement(unlocked = false, snapshot = null, global = 12.5).toUi(config)

        assertEquals(12.5, ui.unlockPercent!!, 0.0001)
        assertNull("locked rows are not tierable", ui.tier)
        assertEquals(0, ui.xp)
    }

    @Test
    fun neitherPercentKnown_showsNoRate() {
        val ui = achievement(unlocked = false, snapshot = null, global = null).toUi(config)

        assertNull("no zero, no placeholder", ui.unlockPercent)
    }

    @Test
    fun unlockedWithNoSnapshot_isNotTierableAndEarnsNoXp() {
        // Steam reported no global stat when it was unlocked: un-tierable and worth zero.
        val ui = achievement(unlocked = true, snapshot = null, global = null).toUi(config)

        assertNull(ui.tier)
        assertEquals(0, ui.xp)
    }

    @Test
    fun hiddenAndLocked_asksForTheHiddenLabel() {
        val ui = achievement(unlocked = false, hidden = true, description = null).toUi(config)

        assertTrue(ui.showHiddenLabel)
    }

    @Test
    fun hiddenButDescribed_rendersNormally() {
        // Once unlocked, Steam supplies the description; the row stops needing the label.
        val ui = achievement(
            unlocked = true,
            hidden = true,
            description = "Beat the game without dying",
        ).toUi(config)

        assertFalse(ui.showHiddenLabel)
        assertEquals("Beat the game without dying", ui.description)
    }

    @Test
    fun notHiddenAndUndescribed_showsNoLabelEither() {
        // A pre-migration row: no description yet, but it isn't hidden, so nothing to explain.
        val ui = achievement(unlocked = true, hidden = false, description = null).toUi(config)

        assertFalse(ui.showHiddenLabel)
        assertNull(ui.description)
    }

    private fun achievement(
        unlocked: Boolean,
        snapshot: Double? = null,
        global: Double? = null,
        hidden: Boolean = false,
        description: String? = null,
    ) = GameAchievement(
        apiName = "ACH_WIN",
        displayName = "Winner",
        iconUrl = null,
        unlocked = unlocked,
        rarityPercent = snapshot,
        globalPercent = global,
        unlockedAt = if (unlocked) 1_000L else null,
        description = description,
        hidden = hidden,
    )
}
