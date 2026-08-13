package com.example.backlogium.domain

import java.time.LocalDate

/**
 * Persistent delivery baselines for progress events.
 *
 * [initialized] is derived from whether the baseline preference keys exist; it is not a fifth
 * stored value. [pendingStreakBreak] is encoded together with the streak-break date so the lost
 * length survives process death without adding another preference key.
 */
data class ProgressMarks(
    val lastCelebratedLevel: Int = 0,
    val lastCelebratedStreakMilestone: Int = 0,
    val lastQuestCelebratedDate: LocalDate? = null,
    val lastStreakBrokenDate: LocalDate? = null,
    val initialized: Boolean = false,
    val pendingStreakBreak: PendingStreakBreak? = null,
)

data class PendingStreakBreak(
    val date: LocalDate,
    val previousLength: Int,
)
