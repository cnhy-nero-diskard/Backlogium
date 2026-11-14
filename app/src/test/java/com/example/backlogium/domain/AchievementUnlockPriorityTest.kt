package com.example.backlogium.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class AchievementUnlockPriorityTest {
    @Test fun unlockGroupsKeepTheirOwnIdentityBetweenMilestonesAndQuests() {
        val first = AchievementUnlockGroup("one", "fixture", 1, "One", 1, emptyList())
        val second = first.copy(eventId = "two", appId = 2)
        val events = listOf(ProgressEvent.QuestMet(LocalDate.of(2026, 1, 1)),
            ProgressEvent.UnlockGroup(first), ProgressEvent.StreakBroken(2),
            ProgressEvent.LevelUp(1, 2), ProgressEvent.UnlockGroup(second), ProgressEvent.StreakMilestone(7))
        assertEquals(listOf(ProgressEvent.LevelUp(1, 2), ProgressEvent.StreakMilestone(7),
            ProgressEvent.UnlockGroup(first), ProgressEvent.UnlockGroup(second),
            ProgressEvent.QuestMet(LocalDate.of(2026, 1, 1)), ProgressEvent.StreakBroken(2)), events.inPresentationOrder())
    }
}
