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

            if (current.todayQuestMet && marks.lastQuestCelebratedDate != today) {
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
        val nextMarks = if (broken != null) {
            marks.copy(
                initialized = true,
                pendingStreakBreak = PendingStreakBreak(today, broken.previousLength),
            )
        } else {
            marks.copy(initialized = true)
        }

        return ProgressDetectionResult(events, nextMarks)
    }

    private fun seed(current: ProgressState, today: LocalDate): ProgressMarks = ProgressMarks(
        lastCelebratedLevel = current.level,
        lastCelebratedStreakMilestone = highestMilestoneAtOrBelow(current.currentStreak),
        lastQuestCelebratedDate = today.takeIf { current.todayQuestMet },
        lastStreakBrokenDate = null,
        initialized = true,
        pendingStreakBreak = null,
    )

    private fun reseed(
        marks: ProgressMarks,
        previous: ProgressState,
        current: ProgressState,
        today: LocalDate,
    ): ProgressMarks = marks.copy(
        lastCelebratedLevel = current.level,
        lastCelebratedStreakMilestone = highestMilestoneAtOrBelow(current.currentStreak),
        lastQuestCelebratedDate = today.takeIf { current.todayQuestMet },
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
