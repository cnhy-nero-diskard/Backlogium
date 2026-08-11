package com.example.backlogium.gamification

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Derives a provable upper bound on the share of owners who have unlocked at least as many
 * achievements as the player. The rates are Steam percentages, so the returned ceiling is also a
 * percentage; the average owner count converts their sum to a fraction of one hundred.
 *
 * For m = N - n, an owner at or above the player's count is missing at most m achievements. In any
 * set of m + k achievements that owner therefore holds at least k, so the set's summed unlock
 * rates divided by k is an upper bound on that owner's population share. Sorting the known rates and
 * taking the smallest valid bound preserves that pigeonhole guarantee; m always comes from N even
 * when some rates are unknown.
 *
 * This is deliberately separate from [Gamification]. A standing is a display statistic, not an XP
 * or progression rule.
 */
object RarityStanding {

    /** Inputs for one game's standing. [globalUnlockPercents] may contain null unknown rates. */
    data class Input(
        val totalAchievements: Int,
        val unlockedAchievements: Int,
        val globalUnlockPercents: List<Double?>,
    )

    data class Result(
        val totalAchievements: Int,
        val unlockedAchievements: Int,
        val averageOwnerUnlockCount: Double,
        /** Null when no valid pigeonhole bound can be derived. */
        val ceilingPercent: Double?,
    )

    /**
     * Computes the tightest available bound in O(N log N), using only currently known rates.
     * Unknown rates are omitted from the candidate sets, but the missing-achievement count always
     * comes from the full [Input.totalAchievements].
     */
    fun derive(input: Input): Result {
        val total = input.totalAchievements.coerceAtLeast(0)
        val unlocked = input.unlockedAchievements.coerceIn(0, total)
        val knownRates = input.globalUnlockPercents
            .asSequence()
            .take(total)
            .mapNotNull { percent ->
                percent
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.coerceAtMost(100.0)
            }
            .toList()
        val averageOwnerUnlockCount = knownRates.sum() / 100.0

        if (unlocked == 0) {
            return Result(total, unlocked, averageOwnerUnlockCount, ceilingPercent = null)
        }

        val missing = total - unlocked
        // k = 1 is the first valid candidate, so at least m + 1 known rates are required.
        if (knownRates.size < missing + 1) {
            return Result(total, unlocked, averageOwnerUnlockCount, ceilingPercent = null)
        }

        val sortedRates = knownRates.sorted()
        var runningSum = 0.0
        repeat(missing) { index -> runningSum += sortedRates[index] }

        var best = Double.POSITIVE_INFINITY
        for (k in 1..unlocked) {
            val windowSize = missing + k
            if (windowSize > sortedRates.size) break
            runningSum += sortedRates[windowSize - 1]
            best = minOf(best, runningSum / k)
        }

        val ceiling = best
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, 100.0)
        return Result(total, unlocked, averageOwnerUnlockCount, ceiling)
    }

    /**
     * Formats a derived ceiling without ever rounding below it. Values below ten percent retain
     * one decimal place; larger values use whole percentages. The display floor keeps a genuine
     * zero-rate result readable as "0.1" rather than the meaningless "0".
     */
    fun formatCeiling(derivedPercent: Double): String {
        val percent = derivedPercent
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.coerceAtMost(100.0)
            ?: 0.0
        val rounded = if (percent < 10.0) {
            BigDecimal.valueOf(percent).setScale(1, RoundingMode.CEILING)
                .max(MIN_DISPLAYABLE_PERCENT)
        } else {
            BigDecimal.valueOf(percent).setScale(0, RoundingMode.CEILING)
        }
        return rounded.toPlainString()
    }

    private val MIN_DISPLAYABLE_PERCENT = BigDecimal("0.1")
}
