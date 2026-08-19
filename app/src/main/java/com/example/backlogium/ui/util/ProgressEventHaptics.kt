package com.example.backlogium.ui.util

import com.example.backlogium.domain.ProgressEvent

/**
 * The single exhaustive answer for what each durable progress transition feels like. A streak
 * break is acknowledged by its visible Home presentation; losing progress is not punished
 * haptically.
 */
internal fun ProgressEvent.toHapticIntent(): HapticIntent = when (this) {
    is ProgressEvent.LevelUp -> HapticIntent.LevelUp
    is ProgressEvent.QuestMet -> HapticIntent.QuestMet
    is ProgressEvent.StreakMilestone -> HapticIntent.StreakMilestone
    is ProgressEvent.StreakBroken -> HapticIntent.Silent
}
