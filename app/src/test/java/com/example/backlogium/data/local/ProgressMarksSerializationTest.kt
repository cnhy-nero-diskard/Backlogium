package com.example.backlogium.data.local

import com.example.backlogium.domain.PendingLevelUp
import com.example.backlogium.domain.PendingStreakBreak
import com.example.backlogium.domain.PendingTransition
import com.example.backlogium.domain.ProgressMarks
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/**
 * The durable progress-event marks (recover-missed-playtime design.md §8) must survive a
 * write→reopen round trip exactly: the explicit earned obligations are what keep a corrective
 * reseed from swallowing an already-owed delivery after process death.
 */
@RunWith(RobolectricTestRunner::class)
class ProgressMarksSerializationTest {

    @Test
    fun levelUpAndMilestoneObligationsSurviveAStoreReopen() = runBlocking {
        val store = SettingsDataStore(RuntimeEnvironment.getApplication())
        val expected = ProgressMarks(
            lastCelebratedLevel = 20,
            lastCelebratedStreakMilestone = 14,
            lastQuestCelebratedDate = LocalDate.parse("2026-08-11"),
            initialized = true,
            pendingStreakBreak = PendingStreakBreak(LocalDate.parse("2026-08-12"), previousLength = 9),
            pendingQuestDates = setOf(LocalDate.parse("2026-08-10"), LocalDate.parse("2026-08-12")),
            pendingLevelUps = setOf(PendingLevelUp(4, 7), PendingLevelUp(20, 21)),
            pendingStreakMilestones = setOf(7, 14),
            pendingTransition = PendingTransition(
                source = com.example.backlogium.domain.RecomputeSource.RETROACTIVE_PLAY,
                previousLevel = 19,
                previousStreak = 0,
                previousTodayQuestMet = false,
                evaluationDate = LocalDate.parse("2026-08-13"),
                operationId = "op:recovery-1",
            ),
        )

        store.writeProgressMarks(expected)

        // A fresh instance is what a restart looks like: same stored preferences, new process.
        val reopened = SettingsDataStore(RuntimeEnvironment.getApplication())
        assertEquals(expected, reopened.progressMarksFlow.first())
        assertEquals(expected, reopened.readProgressMarks())
    }

    @Test
    fun clearingObligationsRemovesTheirKeys() = runBlocking {
        val store = SettingsDataStore(RuntimeEnvironment.getApplication())
        store.writeProgressMarks(
            ProgressMarks(
                lastCelebratedLevel = 5,
                initialized = true,
                pendingLevelUps = setOf(PendingLevelUp(4, 5)),
                pendingStreakMilestones = setOf(7),
            ),
        )
        store.writeProgressMarks(
            ProgressMarks(lastCelebratedLevel = 5, initialized = true),
        )

        val reopened = SettingsDataStore(RuntimeEnvironment.getApplication()).readProgressMarks()
        assertTrue(reopened.pendingLevelUps.isEmpty())
        assertEquals(emptySet<Int>(), reopened.pendingStreakMilestones)
        assertEquals(5, reopened.lastCelebratedLevel)
    }
}