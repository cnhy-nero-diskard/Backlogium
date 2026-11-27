package com.example.backlogium.work

import androidx.work.Data
import androidx.work.WorkInfo
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
import com.example.backlogium.domain.OperationProgress
import com.example.backlogium.domain.OperationStatus
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
