package com.example.backlogium.ui.gamedetail

import com.example.backlogium.data.repo.GameAchievement
import com.example.backlogium.gamification.RarityTier
import com.example.backlogium.gamification.RarityStanding
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RarityStandingUiMappingTest {

    @Test
    fun standingReadsLiveGlobalRates_notFrozenRaritySnapshots() {
        val result = content(
            achievement(unlocked = true, snapshot = 0.1, global = 40.0),
            achievement(unlocked = true, snapshot = 0.2, global = 30.0),
        ).toRarityStanding()!!

        // Full completion uses the rarest *current* rate. Using snapshots would return 0.1%.
        assertEquals(30.0, result.ceilingPercent!!, EPS)
        assertEquals(0.7, result.averageOwnerUnlockCount, EPS)
    }

    @Test
    fun standingKeepsTheFullAchievementCount_whenRatesArePartial() {
        val result = content(
            achievement(unlocked = true, global = 10.0),
            achievement(unlocked = true, global = 10.0),
            achievement(unlocked = false, global = 10.0),
            achievement(unlocked = false, global = 10.0),
            achievement(unlocked = false, global = null),
        ).toRarityStanding()!!

        // N=5 and n=2 means m=3; the four known rates yield 40%, not the invalid 30% from N=4.
        assertEquals(5, result.totalAchievements)
        assertEquals(40.0, result.ceilingPercent!!, EPS)
    }

    @Test
    fun noRows_omitsTheStanding() {
        assertNull(content().toRarityStanding())
    }

    @Test
    fun headlineUsesCeilingCopy_andSuppressesUninformativeBounds() {
        assertEquals(
            "Top 6.7% or better",
            rarityStandingHeadline(RarityStanding.Result(40, 30, 20.0, 6.65)),
        )
        assertNull(rarityStandingHeadline(RarityStanding.Result(40, 30, 20.0, 50.0)))
        assertEquals(
            "At most 0.1% of owners have completed the game",
            rarityStandingHeadline(RarityStanding.Result(3, 3, 0.2, 0.0)),
        )
    }

    @Test
    fun standingTier_reusesAchievementRarityThresholds() {
        assertEquals(RarityTier.COMMON, rarityStandingTier(result(50.0)))
        assertEquals(RarityTier.UNCOMMON, rarityStandingTier(result(20.0)))
        assertEquals(RarityTier.RARE, rarityStandingTier(result(5.0)))
        assertEquals(RarityTier.EPIC, rarityStandingTier(result(1.0)))
        assertEquals(RarityTier.LEGENDARY, rarityStandingTier(result(0.9)))
        assertNull(rarityStandingTier(result(null)))
    }

    private fun content(vararg achievements: GameAchievement) = Content(
        game = null,
        achievements = achievements.toList(),
        trackedMinutes = 0,
        config = RuleConfig(),
    )

    private fun result(ceiling: Double?) = RarityStanding.Result(
        totalAchievements = 40,
        unlockedAchievements = 30,
        averageOwnerUnlockCount = 20.0,
        ceilingPercent = ceiling,
    )

    private fun achievement(
        unlocked: Boolean,
        snapshot: Double? = null,
        global: Double? = null,
    ) = GameAchievement(
        apiName = "ACH_${unlocked}_${snapshot}_${global}",
        displayName = "Achievement",
        iconUrl = null,
        unlocked = unlocked,
        rarityPercent = snapshot,
        globalPercent = global,
    )

    private companion object {
        const val EPS = 1e-9
    }
}
