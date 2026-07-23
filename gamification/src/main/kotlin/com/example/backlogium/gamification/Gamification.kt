package com.example.backlogium.gamification

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The gamification engine: pure, platform-agnostic rules that turn tracked playtime plus
 * configuration into XP/level, goal progress, daily-quest status, and streaks.
 *
 * The engine is pure — no clocks, no I/O, no persistence. Callers supply inputs (the app
 * injects "today" and the ordered day list) and persist the returned values themselves.
 * It has zero Android / networking / Room dependencies so it runs in plain JVM unit tests.
 *
 * The math below (playtime-XP taper, level curve, streak grace) is locked by the
 * `add-gamification-engine` design; every constant lives in [RuleConfig] so it can be
 * retuned without touching call sites or stored schema.
 */

enum class QuestMode { ANY, GOAL_ONLY }

/**
 * Rarity classification for a Steam achievement, derived from its global unlock percentage
 * by [Gamification.tierFor]. Rarer tiers award more XP; the cut points (50/20/5/1) are fixed
 * — they define what "rare" *means* — while the XP per tier lives in [RuleConfig].
 */
enum class RarityTier { COMMON, UNCOMMON, RARE, EPIC, LEGENDARY }

/**
 * All tunable rule constants. Defaults are the documented ones: 1 XP per minute, the
 * `50·(L-1)·L` level curve, a 30-minute daily quest counting any game, no streak grace,
 * a playtime-XP taper that reaches zero at twice a game's completionist average, and the
 * per-rarity-tier achievement XP awards.
 */
data class RuleConfig(
    val xpPerMinute: Int = 1,
    val levelBase: Int = 50, // xpAt(L) = levelBase * (L-1) * L
    /** Playtime at or beyond this multiple of the completionist average earns no further XP. */
    val hltbZeroMultiple: Double = 2.0,
    /** Shapes how fast the marginal XP rate tapers toward the zero point; higher = faster. */
    val hltbDecayExponent: Int = 4,
    val questThresholdMin: Int = 30,
    val questMode: QuestMode = QuestMode.ANY,
    val streakGraceDays: Int = 0,
    // XP awarded per unlocked achievement, by rarity tier. Only the amounts are tunable;
    // the tier cut points are fixed in tierFor().
    val commonAchievementXp: Int = 5,
    val uncommonAchievementXp: Int = 15,
    val rareAchievementXp: Int = 40,
    val epicAchievementXp: Int = 100,
    val legendaryAchievementXp: Int = 250,
)

data class XpState(val totalXp: Int, val level: Int, val xpIntoLevel: Int, val xpForNext: Int)

/**
 * One game's playtime input to [Gamification.xp]. [completionistAverageMinutes] is the
 * HowLongToBeat completionist-average length (minutes); `null` when unavailable, which
 * routes that game through the flat, uncapped fallback rate.
 */
data class GamePlaytimeInput(
    val gameId: String,
    val minutesPlayed: Int,
    val completionistAverageMinutes: Int?,
)

/**
 * One Steam achievement's input to [Gamification.achievementXp]. [globalUnlockPercent] is the
 * fraction of players who have unlocked it (0.0..100.0, from Steam's
 * `GetGlobalAchievementPercentagesForApp`); `null` means Steam reports no global stat for it,
 * which makes it un-tierable and worth zero XP even when [unlocked]. A concrete `0.0` is a
 * genuine ultra-rare value and tiers as [RarityTier.LEGENDARY].
 */
data class AchievementInput(
    val id: String,
    val unlocked: Boolean,
    val globalUnlockPercent: Double?,
)

data class GoalProgress(val fraction: Double) // 0.0 .. 1.0
data class DayInput(val date: LocalDate, val anyMinutes: Int, val goalMinutes: Int)
data class QuestResult(val date: LocalDate, val met: Boolean)
data class StreakState(val current: Int, val longest: Int)

object Gamification {

    /**
     * Total XP for a player's library, plus the derived level and progress into it.
     *
     * Sums [gameXp] across every supplied game — each game's XP taper is computed against
     * its own completionist average — adds [achievementXp] for any unlocked, rarity-tiered
     * achievements into the *same* pool, then derives level/progress via [levelState].
     *
     * [achievements] defaults to empty, so omitting it reproduces playtime-only behavior
     * exactly. Achievement XP is additive: it is one unified pool with playtime XP, not a
     * separate currency or level. [levelState] stays public as the shared seam.
     */
    fun xp(
        games: List<GamePlaytimeInput>,
        achievements: List<AchievementInput> = emptyList(),
        cfg: RuleConfig = RuleConfig(),
    ): XpState {
        val playtimeXp = games.sumOf { gameXp(it.minutesPlayed, it.completionistAverageMinutes, cfg) }
        return levelState(playtimeXp + achievementXp(achievements, cfg), cfg)
    }

    /**
     * Rarity tier for an achievement's [globalUnlockPercent] (0.0..100.0). Cut points are
     * fixed: boundary values resolve to the more-common (higher) tier — exactly 50% is
     * [RarityTier.COMMON], 20% [RarityTier.UNCOMMON], 5% [RarityTier.RARE], 1% [RarityTier.EPIC].
     * A concrete `0.0` (genuinely ultra-rare) tiers as [RarityTier.LEGENDARY].
     */
    fun tierFor(globalUnlockPercent: Double): RarityTier = when {
        globalUnlockPercent >= 50.0 -> RarityTier.COMMON
        globalUnlockPercent >= 20.0 -> RarityTier.UNCOMMON
        globalUnlockPercent >= 5.0 -> RarityTier.RARE
        globalUnlockPercent >= 1.0 -> RarityTier.EPIC
        else -> RarityTier.LEGENDARY
    }

    /**
     * Total XP contributed by a player's unlocked achievements, weighted by rarity tier.
     *
     * Locked achievements contribute zero. An achievement with a null [AchievementInput.globalUnlockPercent]
     * is un-tierable (Steam has no global stat) and contributes zero even when unlocked —
     * distinct from a `0.0` percent, which is a real ultra-rare value tiering as legendary.
     */
    fun achievementXp(achievements: List<AchievementInput>, cfg: RuleConfig = RuleConfig()): Int =
        achievements.sumOf { a ->
            val percent = a.globalUnlockPercent
            if (!a.unlocked || percent == null) 0 else xpForTier(tierFor(percent), cfg)
        }

    /**
     * Diminishing-returns XP for a single game's tracked minutes.
     *
     * The marginal rate at minute `m` is `xpPerMinute · (1 − m/Z)^k` for `0 ≤ m < Z` and
     * `0` for `m ≥ Z`, where `Z = hltbZeroMultiple · T` (T = completionist average) and
     * `k = hltbDecayExponent`. This returns the closed-form integral of that rate over the
     * minutes played — a smooth cumulative value, not a per-minute loop — so evaluation cost
     * is constant regardless of [minutesPlayed]:
     *
     * ```
     * gameXp = xpPerMinute · (Z / (k+1)) · (1 − (1 − min(M,Z)/Z)^(k+1))
     * ```
     *
     * When [completionistAverageMinutes] is null (or non-positive), there is no length to
     * taper against, so XP falls back to the flat, uncapped rate `minutes × xpPerMinute`.
     */
    fun gameXp(
        minutesPlayed: Int,
        completionistAverageMinutes: Int?,
        cfg: RuleConfig = RuleConfig(),
    ): Int {
        val m = minutesPlayed.coerceAtLeast(0)
        // No completionist length to taper against: flat, uncapped rate.
        if (completionistAverageMinutes == null || completionistAverageMinutes <= 0) {
            return m * cfg.xpPerMinute
        }
        val z = cfg.hltbZeroMultiple * completionistAverageMinutes // zero point, in minutes
        if (z <= 0.0) return m * cfg.xpPerMinute // degenerate config guard
        val k = cfg.hltbDecayExponent
        val cappedM = m.toDouble().coerceAtMost(z) // no XP accrues past the zero point
        val cumulative =
            cfg.xpPerMinute * (z / (k + 1)) * (1.0 - (1.0 - cappedM / z).pow(k + 1))
        return cumulative.roundToInt()
    }

    /** Derive level and in-level progress directly from a total XP value. */
    fun levelState(totalXp: Int, cfg: RuleConfig = RuleConfig()): XpState {
        val xp = totalXp.coerceAtLeast(0)
        val level = levelFor(xp, cfg)
        val xpIntoLevel = xp - xpAt(level, cfg)
        val xpForNext = xpAt(level + 1, cfg) - xpAt(level, cfg)
        return XpState(totalXp = xp, level = level, xpIntoLevel = xpIntoLevel, xpForNext = xpForNext)
    }

    /** Goal progress = playtime / target, clamped to 0.0..1.0. Zero target guards to 0.0. */
    fun goalProgress(playtimeMin: Int, targetMin: Int): GoalProgress {
        if (targetMin <= 0) return GoalProgress(0.0)
        val fraction = (playtimeMin.toDouble() / targetMin.toDouble()).coerceIn(0.0, 1.0)
        return GoalProgress(fraction)
    }

    /** Whether a day's qualifying playtime met the configured threshold. */
    fun quest(day: DayInput, cfg: RuleConfig = RuleConfig()): QuestResult {
        val qualifying = when (cfg.questMode) {
            QuestMode.ANY -> day.anyMinutes
            QuestMode.GOAL_ONLY -> day.goalMinutes
        }
        return QuestResult(date = day.date, met = qualifying >= cfg.questThresholdMin)
    }

    /** Current and longest streaks from an ordered list of per-day quest results. */
    fun streak(days: List<QuestResult>, cfg: RuleConfig = RuleConfig()): StreakState {
        var current = 0
        var longest = 0
        var graceUsed = 0
        for (result in days) {
            if (result.met) {
                current += 1
                graceUsed = 0
                if (current > longest) longest = current
            } else if (graceUsed < cfg.streakGraceDays) {
                graceUsed += 1 // forgiven within the grace allowance; streak survives but does not grow
            } else {
                current = 0
                graceUsed = 0
            }
        }
        return StreakState(current = current, longest = longest)
    }

    // XP award for a rarity tier, from the tunable RuleConfig per-tier constants.
    private fun xpForTier(tier: RarityTier, cfg: RuleConfig): Int = when (tier) {
        RarityTier.COMMON -> cfg.commonAchievementXp
        RarityTier.UNCOMMON -> cfg.uncommonAchievementXp
        RarityTier.RARE -> cfg.rareAchievementXp
        RarityTier.EPIC -> cfg.epicAchievementXp
        RarityTier.LEGENDARY -> cfg.legendaryAchievementXp
    }

    // xpAt(L) = levelBase * (L - 1) * L : cumulative XP required to reach level L.
    private fun xpAt(level: Int, cfg: RuleConfig): Int {
        val l = level.coerceAtLeast(1)
        return cfg.levelBase * (l - 1) * l
    }

    // level(xp) = floor((1 + sqrt(1 + (4/levelBase) * xp)) / 2), the closed-form inverse of xpAt.
    private fun levelFor(totalXp: Int, cfg: RuleConfig): Int {
        if (totalXp <= 0) return 1
        val level = floor((1.0 + sqrt(1.0 + (4.0 / cfg.levelBase) * totalXp)) / 2.0).toInt()
        return level.coerceAtLeast(1)
    }
}
