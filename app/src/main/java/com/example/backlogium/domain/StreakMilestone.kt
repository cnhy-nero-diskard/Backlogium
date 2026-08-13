package com.example.backlogium.domain

/**
 * Streak-milestone interval rule shared by progress-event detection and presentation.
 *
 * Deliberately lives in the app module's `domain` package — NOT in the pure `:gamification`
 * module — because it observes an engine-authored derived value rather than deriving XP/streak
 * state itself. [ProgressEventDetector] and [com.example.backlogium.data.repo.ProgressEventRepository]
 * are the sole consumers of this interval rule; Home's milestone animation is driven entirely by
 * the durable `ProgressEvent.StreakMilestone` they produce, not by calling this helper directly.
 */

/** How often (in streak days) a milestone is reached. */
const val STREAK_MILESTONE_INTERVAL_DAYS: Int = 7

/**
 * True when [streakDays] lands exactly on a milestone — a positive multiple of
 * [STREAK_MILESTONE_INTERVAL_DAYS]. Zero and negative streaks are never milestones.
 */
fun isStreakMilestone(streakDays: Int): Boolean =
    streakDays > 0 && streakDays % STREAK_MILESTONE_INTERVAL_DAYS == 0
