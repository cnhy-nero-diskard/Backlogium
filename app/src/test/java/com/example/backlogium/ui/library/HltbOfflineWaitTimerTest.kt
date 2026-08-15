package com.example.backlogium.ui.library

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbOfflineWaitTimerTest {

    @Test
    fun `offline wait cancels only after thirty seconds`() = runTest {
        val ticks = mutableListOf<Int>()
        var timedOut = false

        val timer = launch {
            runHltbOfflineWaitTimer(ticks::add) { timedOut = true }
        }
        runCurrent()

        assertEquals(30, ticks.last())
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(1, ticks.last())
        assertFalse(timedOut)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, ticks.last())
        assertTrue(timedOut)
        timer.join()
    }

    @Test
    fun `cancelling offline wait prevents timeout callback`() = runTest {
        var timedOut = false
        val timer = launch {
            runHltbOfflineWaitTimer({}, { timedOut = true })
        }
        runCurrent()

        timer.cancel()
        timer.join()
        advanceTimeBy(30_000)
        runCurrent()

        assertFalse(timedOut)
    }
}
