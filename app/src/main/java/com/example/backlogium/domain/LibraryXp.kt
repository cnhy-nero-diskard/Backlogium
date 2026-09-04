package com.example.backlogium.domain

import com.example.backlogium.gamification.AchievementInput
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.gamification.RuleConfig

/**
 * One game's inputs to its XP contribution — deliberately the *same* inputs
 * [GamificationUpdater] feeds the engine, not the row's displayed `playtimeForever`:
 *
 * - [minutesPlayed] is frozen backfill + tracked session minutes. Steam's lifetime playtime is
 *   not XP-bearing unless the player opted into the history import, so a never-tracked game
 *   contributes zero however many hours Steam reports.
 * - [unlockedRarityPercents] holds one entry per unlocked achievement — its frozen rarity
 *   snapshot, or null when Steam reported no global stat (un-tierable, worth zero).
 */
data class GameXpInput(
    val appId: Long,
    val minutesPlayed: Int,
    val completionistMinutes: Int?,
    val unlockedRarityPercents: List<Double?> = emptyList(),
)

/**
 * Splits the engine's library-wide XP total into its per-game parts, for the Library's XP badge.
 *
 * Not a re-implementation: it calls `Gamification.gameXp` and `Gamification.achievementXp` with
 * the inputs [GamificationUpdater] uses, so the per-game values **sum to** the player's stored
 * `totalXp`. That identity is exact rather than approximate — `Gamification.xp` sums per-game
 * `gameXp` results that are each already rounded — which is why it is worth asserting in a test.
 *
 * The identity is scoped to the games handed in. The engine sums achievements from every stored
 * unlocked row and playtime over every game with tracked or backfilled minutes; an orphan
 * achievement row, or a backfilled game no longer owned, contributes to `totalXp` with no
 * Library row to badge it.
 *
 * `cfg` is required, never defaulted: [RuleConfig] is user-tunable and persisted, and both engine
 * entry points default it, so an omitted config would compile and render plausible numbers that
 * disagree with the player's total for anyone who edited their rules.
 */
object LibraryXp {

    /** XP a single game has contributed: its tapered playtime XP plus its achievements' XP. */
    fun contribution(input: GameXpInput, cfg: RuleConfig): Long =
        Gamification.gameXp(input.minutesPlayed, input.completionistMinutes, cfg) +
            Gamification.achievementXp(input.achievementInputs(), cfg)

    /** [contribution] across many games, keyed by appId. */
    fun contributions(inputs: List<GameXpInput>, cfg: RuleConfig): Map<Long, Long> =
        inputs.associate { it.appId to contribution(it, cfg) }

    /**
     * The engine's achievement shape for one game. Ids are synthetic: `achievementXp` sums per
     * entry and never reads the id, so the rarity percents are the whole payload.
     */
    private fun GameXpInput.achievementInputs(): List<AchievementInput> =
        unlockedRarityPercents.mapIndexed { index, percent ->
            AchievementInput(id = "$appId#$index", unlocked = true, globalUnlockPercent = percent)
        }
}
