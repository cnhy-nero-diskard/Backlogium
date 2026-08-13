package com.example.backlogium.domain

import java.time.LocalDate

/** Plain transition state consumed by [ProgressEventDetector]; contains no Room/storage types. */
data class ProgressState(
    val level: Int,
    val currentStreak: Int,
    val todayQuestMet: Boolean,
)

data class ProgressDetectionResult(
    val events: List<ProgressEvent>,
    val marks: ProgressMarks,
)

/**
 * The later of an optional stored date and a new one — the shape every acknowledgement high-water
 * mark advances by, so no mark can be regressed by an out-of-order update.
 */
fun laterOf(stored: LocalDate?, candidate: LocalDate): LocalDate =
    if (stored == null || candidate > stored) candidate else stored

/** Pure diff from persisted progress state to the values about to replace it. */
object ProgressEventDetector {
    fun detect(
        marks: ProgressMarks,
        previous: ProgressState?,
        current: ProgressState,
        source: RecomputeSource,
        today: LocalDate,
    ): ProgressDetectionResult {
        if (!marks.initialized || previous == null) {
            return ProgressDetectionResult(
                events = emptyList(),
                marks = seed(current, today),
            )
        }

        if (source != RecomputeSource.SYNC) {
            return ProgressDetectionResult(
                events = emptyList(),
                marks = reseed(marks, previous, current, today),
            )
        }

        val events = buildList {
            if (current.level > marks.lastCelebratedLevel) {
                add(ProgressEvent.LevelUp(marks.lastCelebratedLevel, current.level))
            }

            val highestMilestone = highestMilestoneAtOrBelow(current.currentStreak)
            if (
                current.currentStreak > previous.currentStreak &&
                highestMilestone > marks.lastCelebratedStreakMilestone
            ) {
                add(ProgressEvent.StreakMilestone(highestMilestone))
            }

            if (newlyEarnedQuestDate(marks, previous, current, today) != null) {
                add(ProgressEvent.QuestMet(today))
            }

            if (
                previous.currentStreak > 0 &&
                current.currentStreak == 0 &&
                marks.lastStreakBrokenDate != today &&
                marks.pendingStreakBreak == null
            ) {
                add(ProgressEvent.StreakBroken(previous.currentStreak))
            }
        }.inPresentationOrder()

        val broken = events.filterIsInstance<ProgressEvent.StreakBroken>().firstOrNull()
        val earnedQuestDate = newlyEarnedQuestDate(marks, previous, current, today)
        val nextMarks = marks.copy(
            initialized = true,
            // Existing unacknowledged dates are left exactly as they are: each one was earned by
            // its own transition and is owed its own delivery.
            pendingQuestDates = if (earnedQuestDate == null) {
                marks.pendingQuestDates
            } else {
                marks.pendingQuestDates + earnedQuestDate
            },
            pendingStreakBreak = if (broken != null) {
                PendingStreakBreak(today, broken.previousLength)
            } else {
                marks.pendingStreakBreak
            },
        )

        return ProgressDetectionResult(events, nextMarks)
    }

    /**
     * The quest date this earned recompute *newly earned*, or null.
     *
     * Edge-triggered on today's quest flag, like [ProgressEvent.StreakBroken]: only the recompute
     * that flips today from unmet to met earned it. A later sync on the same day observes the flag
     * already set and adds nothing, and a date already pending or already acknowledged is never
     * re-added — so a rule change that re-mets an acknowledged day cannot replay it.
     */
    private fun newlyEarnedQuestDate(
        marks: ProgressMarks,
        previous: ProgressState,
        current: ProgressState,
        today: LocalDate,
    ): LocalDate? {
        if (!current.todayQuestMet || previous.todayQuestMet) return null
        if (today in marks.pendingQuestDates) return null
        val acknowledgedThrough = marks.lastQuestCelebratedDate
        if (acknowledgedThrough != null && today <= acknowledgedThrough) return null
        return today
    }

    /**
     * First-ever baseline. Every mark is set from the values just computed and nothing is left
     * pending — in particular, historical `questMet = true` rows from before tracking existed are
     * not turned into pending quest dates, because no consumer was ever owed them.
     */
    private fun seed(current: ProgressState, today: LocalDate): ProgressMarks = ProgressMarks(
        lastCelebratedLevel = current.level,
        lastCelebratedStreakMilestone = highestMilestoneAtOrBelow(current.currentStreak),
        lastQuestCelebratedDate = today.takeIf { current.todayQuestMet },
        lastStreakBrokenDate = null,
        initialized = true,
        pendingStreakBreak = null,
        pendingQuestDates = emptySet(),
    )

    /**
     * Non-earned baseline reset. Level/milestone marks follow the written values in either
     * direction, but the quest marks are handled differently in both directions: no pending date is
     * synthesized from a recomputed row, and [ProgressMarks.lastQuestCelebratedDate] only ever
     * advances — regressing it would let already-acknowledged history become deliverable again.
     * Dates earned before this reset stay pending; the reset redefines the baseline, it does not
     * cancel deliveries already owed.
     */
    private fun reseed(
        marks: ProgressMarks,
        previous: ProgressState,
        current: ProgressState,
        today: LocalDate,
    ): ProgressMarks = marks.copy(
        lastCelebratedLevel = current.level,
        lastCelebratedStreakMilestone = highestMilestoneAtOrBelow(current.currentStreak),
        lastQuestCelebratedDate = if (current.todayQuestMet) {
            laterOf(marks.lastQuestCelebratedDate, today)
        } else {
            marks.lastQuestCelebratedDate
        },
        lastStreakBrokenDate = if (previous.currentStreak > 0 && current.currentStreak == 0) {
            today
        } else {
            marks.lastStreakBrokenDate
        },
        initialized = true,
        pendingStreakBreak = null,
    )

    private fun highestMilestoneAtOrBelow(streak: Int): Int {
        if (streak <= 0) return 0
        val candidate = streak - (streak % STREAK_MILESTONE_INTERVAL_DAYS)
        return candidate.takeIf(::isStreakMilestone) ?: 0
    }
}
