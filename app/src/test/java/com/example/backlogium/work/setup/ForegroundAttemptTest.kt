package com.example.backlogium.work.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The immutable foreground-attempt projection: its selection and admission order, its per-stage
 * settlement map, its injectable monotonic observation budget, and the pure per-stage settlement
 * decisions (`settlementReasonFor`). Settlement is per stage, never a work verdict and never a
 * cancellation; a later selected stage keeps being observed even after an earlier one settled.
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
        assertTrue("no stage has settled yet", attempt.stageSettlements.isEmpty())
        assertFalse(attempt.isSettled)
    }

    @Test
    fun defaultObservationBudgetIsMonotonicOneHundredTwentySeconds() {
        assertEquals(120_000L, foregroundAttempt(emptySet(), emptyList()).observationBudgetMs)
    }

    @Test
    fun queuedWaitingSettlesTheStageAndKeepsTheWaitingOperation() {
        val waiting = SetupOperationState.Waiting("waiting for connectivity")
        val reason = settlementReasonFor(waiting, detached = false, admissionDurable = true)

        assertEquals(ForegroundSettledReason.QUEUED, reason)
        // The operation itself is untouched: still waiting, never relabelled failed or skipped.
        assertTrue(waiting.isPending)
        assertFalse(waiting.isTerminal)
    }

    @Test
    fun retryBackoffSettlesTheStageWithoutManufacturingFailure() {
        val scheduled = SetupOperationState.RetryScheduled(attempt = 1, reason = "backoff")
        assertEquals(
            ForegroundSettledReason.BACKOFF,
            settlementReasonFor(scheduled, detached = false, admissionDurable = true),
        )
        assertTrue(scheduled.isPending)
        assertFalse(scheduled.isTerminal)
    }

    @Test
    fun terminalStateSettlesTheStageTerminal() {
        assertEquals(
            ForegroundSettledReason.TERMINAL,
            settlementReasonFor(SetupOperationState.Succeeded(), detached = false, admissionDurable = true),
        )
    }

    @Test
    fun runningInScreenStageStaysObservedWithinBudget() {
        val budget = 10L
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"), observationBudgetMs = budget)
        // No settlement reason for a running in-screen stage: the wait keeps observing.
        assertNull(settlementReasonFor(SetupOperationState.Running(SetupStageProgress(3, 10)), detached = false, admissionDurable = true))
        assertTrue(attempt.stageObserved("sync"))
        // The budget is measured monotonically and consumed inclusively at the budget value.
        assertTrue(attempt.observationBudgetElapsed(budget))
    }

    @Test
    fun budgetExpirySettlesTheStageButNeitherFailsNorCancelsTheOperation() {
        val running = SetupOperationState.Running(SetupStageProgress(3, 10))
        val attempt = foregroundAttempt(setOf("sync"), listOf("sync"), observationBudgetMs = 10L)
            .settleStage("sync", ForegroundSettledReason.BUDGET_EXPIRED)

        assertEquals(ForegroundSettledReason.BUDGET_EXPIRED, attempt.settlementOf("sync"))
        assertFalse(attempt.stageObserved("sync"))
        // Busy work is never turned into a terminal outcome or a cancellation by elapsed time alone.
        assertTrue(running is SetupOperationState.Running)
        assertTrue(running.isPending)
        assertFalse(running.isTerminal)
    }

    @Test
    fun detachedStageSettlesOnceAdmittedAndNotBefore() {
        assertEquals(
            ForegroundSettledReason.DETACHED,
            settlementReasonFor(
                SetupOperationState.Running(SetupStageProgress(1, 4)),
                detached = true,
                admissionDurable = true,
            ),
        )
        // Without a durable association there is no detached settlement — the wait keeps observing.
        assertNull(
            settlementReasonFor(SetupOperationState.Running(), detached = true, admissionDurable = false),
        )
    }

    @Test
    fun continueEndsTheWaitButKeepsRemainingAdmissionsInOrder() {
        val attempt = foregroundAttempt(
            selected = setOf("sync", "assets", "times"),
            registeredOrder = listOf("sync", "assets", "times"),
        ).continueLater()

        assertTrue(attempt.explicitContinued)
        assertTrue(attempt.isSettled)
        assertFalse(attempt.stageObserved("sync"))
        // Merely leaving does not deselect the stages that had not started yet…
        assertEquals(setOf("sync", "assets", "times"), attempt.selected)
        // …and already-selected remaining stages are still admitted in registered order.
        assertEquals(listOf("assets", "times"), attempt.remainingAdmissions(admitted = setOf("sync")))
    }

    @Test
    fun anEarlierStageSettlingDoesNotStopALaterStageBeingObserved() {
        val attempt = foregroundAttempt(
            selected = setOf("sync", "assets", "times"),
            registeredOrder = listOf("sync", "assets", "times"),
        ).settleStage("sync", ForegroundSettledReason.QUEUED)

        assertEquals(ForegroundSettledReason.QUEUED, attempt.settlementOf("sync"))
        // The whole attempt is NOT settled: the later stages still owe their own foreground waits.
        assertFalse(attempt.isSettled)
        assertTrue(attempt.stageObserved("assets"))
        assertTrue(attempt.stageObserved("times"))
        assertEquals(listOf("assets", "times"), attempt.remainingAdmissions(admitted = setOf("sync")))
    }

    @Test
    fun laterStagesAreAdmittedInOrderAndEachStaysObservedUntilItSettles() {
        val attempt = foregroundAttempt(
            selected = setOf("sync", "assets", "times"),
            registeredOrder = listOf("sync", "assets", "times"),
        )
            .settleStage("sync", ForegroundSettledReason.TERMINAL)
            .settleStage("assets", ForegroundSettledReason.DETACHED)

        assertFalse(attempt.isSettled)
        assertTrue(attempt.stageObserved("times"))
        // Late settlement of the last stage completes the whole attempt.
        val done = attempt.settleStage("times", ForegroundSettledReason.BUDGET_EXPIRED)
        assertTrue(done.isSettled)
        assertFalse(done.stageObserved("times"))
    }

    @Test
    fun firstSettlementWinsPerStage() {
        val attempt = foregroundAttempt(setOf("a"), listOf("a"))
            .settleStage("a", ForegroundSettledReason.QUEUED)
        val later = attempt.settleStage("a", ForegroundSettledReason.TERMINAL)
        assertEquals(ForegroundSettledReason.QUEUED, later.settlementOf("a"))
    }

    @Test
    fun detachedRunningWorkWithoutDurableAssociationDoesNotClaimDetachedAdmission() {
        assertNull(
            settlementReasonFor(SetupOperationState.Running(), detached = true, admissionDurable = false),
        )
    }

    @Test
    fun missingOperationSettlesWithExplicitRecoveryInsteadOfWaitingForBudget() {
        assertEquals(
            ForegroundSettledReason.RECOVERY_REQUIRED,
            settlementReasonFor(
                SetupOperationState.RecoveryRequired("Work no longer available"),
                detached = false,
                admissionDurable = false,
            ),
        )
    }

    @Test
    fun detachedTerminalWorkIsTerminalNotPendingDetachedAdmission() {
        // Terminal always wins over a detached-pending admission.
        assertEquals(
            ForegroundSettledReason.TERMINAL,
            settlementReasonFor(SetupOperationState.Succeeded(), detached = true, admissionDurable = true),
        )
    }

    @Test
    fun removedStageIdsDoNotRemainInTheAttemptSelection() {
        val attempt = foregroundAttempt(setOf("sync", "removed"), listOf("sync", "assets"))
        assertEquals(setOf("sync"), attempt.selected)
        assertEquals(listOf("sync"), attempt.admissionOrder)
    }
}