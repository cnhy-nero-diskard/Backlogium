package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.repo.ProgressEventRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressEventRepositoryTest {
    private val today = LocalDate.parse("2026-08-13")
    private val time = object : TimeProvider {
        override fun nowMillis(): Long = 0L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = this@ProgressEventRepositoryTest.today
    }

    @Test
    fun acknowledgedEventDoesNotReappearIncludingAfterRepositoryRecreation() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 5))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val repository = ProgressEventRepository(marksStore, profileDao, dailyDao, time)

        val levelUp = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.LevelUp(4, 5), levelUp)
        repository.acknowledge(levelUp)
        assertTrue(repository.pendingEvents.first().isEmpty())

        val recreated = ProgressEventRepository(marksStore, profileDao, dailyDao, time)
        assertTrue(recreated.pendingEvents.first().isEmpty())
    }

    @Test
    fun unacknowledgedBackgroundEventSurvivesRepositoryRecreation() = runTest {
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 4, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 7))
        val dailyDao = FakeDailyProgressDao(emptyList())

        val firstProcess = ProgressEventRepository(marksStore, profileDao, dailyDao, time)
        assertEquals(ProgressEvent.LevelUp(4, 7), firstProcess.pendingEvents.first().single())

        val nextProcess = ProgressEventRepository(marksStore, profileDao, dailyDao, time)
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
            time,
        )

        val event = repository.pendingEvents.first().single()
        assertEquals(ProgressEvent.StreakBroken(12), event)
        repository.acknowledge(event)

        val marks = marksStore.read()
        assertEquals(null, marks.pendingStreakBreak)
        assertEquals(today, marks.lastStreakBrokenDate)
        assertTrue(repository.pendingEvents.first().isEmpty())
    }
}
