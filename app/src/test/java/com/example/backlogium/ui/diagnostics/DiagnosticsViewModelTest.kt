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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    @Test
    fun windowsRequeryOnSelectionAndClockRefresh() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher) {
                val time = MutableTimeProvider()
                val dao = FakeDiagnosticsDao()
                val viewModel = DiagnosticsViewModel(dao, time)
                val collectors = listOf(
                    launch { viewModel.totals24h.collect {} },
                    launch { viewModel.totals30d.collect {} },
                    launch { viewModel.totals365d.collect {} },
                    launch { viewModel.endpointBreakdown.collect {} },
                )
                runCurrent()

                assertEquals(RequestCounterTotals(ok = 2, failed = 1), viewModel.totals24h.value)
                assertEquals(RequestCounterTotals(ok = 20, failed = 3), viewModel.totals30d.value)
                assertEquals(RequestCounterTotals(ok = 200, failed = 4), viewModel.totals365d.value)
                assertEquals(listOf("24h"), viewModel.endpointBreakdown.value.map { it.route })

                viewModel.selectCounterWindow(RequestCounterWindow.DAYS_30)
                runCurrent()

                assertEquals(listOf("30d"), viewModel.endpointBreakdown.value.map { it.route })
                assertEquals(RequestCounterWindow.DAYS_30, viewModel.selectedCounterWindow.value)

                time.now = INITIAL_NOW + SIX_HOURS_MILLIS
                viewModel.refreshCounterWindows()
                runCurrent()

                assertEquals(RequestCounterTotals(ok = 7, failed = 0), viewModel.totals24h.value)
                assertEquals(RequestCounterTotals(ok = 70, failed = 1), viewModel.totals30d.value)
                assertEquals(RequestCounterTotals(ok = 700, failed = 2), viewModel.totals365d.value)
                assertTrue(
                    dao.observedTotalCutoffs.contains(
                        RequestCounterWindow.HOURS_24.cutoff(time.now),
                    ),
                )

                viewModel.selectCounterWindow(RequestCounterWindow.HOURS_24)
                runCurrent()
                assertEquals(listOf("24h-refreshed"), viewModel.endpointBreakdown.value.map { it.route })
                collectors.forEach { it.cancel() }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class MutableTimeProvider(var now: Long = INITIAL_NOW) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-19")
    }

    private class FakeDiagnosticsDao : DiagnosticsDao {
        val observedTotalCutoffs = mutableListOf<Long>()
        private val totalFlows = mutableMapOf<Long, MutableStateFlow<RequestCounterTotals>>()
        private val routeFlows = mutableMapOf<Long, MutableStateFlow<List<RequestRouteTotals>>>()

        override suspend fun insertRun(run: SyncRun): Long = 1L
        override suspend fun insertBreakdowns(rows: List<RequestBreakdown>) = Unit
        override suspend fun insertPresenceDecision(decision: PresenceDecision) = Unit
        override fun observeRuns(): Flow<List<SyncRun>> = emptyFlow()
        override fun observeRun(runId: Long): Flow<SyncRun?> = emptyFlow()
        override fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>> = emptyFlow()
        override fun observePresenceDecisions(): Flow<List<PresenceDecision>> = emptyFlow()
        override suspend fun incrementRequestTotal(hourStart: Long, route: String, status: String, ok: Boolean, count: Int) = Unit
        override suspend fun pruneRequestTotals(cutoff: Long) = Unit
        override fun observeRequestTotals(cutoff: Long): Flow<RequestCounterTotals> {
            observedTotalCutoffs += cutoff
            return totalFlows.getOrPut(cutoff) { MutableStateFlow(totalsFor(cutoff)) }
        }
        override fun observeRequestRoutes(cutoff: Long): Flow<List<RequestRouteTotals>> =
            routeFlows.getOrPut(cutoff) { MutableStateFlow(routesFor(cutoff)) }
        override suspend fun pruneRuns(limit: Int) = Unit
        override suspend fun prunePresenceDecisions(limit: Int) = Unit
        override suspend fun deleteRequestBreakdowns() = Unit
        override suspend fun deleteSyncRuns() = Unit
        override suspend fun deletePresenceDecisions() = Unit
        override suspend fun deleteRequestTotals() = Unit

        private fun totalsFor(cutoff: Long): RequestCounterTotals = when (cutoff) {
            RequestCounterWindow.HOURS_24.cutoff(INITIAL_NOW) -> RequestCounterTotals(2, 1)
            RequestCounterWindow.DAYS_30.cutoff(INITIAL_NOW) -> RequestCounterTotals(20, 3)
            RequestCounterWindow.DAYS_365.cutoff(INITIAL_NOW) -> RequestCounterTotals(200, 4)
            RequestCounterWindow.HOURS_24.cutoff(INITIAL_NOW + SIX_HOURS_MILLIS) -> RequestCounterTotals(7, 0)
            RequestCounterWindow.DAYS_30.cutoff(INITIAL_NOW + SIX_HOURS_MILLIS) -> RequestCounterTotals(70, 1)
            RequestCounterWindow.DAYS_365.cutoff(INITIAL_NOW + SIX_HOURS_MILLIS) -> RequestCounterTotals(700, 2)
            else -> RequestCounterTotals()
        }

        private fun routesFor(cutoff: Long): List<RequestRouteTotals> = when (cutoff) {
            RequestCounterWindow.HOURS_24.cutoff(INITIAL_NOW) -> listOf(RequestRouteTotals("24h", 2, 1))
            RequestCounterWindow.DAYS_30.cutoff(INITIAL_NOW) -> listOf(RequestRouteTotals("30d", 20, 3))
            RequestCounterWindow.DAYS_365.cutoff(INITIAL_NOW) -> listOf(RequestRouteTotals("365d", 200, 4))
            RequestCounterWindow.HOURS_24.cutoff(INITIAL_NOW + SIX_HOURS_MILLIS) -> listOf(RequestRouteTotals("24h-refreshed", 7, 0))
            else -> emptyList()
        }
    }

    private companion object {
        const val INITIAL_NOW = 1_700_000_000_000L
        const val SIX_HOURS_MILLIS = 6L * 60 * 60 * 1_000
    }
}
