package com.example.backlogium.gamification

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for rarity-tiered achievement XP (add-achievement-xp). Pure functions, no
 * clock/I/O. Covers tier boundaries, the null-vs-zero-percent distinction, additive
 * combination with playtime XP, and per-tier config overrides.
 */
class AchievementXpTest {

    private val cfg = RuleConfig() // documented defaults: 5 / 15 / 40 / 100 / 250

    // --- 3.1 tierFor --------------------------------------------------------

    @Test
    fun tierFor_interiorValues() {
        assertEquals(RarityTier.COMMON, Gamification.tierFor(75.0))
        assertEquals(RarityTier.UNCOMMON, Gamification.tierFor(35.0))
        assertEquals(RarityTier.RARE, Gamification.tierFor(12.0))
        assertEquals(RarityTier.EPIC, Gamification.tierFor(3.0))
        assertEquals(RarityTier.LEGENDARY, Gamification.tierFor(0.5))
    }

    @Test
    fun tierFor_boundariesResolveToMoreCommonTier() {
        // Each cut point belongs to the higher (more common) tier.
        assertEquals(RarityTier.COMMON, Gamification.tierFor(50.0))
        assertEquals(RarityTier.UNCOMMON, Gamification.tierFor(20.0))
        assertEquals(RarityTier.RARE, Gamification.tierFor(5.0))
        assertEquals(RarityTier.EPIC, Gamification.tierFor(1.0))
        // Just below each boundary drops to the rarer tier.
        assertEquals(RarityTier.UNCOMMON, Gamification.tierFor(49.999))
        assertEquals(RarityTier.RARE, Gamification.tierFor(19.999))
        assertEquals(RarityTier.EPIC, Gamification.tierFor(4.999))
        assertEquals(RarityTier.LEGENDARY, Gamification.tierFor(0.999))
    }

    @Test
    fun tierFor_zeroPercentIsLegendary() {
        assertEquals(RarityTier.LEGENDARY, Gamification.tierFor(0.0))
    }

    // --- 3.2 achievementXp --------------------------------------------------

    @Test
    fun achievementXp_singleUnlockedPerTier() {
        assertEquals(5, Gamification.achievementXp(listOf(unlocked(75.0)), cfg)) // COMMON
        assertEquals(15, Gamification.achievementXp(listOf(unlocked(35.0)), cfg)) // UNCOMMON
        assertEquals(40, Gamification.achievementXp(listOf(unlocked(12.0)), cfg)) // RARE
        assertEquals(100, Gamification.achievementXp(listOf(unlocked(3.0)), cfg)) // EPIC
        assertEquals(250, Gamification.achievementXp(listOf(unlocked(0.5)), cfg)) // LEGENDARY
    }

    @Test
    fun achievementXp_lockedContributesZero() {
        assertEquals(0, Gamification.achievementXp(listOf(locked(0.5)), cfg)) // would be 250 if unlocked
    }

    @Test
    fun achievementXp_nullPercentContributesZeroEvenWhenUnlocked() {
        assertEquals(0, Gamification.achievementXp(listOf(unlocked(null)), cfg))
    }

    @Test
    fun achievementXp_zeroPercentUnlockedIsLegendary() {
        assertEquals(250, Gamification.achievementXp(listOf(unlocked(0.0)), cfg))
    }

    @Test
    fun achievementXp_mixedListSums() {
        val list = listOf(
            unlocked(75.0), // COMMON 5
            unlocked(12.0), // RARE 40
            unlocked(0.0), // LEGENDARY 250
            locked(0.5), // 0 (locked)
            unlocked(null), // 0 (un-tierable)
        )
        assertEquals(295, Gamification.achievementXp(list, cfg)) // 5 + 40 + 250
    }

    @Test
    fun achievementXp_emptyListIsZero() {
        assertEquals(0, Gamification.achievementXp(emptyList(), cfg))
    }

    // --- 3.3 xp(...) with achievements --------------------------------------

    @Test
    fun xp_combinesPlaytimeAndAchievementXpAdditively() {
        val games = listOf(flatGame(300)) // 300 playtime XP
        val achievements = listOf(unlocked(3.0), unlocked(75.0)) // EPIC 100 + COMMON 5 = 105
        val state = Gamification.xp(games, achievements)
        assertEquals(405, state.totalXp) // 300 + 105
    }

    @Test
    fun xp_omittingAchievementsMatchesPlaytimeOnly() {
        val games = listOf(flatGame(300))
        // Regression against the base engine: omitting achievements == playtime-only behavior.
        assertEquals(Gamification.xp(games).totalXp, 300)
        assertEquals(Gamification.xp(games, emptyList()).totalXp, 300)
        assertEquals(3, Gamification.xp(games).level)
    }

    // --- 3.4 RuleConfig overrides -------------------------------------------

    @Test
    fun achievementXp_honorsNonDefaultPerTierValues() {
        val custom = cfg.copy(commonAchievementXp = 1, legendaryAchievementXp = 1000)
        assertEquals(1, Gamification.achievementXp(listOf(unlocked(75.0)), custom))
        assertEquals(1000, Gamification.achievementXp(listOf(unlocked(0.0)), custom))
    }

    // --- helpers ------------------------------------------------------------

    private fun unlocked(percent: Double?) =
        AchievementInput(id = "a", unlocked = true, globalUnlockPercent = percent)

    private fun locked(percent: Double?) =
        AchievementInput(id = "a", unlocked = false, globalUnlockPercent = percent)

    private fun flatGame(minutes: Int) =
        GamePlaytimeInput(gameId = "flat", minutesPlayed = minutes, completionistAverageMinutes = null)
}
