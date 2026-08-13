package com.example.backlogium.domain

import java.time.LocalDate

/** A player-facing transition produced by persisted, earned gamification progress. */
sealed interface ProgressEvent {
    data class LevelUp(val from: Int, val to: Int) : ProgressEvent
    data class QuestMet(val date: LocalDate) : ProgressEvent
    data class StreakMilestone(val days: Int) : ProgressEvent
    data class StreakBroken(val previousLength: Int) : ProgressEvent
}

/**
 * Stable presentation order for simultaneous events. Lower values are presented first:
 * level-up, streak milestone, quest met, then streak broken.
 */
val ProgressEvent.priority: Int
    get() = when (this) {
        is ProgressEvent.LevelUp -> 0
        is ProgressEvent.StreakMilestone -> 1
        is ProgressEvent.QuestMet -> 2
        is ProgressEvent.StreakBroken -> 3
    }

val progressEventPriorityComparator: Comparator<ProgressEvent> =
    compareBy(ProgressEvent::priority)

fun Iterable<ProgressEvent>.inPresentationOrder(): List<ProgressEvent> =
    sortedWith(progressEventPriorityComparator)
