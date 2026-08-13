package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.ProgressEventRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exercises [resolvePendingTransition] directly against a marks store seeded with a
 * [PendingTransition] and a Room state that already reflects the interrupted `persist()` call's
 * write — i.e. the state left behind by a crash between the write-ahead record and the marks
 * finalize, for every kind of recompute source.
 */
class PendingTransitionRecoveryTest {
    private val today = LocalDate.parse("2026-08-13")

    @Test
    fun interruptedNonEarnedSourceProducesNoPhantomEventOnRecovery() = runTest {
        for (source in RecomputeSource.entries.filter { it != RecomputeSource.SYNC }) {
            val marksStore = InMemoryProgressMarksStore(
                ProgressMarks(
                    lastCelebratedLevel = 4,
                    initialized = true,
                    pendingTransition = PendingTransition(
                        source = source,
                        previousLevel = 4,
                        previousStreak = 0,
                        previousTodayQuestMet = false,
                        evaluationDate = today,
                    ),
                ),
            )
            // Room already reflects the interrupted write: the profile jumped from 4 to 24.
            val profileDao = FakePlayerProfileDao(PlayerProfile(level = 24))
            val dailyDao = FakeDailyProgressDao(emptyList())

            resolvePendingTransition(marksStore, profileDao, dailyDao)

            val marks = marksStore.read()
            assertNull("$source pending transition", marks.pendingTransition)
            // Reseeded silently to the values actually written, not reported as earned.
            assertEquals("$source baseline", 24, marks.lastCelebratedLevel)

            val repository = ProgressEventRepository(marksStore, profileDao, dailyDao)
            assertEquals(
                "$source produced a phantom event",
                emptyList<ProgressEvent>(),
                repository.pendingEvents.first(),
            )
        }
    }

    @Test
    fun interruptedSyncStreakBreakIsRecoveredNotLost() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingTransition = PendingTransition(
                    source = RecomputeSource.SYNC,
                    previousLevel = 4,
                    previousStreak = 14,
                    previousTodayQuestMet = false,
                    evaluationDate = today,
                ),
            ),
        )
        // Room already reflects the earned drop to zero — the one signal that is otherwise
        // unrecoverable once Room's previous state is overwritten.
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 4, currentStreak = 0))
        val dailyDao = FakeDailyProgressDao(emptyList())

        resolvePendingTransition(marksStore, profileDao, dailyDao)

        val marks = marksStore.read()
        assertNull(marks.pendingTransition)
        assertEquals(PendingStreakBreak(today, previousLength = 14), marks.pendingStreakBreak)
    }

    @Test
    fun crashBeforeRoomWriteResolvesAsNoOp() = runTest {
        // The write-ahead record landed, but the Room write it precedes never happened —
        // recovery must not fabricate a transition that never occurred.
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingTransition = PendingTransition(
                    source = RecomputeSource.SYNC,
                    previousLevel = 4,
                    previousStreak = 0,
                    previousTodayQuestMet = false,
                    evaluationDate = today,
                ),
            ),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 4, currentStreak = 0))
        val dailyDao = FakeDailyProgressDao(emptyList())

        resolvePendingTransition(marksStore, profileDao, dailyDao)

        val marks = marksStore.read()
        assertNull(marks.pendingTransition)
        assertEquals(4, marks.lastCelebratedLevel)
        assertNull(marks.pendingStreakBreak)
    }
}
