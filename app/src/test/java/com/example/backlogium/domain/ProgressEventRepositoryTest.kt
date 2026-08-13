package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressEventRepositoryTest {
    private val today = LocalDate.parse("2026-08-13")
    private val coordinator = ProgressTransitionCoordinator()

    @Test
    fun acknowledgedEventDoesNotReappearIncludingAfterRepositoryRecreation() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 5))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val repository = testRepository(marksStore, coordinator, profileDao, dailyDao)

        val levelUp = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.LevelUp(4, 5), levelUp)
        repository.acknowledge(levelUp)
        assertTrue(repository.pendingEvents.first().isEmpty())

        val recreated = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertTrue(recreated.pendingEvents.first().isEmpty())
    }

    @Test
    fun unacknowledgedBackgroundEventSurvivesRepositoryRecreation() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 7))
        val dailyDao = FakeDailyProgressDao(emptyList())

        val firstProcess = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertEquals(ProgressEvent.LevelUp(4, 7), firstProcess.pendingEvents.first().single())

        val nextProcess = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertEquals(ProgressEvent.LevelUp(4, 7), nextProcess.pendingEvents.first().single())
    }

    @Test
    fun pendingStreakBreakRetainsLostLengthUntilAcknowledged() = runTest {
        val pending = PendingStreakBreak(today, previousLength = 12)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingStreakBreak = pending,
            ),
        )
        val repository = testRepository(
            marksStore,
            coordinator,
            FakePlayerProfileDao(PlayerProfile(level = 4, currentStreak = 0)),
            FakeDailyProgressDao(emptyList()),
        )

        val event = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.StreakBroken(12), event)
        repository.acknowledge(event)

        val marks = marksStore.read()
        assertEquals(null, marks.pendingStreakBreak)
        assertEquals(today, marks.lastStreakBrokenDate)
        assertTrue(repository.pendingEvents.first().isEmpty())
    }

    @Test
    fun streakMilestoneSurvivesRepositoryRecreationAndAckPreventsReplay() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, lastCelebratedStreakMilestone = 0, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(currentStreak = 7))
        val dailyDao = FakeDailyProgressDao(emptyList())

        val firstProcess = testRepository(marksStore, coordinator, profileDao, dailyDao)
        val milestone = firstProcess.pendingEvents.first().single()
        assertEquals(ProgressEvent.StreakMilestone(7), milestone)

        // Recreated before acknowledgement (e.g. Home was never composed) — still pending.
        val recreatedBeforeAck = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertEquals(milestone, recreatedBeforeAck.pendingEvents.first().single())

        recreatedBeforeAck.acknowledge(milestone)

        val recreatedAfterAck = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertTrue(recreatedAfterAck.pendingEvents.first().isEmpty())
    }

    @Test
    fun earnedQuestDateStaysDeliverableRegardlessOfHowManyDaysPass() = runTest {
        // The pending date is the event's identity. Nothing here consults the current date, which
        // is why a rollover cannot change or drop it.
        val earnedOn = today.minusDays(1)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingQuestDates = setOf(earnedOn),
            ),
        )
        val repository = testRepository(
            marksStore,
            coordinator,
            FakePlayerProfileDao(PlayerProfile(level = 4)),
        )

        assertEquals(
            ProgressEvent.QuestMet(earnedOn),
            repository.pendingEvents.first().single(),
        )
    }

    @Test
    fun twoEarnedQuestDatesDeliverIndependentlyOldestFirst() = runTest {
        val dayOne = today.minusDays(2)
        val dayTwo = today.minusDays(1)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingQuestDates = setOf(dayTwo, dayOne),
            ),
        )
        val repository = testRepository(
            marksStore,
            coordinator,
            FakePlayerProfileDao(PlayerProfile(level = 4)),
        )

        val first = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.QuestMet(dayOne), first)

        // Acknowledging the earlier day removes exactly that date and reveals the later one; the
        // later one was never at risk of being obscured, only of being delivered second.
        repository.acknowledge(first)
        assertEquals(setOf(dayTwo), marksStore.read().pendingQuestDates)

        val second = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.QuestMet(dayTwo), second)

        repository.acknowledge(second)
        assertEquals(emptySet<LocalDate>(), marksStore.read().pendingQuestDates)
        assertEquals(dayTwo, marksStore.read().lastQuestCelebratedDate)
        assertTrue(repository.pendingEvents.first().isEmpty())
    }

    @Test
    fun acknowledgingOneQuestDateDoesNotAcknowledgeAnother() = runTest {
        val older = today.minusDays(3)
        val newer = today.minusDays(1)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(
                lastCelebratedLevel = 4,
                initialized = true,
                pendingQuestDates = setOf(older, newer),
            ),
        )
        val repository = testRepository(
            marksStore,
            coordinator,
            FakePlayerProfileDao(PlayerProfile(level = 4)),
        )

        // Acknowledging the *newer* date out of order must not sweep up the older one, and
        // re-acknowledging it must be a no-op rather than consuming the remaining date.
        repository.acknowledge(ProgressEvent.QuestMet(newer))
        repository.acknowledge(ProgressEvent.QuestMet(newer))

        assertEquals(setOf(older), marksStore.read().pendingQuestDates)
        assertEquals(
            ProgressEvent.QuestMet(older),
            repository.pendingEvents.first().single(),
        )
    }

    @Test
    fun historicalMetQuestRowsAreNeverDeliverableWithoutAnEarnedPendingDate() = runTest {
        // Rows that predate progress-event tracking, or that a recompute flipped to met: evidence of
        // nothing. With no earned pending date, there is nothing to deliver.
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress(date = today.minusDays(9).toString(), questMet = true),
                DailyProgress(date = today.minusDays(2).toString(), questMet = true),
                DailyProgress(date = today.toString(), questMet = true),
            ),
        )
        val repository = testRepository(
            marksStore,
            coordinator,
            FakePlayerProfileDao(PlayerProfile(level = 4)),
            dailyDao,
        )

        assertTrue(repository.pendingEvents.first().isEmpty())
    }

    @Test
    fun acknowledgeSurvivesConcurrentUnrelatedMarksWrites() = runBlocking {
        val pending = PendingStreakBreak(today, previousLength = 9)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 0, initialized = true, pendingStreakBreak = pending),
        )
        val repository = testRepository(
            marksStore,
            coordinator,
            FakePlayerProfileDao(PlayerProfile(level = 0, currentStreak = 0)),
            FakeDailyProgressDao(emptyList()),
        )
        val event = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.StreakBroken(9), event)

        // Race a genuine acknowledge() against many concurrent recompute-shaped marks writes that
        // each bump an unrelated field via the same atomic `update` — none of them ever read the
        // outer `marks` snapshot above, so neither the acknowledgement nor any single increment
        // should be able to clobber the other.
        val concurrentWriters = 200
        coroutineScope {
            val ackJob = async(Dispatchers.Default) { repository.acknowledge(event) }
            val incrementJobs = (1..concurrentWriters).map {
                async(Dispatchers.Default) {
                    marksStore.update { it.copy(lastCelebratedLevel = it.lastCelebratedLevel + 1) }
                }
            }
            awaitAll(ackJob, *incrementJobs.toTypedArray())
        }

        val finalMarks = marksStore.read()
        assertNull(finalMarks.pendingStreakBreak)
        assertEquals(today, finalMarks.lastStreakBrokenDate)
        assertEquals(concurrentWriters, finalMarks.lastCelebratedLevel)
        assertTrue(repository.pendingEvents.first().none { it is ProgressEvent.StreakBroken })
    }
}
