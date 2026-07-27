package com.example.backlogium.domain

import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.GamePlaytimeInput
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The property the XP badge promises: per-game contributions **sum to** the player's total XP.
 *
 * The identity is exact, not approximate — `Gamification.xp` sums per-game `gameXp` results that
 * are each already rounded — so it is asserted rather than bounded. It is what makes a badge
 * defensible: one that disagreed with the profile's `totalXp` would be worse than no badge.
 */
class LibraryXpTest {

    private val tunedConfig = RuleConfig(
        xpPerMinute = 3,
        commonAchievementXp = 7,
        uncommonAchievementXp = 21,
        rareAchievementXp = 55,
        epicAchievementXp = 130,
        legendaryAchievementXp = 400,
    )

    @Test
    fun perGameBadgesSumToTheEngineTotal() {
        val badges = LibraryXp.contributions(library, RuleConfig())

        assertEquals(engineTotalXp(RuleConfig()), badges.values.sum())
    }

    @Test
    fun theSumHoldsUnderAnEditedRuleConfig() {
        // The trap this guards: `gameXp`/`achievementXp` both default their `cfg`, so a derivation
        // that forgot to thread the persisted config would still pass with defaults and silently
        // disagree with the total for every user who retuned their rules.
        val badges = LibraryXp.contributions(library, tunedConfig)

        assertEquals(engineTotalXp(tunedConfig), badges.values.sum())
        // And the tuned config really does change the answer, so the assertion above has teeth.
        assertNotEquals(LibraryXp.contributions(library, RuleConfig()), badges)
    }

    @Test
    fun theSumIsScopedToTheGamesTheLibraryShows() {
        // The engine sums achievements from every stored unlocked row and playtime over every game
        // with tracked or backfilled minutes — neither is the Library list. An orphan achievement
        // row counts toward totalXp with no row to badge it, so the identity is asserted against
        // the engine run over *these* games, never unconditionally against a stored total.
        val orphanAchievement = AchievementInput("orphan", unlocked = true, globalUnlockPercent = 0.5)
        val totalWithOrphan = Gamification.xp(
            games = library.map { it.playtimeInput() },
            achievements = library.flatMap { it.achievementInputs() } + orphanAchievement,
            cfg = RuleConfig(),
        ).totalXp

        val badges = LibraryXp.contributions(library, RuleConfig()).values.sum()

        assertEquals(totalWithOrphan - 250, badges) // 0.5% → legendary, unbadgeable
    }

    @Test
    fun neverTrackedGameContributesZero() {
        // A 500-hour Steam library entry with no tracked session and no imported history earns
        // nothing: the badge reports contribution, not lifetime playtime.
        val untracked = GameXpInput(appId = 99L, minutesPlayed = 0, completionistMinutes = 1_800)

        assertEquals(0, LibraryXp.contribution(untracked, RuleConfig()))
    }

    @Test
    fun unTierableAchievementAddsNothing() {
        // A null snapshot means Steam reported no global stat: unlocked, but not tierable.
        val input = GameXpInput(
            appId = 1L,
            minutesPlayed = 0,
            completionistMinutes = null,
            unlockedRarityPercents = listOf(null, null),
        )

        assertEquals(0, LibraryXp.contribution(input, RuleConfig()))
    }

    /** A mixed library: tapered, un-tapered, achievement-only, and playtime-only games. */
    private val library = listOf(
        GameXpInput(
            appId = 1L,
            minutesPlayed = 1_200,
            completionistMinutes = 3_000,
            unlockedRarityPercents = listOf(80.0, 12.0, 0.4),
        ),
        GameXpInput(
            appId = 2L,
            minutesPlayed = 9_000, // well past the taper's zero point
            completionistMinutes = 1_500,
            unlockedRarityPercents = listOf(3.0),
        ),
        GameXpInput(
            appId = 3L,
            minutesPlayed = 400,
            completionistMinutes = null, // no HLTB length: flat fallback rate
        ),
        GameXpInput(
            appId = 4L,
            minutesPlayed = 0,
            completionistMinutes = 600,
            unlockedRarityPercents = listOf(55.0, 55.0),
        ),
    )

    private fun engineTotalXp(cfg: RuleConfig): Int = Gamification.xp(
        games = library.map { it.playtimeInput() },
        achievements = library.flatMap { it.achievementInputs() },
        cfg = cfg,
    ).totalXp

    private fun GameXpInput.playtimeInput() = GamePlaytimeInput(
        gameId = appId.toString(),
        minutesPlayed = minutesPlayed,
        completionistAverageMinutes = completionistMinutes,
    )

    private fun GameXpInput.achievementInputs() =
        unlockedRarityPercents.mapIndexed { index, percent ->
            AchievementInput("$appId#$index", unlocked = true, globalUnlockPercent = percent)
        }
}
