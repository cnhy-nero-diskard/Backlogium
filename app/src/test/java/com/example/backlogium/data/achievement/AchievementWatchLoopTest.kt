package com.example.backlogium.data.achievement

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementWatchLoopTest {
    @Test fun unchangedTicksKeepSelectedCadenceAndNeverOverlapOrCatchUp() = runTest {
        var active = true
        var interval = 60_000L
        val times = mutableListOf<Long>()
        val job = launch {
            achievementWatchLoop({ active }, { interval }) {
                times += testScheduler.currentTime
                delay(5_000) // Request latency is not an overlapping timer.
                AchievementWatchTick(true)
            }
        }
        runCurrent()
        assertEquals(listOf(0L), times)
        advanceTimeBy(64_999); runCurrent()
        assertEquals(1, times.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L, 65_000L), times)
        interval = 30_000L
        advanceTimeBy(35_000); runCurrent()
        assertEquals(listOf(0L, 65_000L, 100_000L), times)
        active = false
        job.cancelAndJoin()
        advanceTimeBy(300_000)
        assertEquals(3, times.size)
    }

    @Test fun failureBackoffRespectsServerDeadlineThenResetsAfterSuccess() = runTest {
        var ticks = 0
        val times = mutableListOf<Long>()
        achievementWatchLoop({ ticks < 5 }, { 30_000 }) {
            times += testScheduler.currentTime
            when (++ticks) {
                1 -> AchievementWatchTick(false)
                2 -> AchievementWatchTick(false, retryDelayMillis = 300_000)
                else -> AchievementWatchTick(true)
            }
        }
        assertEquals(listOf(0L, 60_000L, 360_000L, 390_000L, 420_000L), times)
    }

    @Test fun cancellationDuringFetchCannotScheduleAnotherTick() = runTest {
        var calls = 0
        val job = launch { achievementWatchLoop({ true }, { 30_000 }) { calls++; awaitCancellation() } }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(600_000)
        assertEquals(1, calls)
    }
}
