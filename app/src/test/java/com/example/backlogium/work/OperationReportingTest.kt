package com.example.backlogium.work

import androidx.work.Data
import androidx.work.WorkInfo
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OperationReportingTest {
    private val id = UUID.randomUUID()
    private fun info(state: WorkInfo.State, data: Data = Data.EMPTY, attempts: Int = 0) =
        WorkInfo(id, state, setOf("setup-account:3"),
            if (state.isFinished) data else Data.EMPTY, if (!state.isFinished) data else Data.EMPTY, attempts)

    @Test fun `states preserve retry cancellation and coarse upgrade payloads`() {
        assertEquals(OperationStatus.QUEUED, operationProgress(info(WorkInfo.State.BLOCKED), 3)?.status)
        assertEquals(OperationStatus.RETRYING, operationProgress(info(WorkInfo.State.ENQUEUED, attempts = 1), 3)?.status)
        assertEquals(OperationStatus.CANCELLED, operationProgress(info(WorkInfo.State.CANCELLED), 3)?.status)
        assertEquals(OperationStatus.FAILED, operationProgress(info(WorkInfo.State.FAILED), 3)?.status)
        assertNull(operationProgress(info(WorkInfo.State.RUNNING), 3)?.counts)
    }

    @Test fun `mixed terminal attempts are partial and old operation and account payloads are rejected`() {
        val data = operationData(id.toString(), 3, OperationPhase.ACHIEVEMENTS, OperationCounts(4, 4, 1, 1), 0)
        val result = operationProgress(info(WorkInfo.State.SUCCEEDED, data), 3)!!
        assertEquals(OperationStatus.PARTIAL, result.status)
        assertEquals(0, result.updated)
        assertEquals(4, result.counts?.processed)
        assertNull(operationProgress(info(WorkInfo.State.SUCCEEDED, data), 4))
        val stale = operationData(UUID.randomUUID().toString(), 3, OperationPhase.PREPARING)
        assertNull(operationProgress(info(WorkInfo.State.RUNNING, stale), 3))
        val mismatchedTag = operationData(id.toString(), 4, OperationPhase.PREPARING)
        assertNull(operationProgress(info(WorkInfo.State.RUNNING, mismatchedTag), 3))
    }

    @Test fun `execution identity retains its WorkManager request identity`() {
        val executionId = "3:execution"
        val progress = operationProgress(info(
            WorkInfo.State.RUNNING,
            operationData(id.toString(), 3, OperationPhase.ACHIEVEMENTS, executionId = executionId),
        ), 3)!!

        assertEquals(executionId, progress.id)
        assertEquals(id.toString(), progress.workId)
    }

    @Test fun `pending work from an older build keeps coarse progress only for its original account`() {
        val legacy = WorkInfo(id, WorkInfo.State.ENQUEUED, emptySet())
        assertEquals(OperationStatus.QUEUED, operationProgress(legacy, 0)?.status)
        assertNull(operationProgress(legacy, 0)?.counts)
        assertNull(operationProgress(legacy, 1))
    }

    @Test fun `known constraint stops wait while unknown scheduling stays queued`() {
        val waiting = WorkInfo(id, WorkInfo.State.ENQUEUED, setOf("setup-account:3"),
            runAttemptCount = 1, stopReason = WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        assertEquals(OperationStatus.WAITING, operationProgress(waiting, 3)?.status)
        val unknown = WorkInfo(id, WorkInfo.State.ENQUEUED, setOf("setup-account:3"),
            runAttemptCount = 1, stopReason = WorkInfo.STOP_REASON_UNKNOWN)
        assertEquals(OperationStatus.QUEUED, operationProgress(unknown, 3)?.status)
    }

    @Test fun `committed library with all inline achievements failing is still partially successful`() {
        val data = Data.Builder().putAll(operationData(id.toString(), 3, OperationPhase.SAVING,
            OperationCounts(2, 2, 0, 2))).putString(SteamSyncWorker.KEY_POLL_RESULT, SteamSyncWorker.POLL_COMMITTED).build()
        assertEquals(OperationStatus.PARTIAL, operationProgress(info(WorkInfo.State.SUCCEEDED, data), 3)?.status)
    }

    @Test fun `usable player data with failed catalogue components remains partial when all games are attempted`() {
        val data = Data.Builder().putAll(operationData(
            id.toString(), 3, OperationPhase.ACHIEVEMENTS, OperationCounts(1, 1, failed = 1),
        )).putBoolean(OP_PARTIAL, true).build()

        assertEquals(OperationStatus.PARTIAL, operationProgress(info(WorkInfo.State.SUCCEEDED, data), 3)?.status)
    }

    @Test fun `phase without counts is indeterminate and completed batches flush throttled writes`() = runTest {
        var now = 0L
        var writes = 0
        val reporter = OperationReporter { now }
        reporter.report(OperationPhase.PREPARING, null) { writes++ }
        reporter.report(OperationPhase.ACHIEVEMENTS, OperationCounts(0, 4)) { writes++ }
        reporter.report(OperationPhase.ACHIEVEMENTS, OperationCounts(1, 4)) { writes++ }
        assertEquals(2, writes)
        now = 300
        reporter.report(OperationPhase.ACHIEVEMENTS, OperationCounts(2, 4)) { writes++ }
        reporter.report(OperationPhase.ACHIEVEMENTS, OperationCounts(4, 4)) { writes++ }
        assertEquals(4, writes)
        assertNull(operationProgress(info(WorkInfo.State.RUNNING,
            operationData(id.toString(), 3, OperationPhase.FETCHING_LIBRARY)), 3)?.counts)
    }

    @Test fun `a newer periodic result survives its sleeping schedule and does not reveal an older manual result`() {
        val manual = TimedOperation(OperationProgress("manual", OperationStatus.SUCCEEDED), startedAt = 100)
        val periodic = StoredOperationReport(
            workId = id.toString(), generation = 3, startedAt = 200,
            progress = OperationProgress("3:periodic-cycle", OperationStatus.PARTIAL,
                OperationPhase.ACHIEVEMENTS, OperationCounts(4, 4, failed = 1), updated = 2),
        )
        val sleeping = WorkInfo(id, WorkInfo.State.ENQUEUED, emptySet(), runAttemptCount = 0)

        val selected = latestOperation(listOfNotNull(manual, periodicOperation(sleeping, 3, periodic)))

        assertEquals("3:periodic-cycle", selected?.id)
        assertEquals(OperationStatus.PARTIAL, selected?.status)
        assertEquals(2, selected?.updated)
        assertNull("a schedule that has never run is not itself an operation",
            periodicOperation(WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet()), 3, null))
    }

    @Test fun `a replacement periodic request does not inherit the prior request report`() {
        val priorId = UUID.randomUUID()
        val report = StoredOperationReport(
            workId = priorId.toString(), generation = 3, startedAt = 200,
            progress = OperationProgress("3:prior-cycle", OperationStatus.PARTIAL),
        )
        val replacement = WorkInfo(UUID.randomUUID(), WorkInfo.State.ENQUEUED, emptySet())

        assertNull(periodicOperation(replacement, 3, report))
        assertNull("a removed request cannot leave a stored execution active", periodicOperation(null, 3, report))
    }

    @Test fun `periodic backoff retains the attempted cycle identity and reports retrying`() {
        val periodicId = UUID.randomUUID()
        val report = StoredOperationReport(
            workId = periodicId.toString(), generation = 3, startedAt = 300,
            progress = OperationProgress("3:cycle", OperationStatus.RUNNING,
                OperationPhase.ACHIEVEMENTS, OperationCounts(2, 5, failed = 1)),
        )
        val retry = WorkInfo(periodicId, WorkInfo.State.ENQUEUED, emptySet(), runAttemptCount = 1)

        val selected = periodicOperation(retry, 3, report)?.progress

        assertEquals("3:cycle", selected?.id)
        assertEquals(OperationStatus.RETRYING, selected?.status)
        assertEquals(2, selected?.counts?.processed)
        assertNull(periodicOperation(retry, 4, report))
    }

    @Test fun `live periodic work outranks a newer manual terminal summary`() {
        val periodicId = UUID.randomUUID()
        val executionId = "3:periodic"
        val running = WorkInfo(
            periodicId,
            WorkInfo.State.RUNNING,
            setOf("setup-account:3"),
            progress = operationData(
                periodicId.toString(), 3, OperationPhase.ACHIEVEMENTS,
                OperationCounts(3, 8), executionId = executionId, startedAt = 100,
            ),
        )
        val manual = TimedOperation(
            OperationProgress("manual", OperationStatus.SUCCEEDED, counts = OperationCounts(5, 5)),
            startedAt = 200,
        )
        val report = StoredOperationReport(
            periodicId.toString(), 3, 100,
            OperationProgress(executionId, OperationStatus.RUNNING, OperationPhase.ACHIEVEMENTS,
                OperationCounts(2, 8)),
            OperationSource.PERIODIC,
        )

        val selected = latestOperation(listOfNotNull(manual, periodicOperation(running, 3, report)))

        assertEquals(executionId, selected?.id)
        assertEquals(OperationStatus.RUNNING, selected?.status)
        assertEquals(3, selected?.counts?.processed)
    }

    @Test fun `known connectivity stop stays waiting and keeps the periodic execution progress`() {
        val periodicId = UUID.randomUUID()
        val waiting = WorkInfo(
            periodicId, WorkInfo.State.ENQUEUED, emptySet(), runAttemptCount = 2,
            stopReason = WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
        )
        val report = StoredOperationReport(
            periodicId.toString(), 3, 100,
            OperationProgress("3:cycle", OperationStatus.RUNNING, OperationPhase.ACHIEVEMENTS,
                OperationCounts(2, 5, failed = 1)),
            OperationSource.PERIODIC,
        )

        val selected = periodicOperation(waiting, 3, report)?.progress

        assertEquals("3:cycle", selected?.id)
        assertEquals(OperationStatus.WAITING, selected?.status)
        assertEquals(2, selected?.counts?.processed)
    }

    @Test fun `interrupted reporting retains measured progress through constraint wait and cancellation`() = runTest {
        val reports = OperationReportStore(RuntimeEnvironment.getApplication())
        reports.clearForTest()
        val workId = UUID.randomUUID()
        val begun = reports.begin(OperationChannel.STEAM, OperationSource.PERIODIC,
            workId.toString(), generation = 3, retry = false)
        assertNull(begun.progress.counts)

        val counts = OperationCounts(processed = 6, total = 10, failed = 1)
        var workProgress = Data.EMPTY
        OperationReporter { 1_000L }.report(OperationPhase.ACHIEVEMENTS, counts) {
            workProgress = operationData(
                workId.toString(), 3, OperationPhase.ACHIEVEMENTS, counts, updated = 4,
                executionId = begun.progress.id, startedAt = begun.startedAt,
            )
        }
        val measured = operationProgress(WorkInfo(
            workId, WorkInfo.State.RUNNING, setOf("setup-account:3"), progress = workProgress,
        ), 3)!!
        reports.saveInterrupted(
            OperationChannel.STEAM,
            begun.copy(progress = measured),
        ) { true }
        val stored = reports.observe(OperationChannel.STEAM).first().single()
        assertEquals(begun.progress.id, stored.progress.id)
        assertEquals(OperationPhase.ACHIEVEMENTS, stored.progress.phase)
        assertEquals(counts, stored.progress.counts)
        assertEquals(4, stored.progress.updated)
        assertEquals(OperationStatus.RUNNING, stored.progress.status)
        assertTrue(stored.interrupted)

        val retainedCounts = OperationCounts(processed = 5, total = 10, failed = 1)
        val retainedWorkProgress = operationData(
            workId.toString(), 3, OperationPhase.PREPARING, retainedCounts, updated = 3,
            executionId = begun.progress.id, startedAt = begun.startedAt,
        )
        val waitingWithProgress = WorkInfo(
            workId, WorkInfo.State.ENQUEUED, setOf("setup-account:3"), progress = retainedWorkProgress,
            runAttemptCount = 2, stopReason = WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
        )
        val waitingWithClearedProgress = WorkInfo(
            workId, WorkInfo.State.ENQUEUED, setOf("setup-account:3"), progress = Data.EMPTY,
            runAttemptCount = 2, stopReason = WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
        )
        listOf(
            waitingWithProgress to (counts to 4),
            waitingWithClearedProgress to (counts to 4),
        ).forEach { (info, expected) ->
            val projected = periodicOperation(info, 3, stored)?.progress
            assertEquals(OperationStatus.WAITING, projected?.status)
            assertEquals(begun.progress.id, projected?.id)
            assertEquals(workId.toString(), projected?.workId)
            assertEquals(OperationPhase.ACHIEVEMENTS, projected?.phase)
            assertEquals(expected.first, projected?.counts)
            assertEquals(expected.second, projected?.updated)
            assertTrue(projected?.active == true)
        }

        val cancelled = WorkInfo(
            workId, WorkInfo.State.CANCELLED, setOf("setup-account:3"), progress = workProgress,
        )
        val cancellationSummary = periodicOperation(cancelled, 3, stored)?.progress
        assertEquals(OperationStatus.CANCELLED, cancellationSummary?.status)
        assertEquals(begun.progress.id, cancellationSummary?.id)
        assertEquals(OperationPhase.ACHIEVEMENTS, cancellationSummary?.phase)
        assertEquals(counts, cancellationSummary?.counts)
        assertFalse(cancellationSummary?.active == true)

        val cancelledWithoutProgress = WorkInfo(
            workId, WorkInfo.State.CANCELLED, setOf("setup-account:3"), progress = Data.EMPTY,
        )
        val recoveredCancellation = periodicOperation(cancelledWithoutProgress, 3, stored)?.progress
        assertEquals(OperationStatus.CANCELLED, recoveredCancellation?.status)
        assertEquals(begun.progress.id, recoveredCancellation?.id)
        assertEquals(OperationPhase.ACHIEVEMENTS, recoveredCancellation?.phase)
        assertEquals(counts, recoveredCancellation?.counts)
    }

    @Test fun `a later retry attempt or execution resets progress instead of inheriting an old snapshot`() = runTest {
        val reports = OperationReportStore(RuntimeEnvironment.getApplication())
        reports.clearForTest()
        val workId = id.toString()
        val first = reports.begin(OperationChannel.STEAM, OperationSource.MANUAL, workId, 3, retry = false, attempt = 0)
        val interruptedCounts = OperationCounts(6, 10)
        reports.saveInterrupted(
            OperationChannel.STEAM,
            first.copy(progress = first.progress.copy(
                phase = OperationPhase.ACHIEVEMENTS,
                counts = interruptedCounts,
                updated = 4,
            )),
        ) { true }

        val retry = reports.begin(OperationChannel.STEAM, OperationSource.MANUAL, workId, 3, retry = true, attempt = 1)
        val oldAttempt = WorkInfo(
            id,
            WorkInfo.State.RUNNING,
            setOf("setup-account:3"),
            progress = operationData(
                id.toString(), 3, OperationPhase.ACHIEVEMENTS, interruptedCounts,
                updated = 4, executionId = retry.progress.id, startedAt = retry.startedAt, attempt = 0,
            ),
            runAttemptCount = 1,
        )

        val reset = oneTimeOperation(oldAttempt, 3, retry)?.progress
        assertEquals(OperationPhase.PREPARING, reset?.phase)
        assertNull(reset?.counts)
        assertNull(reset?.updated)

        val currentCounts = OperationCounts(1, 10)
        val currentAttempt = WorkInfo(
            id,
            WorkInfo.State.RUNNING,
            setOf("setup-account:3"),
            progress = operationData(
                id.toString(), 3, OperationPhase.ACHIEVEMENTS, currentCounts,
                executionId = retry.progress.id, startedAt = retry.startedAt, attempt = 1,
            ),
            runAttemptCount = 1,
        )
        assertEquals(currentCounts, oneTimeOperation(currentAttempt, 3, retry)?.progress?.counts)

        val restarted = reports.begin(OperationChannel.STEAM, OperationSource.MANUAL, workId, 3, retry = false, attempt = 0)
        val oldExecution = WorkInfo(
            id,
            WorkInfo.State.RUNNING,
            setOf("setup-account:3"),
            progress = operationData(
                id.toString(), 3, OperationPhase.ACHIEVEMENTS, interruptedCounts,
                executionId = first.progress.id, startedAt = first.startedAt, attempt = 0,
            ),
            runAttemptCount = 0,
        )
        val resetExecution = oneTimeOperation(oldExecution, 3, restarted)?.progress
        assertEquals(restarted.progress.id, resetExecution?.id)
        assertEquals(OperationPhase.PREPARING, resetExecution?.phase)
        assertNull(resetExecution?.counts)
    }

    @Test fun `cancelled periodic work reconciles its stored running report`() {
        val periodicId = UUID.randomUUID()
        val cancelled = WorkInfo(periodicId, WorkInfo.State.CANCELLED, emptySet())
        val report = StoredOperationReport(
            periodicId.toString(), 3, 100,
            OperationProgress("3:cycle", OperationStatus.RUNNING, OperationPhase.ACHIEVEMENTS,
                OperationCounts(2, 5)),
            OperationSource.PERIODIC,
        )

        val selected = periodicOperation(cancelled, 3, report)?.progress

        assertEquals("3:cycle", selected?.id)
        assertEquals(OperationStatus.CANCELLED, selected?.status)
        assertEquals(2, selected?.counts?.processed)
    }
}
