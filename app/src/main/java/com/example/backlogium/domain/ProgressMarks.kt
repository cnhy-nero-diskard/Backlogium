package com.example.backlogium.domain

import java.time.LocalDate

/**
 * Persistent delivery baselines for progress events.
 *
 * [initialized] is derived from whether the baseline preference keys exist; it is not a separate
 * stored value. [pendingStreakBreak] is encoded together with the streak-break date so the lost
 * length survives process death without adding another preference key. [pendingTransition] is the
 * write-ahead record of an in-flight [com.example.backlogium.domain.GamificationUpdater.persist]
 * call: it is written before the corresponding Room write and cleared only after that call's
 * marks are finalized, so a crash in between can be resolved deterministically rather than either
 * fabricating an event that never happened or losing one that did.
 *
 * The four `pending…` fields are *explicit earned identity*: a value is present only because an
 * earned transition put it there, so a consumer never has to re-derive whether it was earned. The
 * two `last…` fields are acknowledgement high-water marks. The distinction matters most for quests:
 * [pendingQuestDates] holds the dates a `SYNC` actually earned and no consumer has acknowledged,
 * while [lastQuestCelebratedDate] records how far acknowledgement (or a non-earned baseline reset)
 * has advanced. A stored `DailyProgress` row with `questMet = true` is evidence of nothing on its
 * own — it may predate progress-event tracking entirely, or have become met under a recomputed rule
 * — so earnedness is never inferred from it.
 */
data class ProgressMarks(
    val lastCelebratedLevel: Int = 0,
    val lastCelebratedStreakMilestone: Int = 0,
    val lastQuestCelebratedDate: LocalDate? = null,
    val lastStreakBrokenDate: LocalDate? = null,
    val initialized: Boolean = false,
    val pendingStreakBreak: PendingStreakBreak? = null,
    val pendingTransition: PendingTransition? = null,
    /**
     * Quest dates earned by an earned recompute and not yet acknowledged, oldest first when read
     * back from storage. Never synthesized from historical `DailyProgress` rows.
     */
    val pendingQuestDates: Set<LocalDate> = emptySet(),
    /**
     * Level-up transitions earned by earned recomputes and not yet acknowledged
     * (recover-missed-playtime, design.md §8 / tasks 5.5–5.6). Written at the same finalize step as
     * [pendingQuestDates] and preserved by every non-earned reseed, so a corrective baseline that
     * advances [lastCelebratedLevel] past one cannot swallow a delivery that was already owed.
     * Each entry is one independent earned transition: a fresh rise shares the set with an older
     * still-owed one instead of collapsing into it, and each is delivered — smallest `from` first —
     * and removed by its own acknowledgement.
     */
    val pendingLevelUps: Set<PendingLevelUp> = emptySet(),
    /**
     * Streak milestones earned by earned recomputes and not yet acknowledged, delivered smallest
     * first. Preserved across corrective reseeds for the same reason as [pendingLevelUps].
     */
    val pendingStreakMilestones: Set<Int> = emptySet(),
)

/**
 * An earned level-up awaiting delivery. Mirrors [com.example.backlogium.domain.ProgressEvent.LevelUp]
 * with its bounds captured durably, so a corrective reseed that moves [ProgressMarks.lastCelebratedLevel]
 * does not erase the transition that actually earned the celebration.
 */
data class PendingLevelUp(
    val from: Int,
    val to: Int,
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
    /**
     * The durable identity of the recovery operation whose staged persist wrote this record
     * (recover-missed-playtime task 5.6). Null for ordinary syncs; recovery writes it so a resume
     * can attribute the in-flight transition to its journal rather than to an unrelated later sync.
     */
    val operationId: String? = null,
)
