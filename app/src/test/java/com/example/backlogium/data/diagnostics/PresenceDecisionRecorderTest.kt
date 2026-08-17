package com.example.backlogium.data.diagnostics

import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PresenceDecisionRecorderTest {

    @Test
    fun lifecycleOutcomes_areStoredWithoutCredentialValues() = runBlocking {
        val dao = RecordingDiagnosticsDao()
        val recorder = PresenceDecisionRecorder(dao, FixedTimeProvider)
        val outcomes = listOf(
            PresenceOutcome.MONITORING_STARTED,
            PresenceOutcome.MONITORING_ALREADY_RUNNING,
            PresenceOutcome.START_REFUSED,
            PresenceOutcome.START_FAILED,
            PresenceOutcome.START_NOT_ATTEMPTED,
            PresenceOutcome.RUNTIME_BUDGET_REACHED,
        )

        outcomes.forEach { recorder.record("test", it) }

        assertEquals(outcomes.map { it.value }, dao.decisions.map { it.outcome })
        assertTrue(dao.decisions.all { it.appId == null })
    }

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-17")
    }

    private class RecordingDiagnosticsDao : DiagnosticsDao {
        val decisions = mutableListOf<PresenceDecision>()

        override suspend fun insertRun(run: SyncRun): Long = 1L
        override suspend fun insertBreakdowns(rows: List<RequestBreakdown>) = Unit
        override suspend fun insertPresenceDecision(decision: PresenceDecision) {
            decisions += decision
        }
        override fun observeRuns(): Flow<List<SyncRun>> = emptyFlow()
        override fun observeRun(runId: Long): Flow<SyncRun?> = emptyFlow()
        override fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>> = emptyFlow()
        override fun observePresenceDecisions(): Flow<List<PresenceDecision>> = emptyFlow()
        override suspend fun pruneRuns(limit: Int) = Unit
        override suspend fun prunePresenceDecisions(limit: Int) = Unit
        override suspend fun deleteRequestBreakdowns() = Unit
    }
}
