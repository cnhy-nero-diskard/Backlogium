package com.example.backlogium.gamification

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Minimal implementation of the gamification engine.
 *
 * This is the stub surface consumed by the Android app (`add-android-steam-app`).
 * The public API and the locked math below come from the `add-gamification-engine`
 * design; that sibling change owns the full, exhaustively unit-tested implementation
 * and any future rule tuning. Keep this API stable so the app compiles against either.
 *
 * The engine is pure: no clocks, no I/O, no persistence. Callers pass inputs (the app
 * injects "today") and persist the returned values themselves.
 */

enum class QuestMode { ANY, GOAL_ONLY }

data class RuleConfig(
    val xpPerMinute: Int = 1,
    val levelBase: Int = 50, // xpAt(L) = levelBase * (L-1) * L
    val questThresholdMin: Int = 30,
    val questMode: QuestMode = QuestMode.ANY,
    val streakGraceDays: Int = 0,
)

data class XpState(val totalXp: Int, val level: Int, val xpIntoLevel: Int, val xpForNext: Int)
data class GoalProgress(val fraction: Double) // 0.0 .. 1.0
data class DayInput(val date: LocalDate, val anyMinutes: Int, val goalMinutes: Int)
data class QuestResult(val date: LocalDate, val met: Boolean)
data class StreakState(val current: Int, val longest: Int)

object Gamification {

    /** Total XP from tracked minutes, plus the derived level and progress into it. */
    fun xp(totalMinutes: Int, cfg: RuleConfig = RuleConfig()): XpState =
        levelState(totalMinutes.coerceAtLeast(0) * cfg.xpPerMinute, cfg)

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
