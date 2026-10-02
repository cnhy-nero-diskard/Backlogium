package com.example.backlogium.work.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The immutable foreground-attempt projection: its selection and admission order, its injectable
 * monotonic observation budget, and the pure settlement decisions it makes from one stage's
 * operation state. Settlement never mutates an operation, never cancels one, and never turns busy
 * or elapsed work into a terminal failure.
 */
class ForegroundAttemptTest {

    @Test
    fun admissionOrderIsRegisteredOrderFilteredToSelection() {
        val attempt = foregroundAttempt(
            selected = setOf("b", "a"),
            registeredOrder = listOf("a", "b", "c", "gone"),
        )
        assertEquals(setOf("a", "b"), attempt.selected)
        // Unknown ids are dropped at construction and order follows the registry, not the set.
        assertEquals(listOf("a", "b"), attempt.admissionOrder)
        assertFalse(attempt.isSettled)
    }

    @Test
    fun defaultObservationBudgetIsMonotonicOneHundredTwentySeconds() {
        assertEquals(120_000L, foregroundAttempt(emptySet(), emptyList()).observationBudgetMs)
    }

    @Test
    fun queuedWaitingSettlesTheForegroundAndKeepsTheWaitingOperation() {
        val waiting = SetupOperationState.Waiting("waiting for connectivity")
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"))
            .settledFor(state = waiting, detached = false, elapsedMonotonicMs = 0L)

        assertEquals(ForegroundSettledReason.QUEUED, attempt.settledReason)
        // The operation itself is untouched: still waiting, never relabelled failed or skipped.
        assertTrue(waiting.isPending)
        assertFalse(waiting.isTerminal)
    }

    @Test
    fun retryBackoffSettlesTheForegroundWithoutManufacturingFailure() {
        val scheduled = SetupOperationState.RetryScheduled(attempt = 1, reason = "backoff")
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"))
            .settledFor(state = scheduled, detached = false, elapsedMonotonicMs = 0L)

        assertEquals(ForegroundSettledReason.BACKOFF, attempt.settledReason)
        assertTrue(scheduled.isPending)
        assertFalse(scheduled.isTerminal)
    }

    @Test
    fun terminalStateSettlesTerminal() {
        val attempt = foregroundAttempt(setOf("a"), listOf("a"))
            .settledFor(
                state = SetupOperationState.Succeeded(),
                detached = false,
                elapsedMonotonicMs = 0L,
            )
        assertEquals(ForegroundSettledReason.TERMINAL, attempt.settledReason)
    }

    @Test
    fun runningInScreenStageStaysObservedWithinBudget() {
        val budget = 10L
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"), observationBudgetMs = budget)

        val withinBudget = attempt.settledFor(
            state = SetupOperationState.Running(SetupStageProgress(3, 10)),
            detached = false,
            elapsedMonotonicMs = 5L,
        )
        assertFalse(withinBudget.isSettled)
        // The budget is measured monotonically and consumed inclusively at the budget value.
        assertTrue(attempt.observationBudgetElapsed(budget))
    }

    @Test
    fun budgetExpirySettlesButNeitherFailsNorCancelsTheOperation() {
        val running = SetupOperationState.Running(SetupStageProgress(3, 10))
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"), observationBudgetMs = 10L)

        val expired = attempt.settledFor(running, detached = false, elapsedMonotonicMs = 60_000L)

        assertEquals(ForegroundSettledReason.BUDGET_EXPIRED, expired.settledReason)
        // Busy work is never turned into a terminal outcome or a cancellation by elapsed time alone.
        assertTrue(running is SetupOperationState.Running)
        assertTrue(running.isPending)
        assertFalse(running.isTerminal)
        assertEquals(SetupStageProgress(3, 10), (running as SetupOperationState.Running).progress)
    }

    @Test
    fun budgetExpiryCannotMutateTheOriginalAttempt() {
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"), observationBudgetMs = 10L)
        attempt.settledFor(
            state = SetupOperationState.Running(),
            detached = false,
            elapsedMonotonicMs = 60_000L,
        )
        // The attempt is immutable: settling produced a new value and left the original active.
        assertTrue(attempt.isActive)
        assertNull(attempt.settledReason)
    }

    @Test
    fun detachedStageSettlesOnceAdmittedAndNotBefore() {
        val notAdmitted = foregroundAttempt(setOf("assets"), listOf("assets"))
            .settledFor(state = SetupOperationState.NeverRun, detached = true, elapsedMonotonicMs = 0L)
        assertFalse(notAdmitted.isSettled)

        val running = foregroundAttempt(setOf("assets"), listOf("assets"))
            .settledFor(
                state = SetupOperationState.Running(SetupStageProgress(1, 4)),
                detached = true,
                elapsedMonotonicMs = 0L,
                admissionDurable = true,
            )
        assertEquals(ForegroundSettledReason.DETACHED, running.settledReason)
    }

    @Test
    fun continueEndsTheWaitButKeepsRemainingAdmissionsInOrder() {
        val attempt = foregroundAttempt(
            selected = setOf("sync", "assets", "times"),
            registeredOrder = listOf("sync", "assets", "times"),
        ).continueLater()

        assertEquals(ForegroundSettledReason.CONTINUE, attempt.settledReason)
        // Merely leaving does not deselect the stages that had not started yet…
        assertEquals(setOf("sync", "assets", "times"), attempt.selected)
        // …and already-selected remaining stages are still admitted in registered order.
        assertEquals(listOf("assets", "times"), attempt.remainingAdmissions(admitted = setOf("sync")))
    }

    @Test
    fun queuedSettlementStillAdmitsLaterIndependentStagesInOrder() {
        val attempt = foregroundAttempt(
            selected = setOf("sync", "assets", "times"),
            registeredOrder = listOf("sync", "assets", "times"),
        ).settledFor(
            state = SetupOperationState.Waiting("queued behind another poll"),
            detached = false,
            elapsedMonotonicMs = 0L,
        )

        assertEquals(ForegroundSettledReason.QUEUED, attempt.settledReason)
        assertEquals(listOf("assets", "times"), attempt.remainingAdmissions(admitted = setOf("sync")))
    }

    @Test
    fun firstSettlementWins() {
        val attempt = foregroundAttempt(setOf("a"), listOf("a"))
            .settledFor(
                state = SetupOperationState.Waiting("queued"),
                detached = false,
                elapsedMonotonicMs = 0L,
            )
        val later = attempt.settledFor(
            state = SetupOperationState.Succeeded(),
            detached = false,
            elapsedMonotonicMs = 0L,
        )
        assertEquals(ForegroundSettledReason.QUEUED, later.settledReason)
    }

    @Test
    fun detachedRunningWorkWithoutDurableAssociationDoesNotClaimDetachedAdmission() {
        val attempt = foregroundAttempt(setOf("assets"), listOf("assets"))
            .settledFor(SetupOperationState.Running(), detached = true, elapsedMonotonicMs = 0L)
        assertFalse(attempt.isSettled)
    }

    @Test
    fun missingOperationSettlesWithExplicitRecoveryInsteadOfWaitingForBudget() {
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"))
            .settledFor(
                SetupOperationState.RecoveryRequired("Work no longer available"),
                detached = false,
                elapsedMonotonicMs = 0L,
            )
        assertEquals(ForegroundSettledReason.RECOVERY_REQUIRED, attempt.settledReason)
    }

    @Test
    fun detachedTerminalWorkIsTerminalNotPendingDetachedAdmission() {
        val attempt = foregroundAttempt(setOf("assets"), listOf("assets"))
            .settledFor(
                SetupOperationState.Succeeded(),
                detached = true,
                elapsedMonotonicMs = 0L,
                admissionDurable = true,
            )
        assertEquals(ForegroundSettledReason.TERMINAL, attempt.settledReason)
    }

    @Test
    fun removedStageIdsDoNotRemainInTheAttemptSelection() {
        val attempt = foregroundAttempt(setOf("sync", "removed"), listOf("sync", "assets"))
        assertEquals(setOf("sync"), attempt.selected)
        assertEquals(listOf("sync"), attempt.admissionOrder)
    }
}
