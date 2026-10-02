package com.example.backlogium.work.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-stage operation states and the pending/terminal split they exist for: waiting, running,
 * and retry-scheduled work is pending, never a terminal outcome, and never a stand-in for the
 * other two.
 */
class SetupOperationStateTest {

    @Test
    fun waitingIsPendingButNotTerminalAndNotARetry() {
        val waiting = SetupOperationState.Waiting("no network")
        assertTrue(waiting.isPending)
        assertFalse(waiting.isTerminal)
    }

    @Test
    fun retryScheduledIsPendingWithAnAttemptAndReason() {
        val scheduled = SetupOperationState.RetryScheduled(attempt = 2, reason = "transient fetch failure")
        assertTrue(scheduled.isPending)
        assertFalse(scheduled.isTerminal)
        assertEquals(2, scheduled.attempt)
        assertEquals("transient fetch failure", scheduled.reason)
    }

    @Test
    fun terminalStatesAreNotPending() {
        listOf(
            SetupOperationState.Succeeded(),
            SetupOperationState.Failed(),
            SetupOperationState.Cancelled,
            SetupOperationState.Skipped,
        ).forEach { state ->
            assertTrue("$state must be terminal", state.isTerminal)
            assertFalse("$state must not be pending", state.isPending)
        }
    }

    @Test
    fun neverRunIsNeitherPendingNorTerminal() {
        assertFalse(SetupOperationState.NeverRun.isPending)
        assertFalse(SetupOperationState.NeverRun.isTerminal)
    }

    @Test
    fun recoveryRequiredIsNeitherPendingNorTerminal() {
        val recovery = SetupOperationState.RecoveryRequired(
            "the pending operation is no longer available",
        )
        assertFalse(recovery.isPending)
        assertFalse(recovery.isTerminal)
        assertEquals("the pending operation is no longer available", recovery.reason)
    }

    @Test
    fun succeededCarriesOptionalDomainDetail() {
        assertNull(SetupOperationState.Succeeded().detail)
        assertEquals("12 covers stored", SetupOperationState.Succeeded("12 covers stored").detail)
    }

    @Test
    fun runningProgressIsOptional() {
        assertNull(SetupOperationState.Running().progress)
        val running = SetupOperationState.Running(SetupStageProgress(5, 10))
        assertEquals(SetupStageProgress(5, 10), running.progress)
    }

    @Test
    fun zeroTotalsAreNeverDeterminate() {
        assertFalse(SetupStageProgress(0, 0).isDeterminate)
        assertFalse(SetupStageProgress(5, 0).isDeterminate)
        assertTrue(SetupStageProgress(0, 10).isDeterminate)
    }
}