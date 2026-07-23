package com.example.backlogium.domain

/**
 * Streak-milestone rule for the celebratory Home-screen animation (restyle-visual-identity).
 *
 * Deliberately lives in the app module's `domain` package — NOT in the pure `:gamification`
 * module — because that module's `Gamification.kt` is a stub slated for wholesale replacement
 * by `add-gamification-engine`; new code added there risks being dropped when that change
 * lands (see the change's design.md decision 5). This is a purely decorative UI trigger: it
 * awards no XP and does not touch `XpState`, so it has no reason to live inside the engine's
 * boundary regardless.
 */

/** How often (in streak days) a milestone celebration fires. */
const val STREAK_MILESTONE_INTERVAL_DAYS: Int = 7

/**
 * True when [streakDays] lands exactly on a milestone — a positive multiple of
 * [STREAK_MILESTONE_INTERVAL_DAYS]. Zero and negative streaks are never milestones.
 */
fun isStreakMilestone(streakDays: Int): Boolean =
    streakDays > 0 && streakDays % STREAK_MILESTONE_INTERVAL_DAYS == 0
