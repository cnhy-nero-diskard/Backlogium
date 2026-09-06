package com.example.backlogium.data.diagnostics

import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestRouteTotals
import com.example.backlogium.data.local.entity.RequestTotal
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SyncRunRecorderTest {

    @Test
    fun finishWritesCredentialFreeHourlyRollups() = runBlocking {
        val dao = RecordingDiagnosticsDao()
        val recorder = SyncRunRecorder(dao, FixedTimeProvider)
        val scope = recorder.begin("test")
        scope.recordRequest(
            "/IPlayerService/GetOwnedGames/v1/?key=secret&steamid=76561198000000001&appid=440",
            200,
            10,
        )
        scope.recordRequest(
            "/IPlayerService/GetOwnedGames/v1/?key=secret&steamid=76561198000000001&appid=440",
            null,
            20,
        )

        recorder.finish(scope, SyncOutcome.SUCCESS, null, gamesExamined = 1, gamesUpdated = 1)

        val hourStart = FixedTimeProvider.nowMillis() - Math.floorMod(FixedTimeProvider.nowMillis(), REQUEST_COUNTER_HOUR_MILLIS)
        assertEquals(
            listOf(
                RequestTotal(hourStart, "/IPlayerService/GetOwnedGames/v1/", "200", true, 1),
                RequestTotal(hourStart, "/IPlayerService/GetOwnedGames/v1/", NETWORK_REQUEST_STATUS, false, 1),
            ),
            dao.totals,
        )
        assertEquals(1, dao.runs.size)
    }

    @Test
    fun clockRollbacksRecordedThisRunAppearOnThePersistedRun() = runBlocking {
        val dao = RecordingDiagnosticsDao()
        val recorder = SyncRunRecorder(dao, FixedTimeProvider)
        val scope = recorder.begin("test")

        // A clamp is recorded rather than discarded silently (auditfix-session-ledger-integrity,
        // #115) — this is the diagnostics surface task 3.3 requires it appear on.
        scope.recordClockRollback()
        scope.recordClockRollback()

        recorder.finish(scope, SyncOutcome.SUCCESS, null, gamesExamined = 1, gamesUpdated = 1)

        assertEquals(2, dao.runs.single().clockRollbackCount)
    }

    @Test
    fun rollupFailureDoesNotPreventRunPersistence() = runBlocking {
        val dao = RecordingDiagnosticsDao(failRollup = true)
        val recorder = SyncRunRecorder(dao, FixedTimeProvider)
        val scope = recorder.begin("test")
        scope.recordRequest("/IPlayerService/GetOwnedGames/v1/", 200, 10)

        recorder.finish(scope, SyncOutcome.FAILED, "offline", gamesExamined = 0, gamesUpdated = 0)

        assertEquals(1, dao.runs.size)
        assertEquals(1, dao.pruneTotalsCalls)
        assertEquals(emptyList<RequestTotal>(), dao.totals)
    }

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_001_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-19")
    }

    private class RecordingDiagnosticsDao(private val failRollup: Boolean = false) : DiagnosticsDao {
        val runs = mutableListOf<SyncRun>()
        val totals = mutableListOf<RequestTotal>()
        var pruneTotalsCalls = 0

        override suspend fun insertRun(run: SyncRun): Long {
            runs += run
            return runs.size.toLong()
        }

        override suspend fun insertBreakdowns(rows: List<RequestBreakdown>) = Unit
        override suspend fun insertPresenceDecision(decision: PresenceDecision) = Unit
        override fun observeRuns(): Flow<List<SyncRun>> = emptyFlow()
        override fun observeRun(runId: Long): Flow<SyncRun?> = emptyFlow()
        override fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>> = emptyFlow()
        override fun observePresenceDecisions(): Flow<List<PresenceDecision>> = emptyFlow()
        override suspend fun incrementRequestTotal(hourStart: Long, route: String, status: String, ok: Boolean, count: Int) {
            if (failRollup) error("simulated rollup failure")
            totals += RequestTotal(hourStart, route, status, ok, count)
        }
        override suspend fun pruneRequestTotals(cutoff: Long) {
            pruneTotalsCalls++
        }
        override fun observeRequestTotals(cutoff: Long): Flow<RequestCounterTotals> = emptyFlow()
        override fun observeRequestRoutes(cutoff: Long): Flow<List<RequestRouteTotals>> = emptyFlow()
        override suspend fun pruneRuns(limit: Int) = Unit
        override suspend fun prunePresenceDecisions(limit: Int) = Unit
        override suspend fun deleteRequestBreakdowns() = Unit
        override suspend fun deleteSyncRuns() = Unit
        override suspend fun deletePresenceDecisions() = Unit
        override suspend fun deleteRequestTotals() = Unit
    }
}
