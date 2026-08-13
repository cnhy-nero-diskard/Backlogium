package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.ProgressEventRepository
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

    @Test
    fun acknowledgedEventDoesNotReappearIncludingAfterRepositoryRecreation() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 5))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val repository = ProgressEventRepository(marksStore, profileDao, dailyDao)

        val levelUp = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.LevelUp(4, 5), levelUp)
        repository.acknowledge(levelUp)
        assertTrue(repository.pendingEvents.first().isEmpty())

        val recreated = ProgressEventRepository(marksStore, profileDao, dailyDao)
        assertTrue(recreated.pendingEvents.first().isEmpty())
    }

    @Test
    fun unacknowledgedBackgroundEventSurvivesRepositoryRecreation() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 7))
        val dailyDao = FakeDailyProgressDao(emptyList())

        val firstProcess = ProgressEventRepository(marksStore, profileDao, dailyDao)
        assertEquals(ProgressEvent.LevelUp(4, 7), firstProcess.pendingEvents.first().single())

        val nextProcess = ProgressEventRepository(marksStore, profileDao, dailyDao)
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
        val repository = ProgressEventRepository(
            marksStore,
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

        val firstProcess = ProgressEventRepository(marksStore, profileDao, dailyDao)
        val milestone = firstProcess.pendingEvents.first().single()
        assertEquals(ProgressEvent.StreakMilestone(7), milestone)

        // Recreated before acknowledgement (e.g. Home was never composed) — still pending.
        val recreatedBeforeAck = ProgressEventRepository(marksStore, profileDao, dailyDao)
        assertEquals(milestone, recreatedBeforeAck.pendingEvents.first().single())

        recreatedBeforeAck.acknowledge(milestone)

        val recreatedAfterAck = ProgressEventRepository(marksStore, profileDao, dailyDao)
        assertTrue(recreatedAfterAck.pendingEvents.first().isEmpty())
    }

    @Test
    fun questEarnedYesterdayIsStillDeliveredToday() = runTest {
        val yesterday = today.minusDays(1)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val dailyDao = FakeDailyProgressDao(
            listOf(DailyProgress(date = yesterday.toString(), questMet = true)),
        )
        val repository = ProgressEventRepository(
            marksStore,
            FakePlayerProfileDao(PlayerProfile(level = 4)),
            dailyDao,
        )

        val event = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.QuestMet(yesterday), event)
    }

    @Test
    fun twoUnacknowledgedQuestDaysBothEventuallyDeliverOldestFirst() = runTest {
        val dayOne = today.minusDays(2)
        val dayTwo = today.minusDays(1)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val dailyDao = FakeDailyProgressDao(
            listOf(
                DailyProgress(date = dayOne.toString(), questMet = true),
                DailyProgress(date = dayTwo.toString(), questMet = true),
            ),
        )
        val repository = ProgressEventRepository(
            marksStore,
            FakePlayerProfileDao(PlayerProfile(level = 4)),
            dailyDao,
        )

        val first = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.QuestMet(dayOne), first)

        repository.acknowledge(first)

        val second = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.QuestMet(dayTwo), second)
    }

    @Test
    fun acknowledgeSurvivesConcurrentUnrelatedMarksWrites() = runBlocking {
        val pending = PendingStreakBreak(today, previousLength = 9)
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 0, initialized = true, pendingStreakBreak = pending),
        )
        val repository = ProgressEventRepository(
            marksStore,
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
