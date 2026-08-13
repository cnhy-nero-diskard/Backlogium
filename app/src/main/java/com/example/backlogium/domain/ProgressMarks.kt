package com.example.backlogium.domain

import java.time.LocalDate

/**
 * Persistent delivery baselines for progress events.
 *
 * [initialized] is derived from whether the baseline preference keys exist; it is not a fifth
 * stored value. [pendingStreakBreak] is encoded together with the streak-break date so the lost
 * length survives process death without adding another preference key. [pendingTransition] is the
 * write-ahead record of an in-flight [com.example.backlogium.domain.GamificationUpdater.persist]
 * call: it is written before the corresponding Room write and cleared only after that call's
 * marks are finalized, so a crash in between can be resolved deterministically rather than either
 * fabricating an event that never happened or losing one that did.
 */
data class ProgressMarks(
    val lastCelebratedLevel: Int = 0,
    val lastCelebratedStreakMilestone: Int = 0,
    val lastQuestCelebratedDate: LocalDate? = null,
    val lastStreakBrokenDate: LocalDate? = null,
    val initialized: Boolean = false,
    val pendingStreakBreak: PendingStreakBreak? = null,
    val pendingTransition: PendingTransition? = null,
)

data class PendingStreakBreak(
    val date: LocalDate,
    val previousLength: Int,
)

/**
 * The durable snapshot of a [ProgressState] taken immediately before an in-flight recompute's Room
 * write, kept only until that recompute's marks are finalized. Once the Room write lands, this is
 * the only surviving record of what the profile looked like beforehand — the information an
 * interrupted recompute needs to be resolved correctly rather than reseeded from an already-updated
 * profile.
 */
data class PendingTransition(
    val source: RecomputeSource,
    val previousLevel: Int,
    val previousStreak: Int,
    val previousTodayQuestMet: Boolean,
    val evaluationDate: LocalDate,
)
