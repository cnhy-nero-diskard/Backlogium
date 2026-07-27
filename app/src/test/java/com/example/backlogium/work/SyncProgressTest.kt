package com.example.backlogium.work

import androidx.work.WorkInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the shell's sync cue: which WorkManager states count as "syncing", and the
 * minimum-visible latch that keeps a fast sync from flickering.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncProgressTest {

    @Test
    fun periodicWorkMerelyEnqueued_isNotInProgress() {
        // The spins-forever bug, asserted directly: a PeriodicWorkRequest sits in ENQUEUED for
        // the whole 15 minutes *between* runs. If that counted, the indicator would never stop.
        assertFalse(
            isSyncInProgress(
                oneTimeStates = emptyList(),
                periodicStates = listOf(WorkInfo.State.ENQUEUED),
            ),
        )
    }

    @Test
    fun periodicWorkRunning_isInProgress() {
        assertTrue(
            isSyncInProgress(
                oneTimeStates = emptyList(),
                periodicStates = listOf(WorkInfo.State.RUNNING),
            ),
        )
    }

    @Test
    fun manualWorkEnqueued_isInProgress() {
        // An expedited manual sync should show feedback from the tap, not from the first byte.
        assertTrue(
            isSyncInProgress(
                oneTimeStates = listOf(WorkInfo.State.ENQUEUED),
                periodicStates = listOf(WorkInfo.State.ENQUEUED),
            ),
        )
    }

    @Test
    fun terminalStates_areNotInProgress() {
        assertFalse(
            isSyncInProgress(
                oneTimeStates = listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED),
                periodicStates = listOf(WorkInfo.State.ENQUEUED),
            ),
        )
    }

    @Test
    fun holdTrue_shortPulseStaysVisibleForTheFullMinimum() = runTest {
        val clock = testScheduler
        val source = MutableStateFlow(false)
        val seen = mutableListOf<Pair<Long, Boolean>>()
        val collector = launch {
            source.holdTrue(HOLD).collect { seen += clock.currentTime to it }
        }

        advanceUntilIdle()
        source.value = true
        advanceTimeBy(50)
        source.value = false // a 50ms sync: gone almost as soon as it appeared

        // Still visible well past the point the raw flow went false.
        advanceTimeBy(HOLD - 1)
        assertEquals(true, seen.last().second)

        advanceUntilIdle()
        assertEquals(listOf(false, true, false), seen.map { it.second })
        // Visible for the 50ms pulse plus the full hold, not for 50ms.
        assertEquals(50L + HOLD, seen.last().first - seen[1].first)
        collector.cancel()
    }

    @Test
    fun holdTrue_longSyncIsNotExtendedBeyondItsEndPlusTheHold() = runTest {
        val clock = testScheduler
        val source = MutableStateFlow(false)
        val seen = mutableListOf<Pair<Long, Boolean>>()
        val collector = launch {
            source.holdTrue(HOLD).collect { seen += clock.currentTime to it }
        }

        advanceUntilIdle()
        source.value = true
        advanceTimeBy(10_000) // a genuinely long sync
        source.value = false
        advanceUntilIdle()

        assertEquals(listOf(false, true, false), seen.map { it.second })
        assertEquals(10_000L + HOLD, seen.last().first - seen[1].first)
        collector.cancel()
    }

    @Test
    fun holdTrue_syncStartingDuringTheHold_readsAsOneContinuousRun() = runTest {
        val source = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val collector = launch { source.holdTrue(HOLD).collect { seen += it } }

        advanceUntilIdle()
        source.value = true
        advanceTimeBy(100)
        source.value = false
        advanceTimeBy(HOLD / 2) // second sync starts before the hold expires
        source.value = true
        advanceTimeBy(100)
        source.value = false
        advanceUntilIdle()

        // No false in between: the indicator never blinked off.
        assertEquals(listOf(false, true, false), seen)
        collector.cancel()
    }

    @Test
    fun holdTrue_initialFalseIsEmittedImmediately() = runTest {
        // Opening the app with nothing running must not leave the state undecided for the hold.
        val emitted = flowOf(false).holdTrue(HOLD).toList()
        assertEquals(listOf(false), emitted)
        assertEquals(0L, testScheduler.currentTime)
    }

    private companion object {
        const val HOLD = 700L
    }
}
