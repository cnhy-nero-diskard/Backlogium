package com.example.backlogium.work

import androidx.work.Data
import androidx.work.WorkInfo
import com.example.backlogium.domain.OperationCounts
import com.example.backlogium.domain.OperationPhase
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
}
