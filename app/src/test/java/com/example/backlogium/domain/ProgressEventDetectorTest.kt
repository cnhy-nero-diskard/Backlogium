package com.example.backlogium.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressEventDetectorTest {
    private val today = LocalDate.parse("2026-08-13")

    @Test
    fun firstPersistSeedsWithoutCelebrating() {
        val result = detect(
            marks = ProgressMarks(),
            previous = null,
            current = ProgressState(level = 30, currentStreak = 14, todayQuestMet = true),
        )

        assertTrue(result.events.isEmpty())
        assertEquals(30, result.marks.lastCelebratedLevel)
        assertEquals(14, result.marks.lastCelebratedStreakMilestone)
        assertEquals(today, result.marks.lastQuestCelebratedDate)
        assertEquals(emptySet<LocalDate>(), result.marks.pendingQuestDates)
        assertTrue(result.marks.initialized)
    }

    @Test
    fun syncLevelRiseProducesOneCollapsedLevelUp() {
        val result = detect(
            marks = initializedMarks(level = 4),
            previous = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
            current = ProgressState(level = 7, currentStreak = 0, todayQuestMet = false),
        )

        assertEquals(listOf(ProgressEvent.LevelUp(4, 7)), result.events)
    }

    @Test
    fun nonEarnedSourcesReseedWithoutEventsIncludingDownward() {
        RecomputeSource.entries.filter { it != RecomputeSource.SYNC }.forEach { source ->
            val raised = detect(
                marks = initializedMarks(level = 4, milestone = 7),
                previous = ProgressState(level = 4, currentStreak = 7, todayQuestMet = true),
                current = ProgressState(level = 24, currentStreak = 14, todayQuestMet = true),
                source = source,
            )
            assertTrue("$source should not emit", raised.events.isEmpty())
            assertEquals(24, raised.marks.lastCelebratedLevel)
            assertEquals(14, raised.marks.lastCelebratedStreakMilestone)

            val lowered = detect(
                marks = raised.marks,
                previous = ProgressState(level = 24, currentStreak = 14, todayQuestMet = true),
                current = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
                source = source,
            )
            assertTrue(lowered.events.isEmpty())
            assertEquals(4, lowered.marks.lastCelebratedLevel)
            assertEquals(0, lowered.marks.lastCelebratedStreakMilestone)

            val earnedAgain = detect(
                marks = lowered.marks,
                previous = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
                current = ProgressState(level = 5, currentStreak = 0, todayQuestMet = false),
            )
            assertEquals(listOf(ProgressEvent.LevelUp(4, 5)), earnedAgain.events)
        }
    }

    @Test
    fun syncCrossingSeveralStreakMilestonesEmitsHighestOnly() {
        val result = detect(
            marks = initializedMarks(level = 4, milestone = 0),
            previous = ProgressState(level = 4, currentStreak = 6, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 15, todayQuestMet = false),
        )

        assertEquals(listOf(ProgressEvent.StreakMilestone(14)), result.events)
    }

    @Test
    fun nonMilestoneStreakRiseDoesNotEmitMilestone() {
        val result = detect(
            marks = initializedMarks(level = 4, milestone = 7),
            previous = ProgressState(level = 4, currentStreak = 7, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 8, todayQuestMet = false),
        )

        assertTrue(result.events.isEmpty())
    }

    @Test
    fun todaysQuestFirstMetProducesQuestEvent() {
        val result = detect(
            marks = initializedMarks(level = 4, questDate = today.minusDays(1)),
            previous = ProgressState(level = 4, currentStreak = 2, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 3, todayQuestMet = true),
        )

        assertEquals(listOf(ProgressEvent.QuestMet(today)), result.events)
        // The event's identity is recorded durably, not left to be re-derived from the day's row.
        assertEquals(setOf(today), result.marks.pendingQuestDates)
    }

    @Test
    fun alreadyCelebratedQuestDoesNotReappear() {
        val result = detect(
            marks = initializedMarks(level = 4, questDate = today),
            previous = ProgressState(level = 4, currentStreak = 2, todayQuestMet = true),
            current = ProgressState(level = 4, currentStreak = 2, todayQuestMet = true),
        )

        assertTrue(result.events.isEmpty())
        assertEquals(emptySet<LocalDate>(), result.marks.pendingQuestDates)
    }

    @Test
    fun aQuestAlreadyMetBeforeThisRecomputeIsNotEarnedAgain() {
        // Edge-triggered: a second sync on the same day sees the flag already set. Only the
        // recompute that flipped it earned the transition.
        val result = detect(
            marks = initializedMarks(level = 4),
            previous = ProgressState(level = 4, currentStreak = 3, todayQuestMet = true),
            current = ProgressState(level = 4, currentStreak = 3, todayQuestMet = true),
        )

        assertTrue(result.events.isEmpty())
        assertEquals(emptySet<LocalDate>(), result.marks.pendingQuestDates)
    }

    @Test
    fun earnedQuestDatesFromEarlierDaysSurviveALaterRecompute() {
        val yesterday = today.minusDays(1)
        val result = detect(
            marks = initializedMarks(level = 4).copy(pendingQuestDates = setOf(yesterday)),
            previous = ProgressState(level = 4, currentStreak = 1, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 2, todayQuestMet = true),
        )

        assertEquals(listOf(ProgressEvent.QuestMet(today)), result.events)
        assertEquals(setOf(yesterday, today), result.marks.pendingQuestDates)
    }

    @Test
    fun nonEarnedSourcesNeitherAddNorCancelPendingQuestDates() {
        val yesterday = today.minusDays(1)
        RecomputeSource.entries.filter { it != RecomputeSource.SYNC }.forEach { source ->
            val result = detect(
                marks = initializedMarks(level = 4, questDate = yesterday)
                    .copy(pendingQuestDates = setOf(yesterday)),
                previous = ProgressState(level = 4, currentStreak = 1, todayQuestMet = false),
                current = ProgressState(level = 4, currentStreak = 2, todayQuestMet = true),
                source = source,
            )

            assertTrue("$source should not emit", result.events.isEmpty())
            // Today's newly-met row is not an earned quest; yesterday's earned one is still owed.
            assertEquals("$source pending set", setOf(yesterday), result.marks.pendingQuestDates)
        }
    }

    @Test
    fun nonEarnedSourcesNeverRegressTheAcknowledgedQuestDate() {
        // Today's quest is unmet under the new rule. Clearing the acknowledged high-water mark here
        // is what used to make an already-celebrated day look undelivered again.
        RecomputeSource.entries.filter { it != RecomputeSource.SYNC }.forEach { source ->
            val result = detect(
                marks = initializedMarks(level = 4, questDate = today.minusDays(1)),
                previous = ProgressState(level = 4, currentStreak = 1, todayQuestMet = true),
                current = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
                source = source,
            )

            assertEquals("$source", today.minusDays(1), result.marks.lastQuestCelebratedDate)
        }
    }

    @Test
    fun earnedStreakBreakCarriesLostLengthAndBecomesPending() {
        val result = detect(
            marks = initializedMarks(level = 4, milestone = 14),
            previous = ProgressState(level = 4, currentStreak = 14, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
        )

        assertEquals(listOf(ProgressEvent.StreakBroken(14)), result.events)
        assertEquals(PendingStreakBreak(today, 14), result.marks.pendingStreakBreak)
    }

    @Test
    fun nonEarnedStreakDropProducesNoBreak() {
        val result = detect(
            marks = initializedMarks(level = 4, milestone = 14),
            previous = ProgressState(level = 4, currentStreak = 14, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
            source = RecomputeSource.RULE_CHANGE,
        )

        assertTrue(result.events.isEmpty())
        assertEquals(today, result.marks.lastStreakBrokenDate)
        assertEquals(null, result.marks.pendingStreakBreak)
    }

    @Test
    fun simultaneousEventsAreReturnedInStablePresentationPriority() {
        val result = detect(
            marks = initializedMarks(level = 4, milestone = 0, questDate = today.minusDays(1)),
            previous = ProgressState(level = 4, currentStreak = 6, todayQuestMet = false),
            current = ProgressState(level = 7, currentStreak = 7, todayQuestMet = true),
        )

        assertEquals(
            listOf(
                ProgressEvent.LevelUp(4, 7),
                ProgressEvent.StreakMilestone(7),
                ProgressEvent.QuestMet(today),
            ),
            result.events,
        )
    }

    @Test
    fun pendingBreakIsNotDuplicatedBeforeAcknowledgement() {
        val pending = PendingStreakBreak(today, 5)
        val result = detect(
            marks = initializedMarks(level = 4).copy(pendingStreakBreak = pending),
            previous = ProgressState(level = 4, currentStreak = 5, todayQuestMet = false),
            current = ProgressState(level = 4, currentStreak = 0, todayQuestMet = false),
        )

        assertTrue(result.events.isEmpty())
        assertEquals(pending, result.marks.pendingStreakBreak)
    }

    private fun detect(
        marks: ProgressMarks,
        previous: ProgressState?,
        current: ProgressState,
        source: RecomputeSource = RecomputeSource.SYNC,
    ): ProgressDetectionResult = ProgressEventDetector.detect(
        marks = marks,
        previous = previous,
        current = current,
        source = source,
        today = today,
    )

    private fun initializedMarks(
        level: Int,
        milestone: Int = 0,
        questDate: LocalDate? = null,
    ): ProgressMarks = ProgressMarks(
        lastCelebratedLevel = level,
        lastCelebratedStreakMilestone = milestone,
        lastQuestCelebratedDate = questDate,
        initialized = true,
    )
}
