package com.example.backlogium.ui.diagnostics

import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestRouteTotals
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    @Test
    fun windowsComposeAndEndpointSelectionRequeriesTheChosenWindow() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val dao = FakeDiagnosticsDao()
                val viewModel = DiagnosticsViewModel(dao, FixedTimeProvider)
                val collectors = listOf(
                    launch { viewModel.totals24h.collect {} },
                    launch { viewModel.totals30d.collect {} },
                    launch { viewModel.totals365d.collect {} },
                    launch { viewModel.endpointBreakdown.collect {} },
                )
                advanceUntilIdle()

                assertEquals(RequestCounterTotals(ok = 2, failed = 1), viewModel.totals24h.value)
                assertEquals(RequestCounterTotals(ok = 20, failed = 3), viewModel.totals30d.value)
                assertEquals(RequestCounterTotals(ok = 200, failed = 4), viewModel.totals365d.value)
                assertEquals(listOf("24h"), viewModel.endpointBreakdown.value.map { it.route })

                viewModel.selectCounterWindow(RequestCounterWindow.DAYS_30)
                advanceUntilIdle()

                assertEquals(listOf("30d"), viewModel.endpointBreakdown.value.map { it.route })
                assertEquals(RequestCounterWindow.DAYS_30, viewModel.selectedCounterWindow.value)
                collectors.forEach { it.cancel() }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_700_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-19")
    }

    private class FakeDiagnosticsDao : DiagnosticsDao {
        private val totals = mapOf(
            RequestCounterWindow.HOURS_24.cutoff(FixedTimeProvider.nowMillis()) to RequestCounterTotals(2, 1),
            RequestCounterWindow.DAYS_30.cutoff(FixedTimeProvider.nowMillis()) to RequestCounterTotals(20, 3),
            RequestCounterWindow.DAYS_365.cutoff(FixedTimeProvider.nowMillis()) to RequestCounterTotals(200, 4),
        )
        private val routes = mapOf(
            RequestCounterWindow.HOURS_24.cutoff(FixedTimeProvider.nowMillis()) to listOf(RequestRouteTotals("24h", 2, 1)),
            RequestCounterWindow.DAYS_30.cutoff(FixedTimeProvider.nowMillis()) to listOf(RequestRouteTotals("30d", 20, 3)),
            RequestCounterWindow.DAYS_365.cutoff(FixedTimeProvider.nowMillis()) to listOf(RequestRouteTotals("365d", 200, 4)),
        )

        override suspend fun insertRun(run: SyncRun): Long = 1L
        override suspend fun insertBreakdowns(rows: List<RequestBreakdown>) = Unit
        override suspend fun insertPresenceDecision(decision: PresenceDecision) = Unit
        override fun observeRuns(): Flow<List<SyncRun>> = emptyFlow()
        override fun observeRun(runId: Long): Flow<SyncRun?> = emptyFlow()
        override fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>> = emptyFlow()
        override fun observePresenceDecisions(): Flow<List<PresenceDecision>> = emptyFlow()
        override suspend fun incrementRequestTotal(hourStart: Long, route: String, status: String, ok: Boolean, count: Int) = Unit
        override suspend fun pruneRequestTotals(cutoff: Long) = Unit
        override fun observeRequestTotals(cutoff: Long): Flow<RequestCounterTotals> = MutableStateFlow(totals[cutoff] ?: RequestCounterTotals())
        override fun observeRequestRoutes(cutoff: Long): Flow<List<RequestRouteTotals>> = MutableStateFlow(routes[cutoff].orEmpty())
        override suspend fun pruneRuns(limit: Int) = Unit
        override suspend fun prunePresenceDecisions(limit: Int) = Unit
        override suspend fun deleteRequestBreakdowns() = Unit
        override suspend fun deleteSyncRuns() = Unit
        override suspend fun deletePresenceDecisions() = Unit
        override suspend fun deleteRequestTotals() = Unit
    }
}
