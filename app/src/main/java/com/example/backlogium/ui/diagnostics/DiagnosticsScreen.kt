package com.example.backlogium.ui.diagnostics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.diagnostics.REQUEST_COUNTER_HOUR_MILLIS
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestRouteTotals
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.ui.util.UiFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DiagnosticsUiState(val runs: List<SyncRun> = emptyList(), val decisions: List<PresenceDecision> = emptyList())

enum class RequestCounterWindow(val label: String, private val durationMillis: Long) {
    HOURS_24("24h", 24L * 60 * 60 * 1_000),
    DAYS_30("30d", 30L * 24 * 60 * 60 * 1_000),
    DAYS_365("365d", 365L * 24 * 60 * 60 * 1_000),
    ;

    fun cutoff(nowMillis: Long): Long = nowMillis - durationMillis
}

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModel @Inject constructor(
    private val dao: DiagnosticsDao,
    time: TimeProvider,
) : ViewModel() {
    private val counterRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val counterNowMillis: StateFlow<Long> = merge(
        flowOf(Unit),
        flow {
            while (true) {
                delay(REQUEST_COUNTER_HOUR_MILLIS)
                emit(Unit)
            }
        },
        counterRefreshes,
    ).map { time.nowMillis() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), time.nowMillis())

    val totals24h: StateFlow<RequestCounterTotals> = counterNowMillis
        .flatMapLatest { nowMillis -> dao.observeRequestTotals(RequestCounterWindow.HOURS_24.cutoff(nowMillis)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestCounterTotals())
    val totals30d: StateFlow<RequestCounterTotals> = counterNowMillis
        .flatMapLatest { nowMillis -> dao.observeRequestTotals(RequestCounterWindow.DAYS_30.cutoff(nowMillis)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestCounterTotals())
    val totals365d: StateFlow<RequestCounterTotals> = counterNowMillis
        .flatMapLatest { nowMillis -> dao.observeRequestTotals(RequestCounterWindow.DAYS_365.cutoff(nowMillis)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RequestCounterTotals())

    private val selectedCounterWindowState = MutableStateFlow(RequestCounterWindow.HOURS_24)
    val selectedCounterWindow: StateFlow<RequestCounterWindow> = selectedCounterWindowState
    val endpointBreakdown: StateFlow<List<RequestRouteTotals>> = combine(selectedCounterWindowState, counterNowMillis) { window, nowMillis ->
        window to nowMillis
    }.flatMapLatest { (window, nowMillis) -> dao.observeRequestRoutes(window.cutoff(nowMillis)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<DiagnosticsUiState> = combine(dao.observeRuns(), dao.observePresenceDecisions()) { runs, decisions ->
        DiagnosticsUiState(runs, decisions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun breakdowns(runId: Long) = dao.observeBreakdowns(runId)

    fun selectCounterWindow(window: RequestCounterWindow) {
        selectedCounterWindowState.value = window
    }

    /** Refreshes rolling-window cutoffs immediately; the normal screen also refreshes hourly. */
    fun refreshCounterWindows() {
        counterRefreshes.tryEmit(Unit)
    }
}

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val totals24h by viewModel.totals24h.collectAsStateWithLifecycle()
    val totals30d by viewModel.totals30d.collectAsStateWithLifecycle()
    val totals365d by viewModel.totals365d.collectAsStateWithLifecycle()
    val selectedCounterWindow by viewModel.selectedCounterWindow.collectAsStateWithLifecycle()
    val endpointBreakdown by viewModel.endpointBreakdown.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SyncRun?>(null) }
    selected?.let { run ->
        val breakdowns by viewModel.breakdowns(run.id).collectAsStateWithLifecycle(emptyList())
        DiagnosticsDetail(run, breakdowns) { selected = null }
    } ?: DiagnosticsList(
        state = state,
        totals24h = totals24h,
        totals30d = totals30d,
        totals365d = totals365d,
        selectedCounterWindow = selectedCounterWindow,
        endpointBreakdown = endpointBreakdown,
        onCounterWindowSelected = viewModel::selectCounterWindow,
        onSelect = { selected = it },
    )
}

@Composable
private fun DiagnosticsList(
    state: DiagnosticsUiState,
    totals24h: RequestCounterTotals,
    totals30d: RequestCounterTotals,
    totals365d: RequestCounterTotals,
    selectedCounterWindow: RequestCounterWindow,
    endpointBreakdown: List<RequestRouteTotals>,
    onCounterWindowSelected: (RequestCounterWindow) -> Unit,
    onSelect: (SyncRun) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "diagnostics-title") { Text("Diagnostics", style = MaterialTheme.typography.headlineSmall) }
        item(key = "request-counters") {
            RequestCountersSection(
                totals = listOf(
                    "24h" to totals24h,
                    "30d" to totals30d,
                    "365d" to totals365d,
                ),
                selectedWindow = selectedCounterWindow,
                endpointBreakdown = endpointBreakdown,
                onWindowSelected = onCounterWindowSelected,
            )
        }
        item(key = "recent-sync-runs") { Text("Recent sync runs", style = MaterialTheme.typography.titleMedium) }
        if (state.runs.isEmpty()) item(key = "no-sync-runs") { Text("No sync runs recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.runs, key = { "run-${it.id}" }) { run ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(run) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${run.outcome} - ${run.trigger}", style = MaterialTheme.typography.titleSmall)
                    Text(UiFormat.dateTime(run.startedAt), style = MaterialTheme.typography.bodySmall)
                    Text("${run.durationMs / 1000}s - ${run.requestCount} requests", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "presence-decisions") { Text("Presence decisions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (state.decisions.isEmpty()) item(key = "no-presence-decisions") { Text("No presence decisions recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.decisions, key = { "decision-${it.id}" }) { decision -> PresenceRow(decision) }
    }
}

@Composable
private fun RequestCountersSection(
    totals: List<Pair<String, RequestCounterTotals>>,
    selectedWindow: RequestCounterWindow,
    endpointBreakdown: List<RequestRouteTotals>,
    onWindowSelected: (RequestCounterWindow) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Request counters", style = MaterialTheme.typography.titleMedium)
        if (totals.all { (_, value) -> value.ok == 0L && value.failed == 0L }) {
            Text("No requests counted yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return
        }
        totals.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Text("${value.ok + value.failed} (${value.ok} ok · ${value.failed} failed)")
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("By endpoint", style = MaterialTheme.typography.titleSmall)
            Row {
                RequestCounterWindow.entries.forEach { window ->
                    TextButton(onClick = { onWindowSelected(window) }) {
                        Text(if (window == selectedWindow) "[${window.label}]" else window.label)
                    }
                }
            }
        }
        endpointBreakdown.forEach { route ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(route.route, modifier = Modifier.weight(1f))
                Text("${route.ok} ok · ${route.failed} failed")
            }
        }
    }
}

@Composable
private fun DiagnosticsDetail(run: SyncRun, breakdowns: List<RequestBreakdown>, onBack: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = onBack) { Text("Back") } }
        item { Text("Sync run", style = MaterialTheme.typography.headlineSmall) }
        item { Text("${run.outcome} - ${UiFormat.dateTime(run.startedAt)}") }
        item { Text("Trigger: ${run.trigger}\nDuration: ${run.durationMs} ms\nRequests: ${run.requestCount} (${run.requestMillis} ms)\nGames: ${run.gamesExamined} examined, ${run.gamesUpdated} updated") }
        run.errorMessage?.let { item { Text("Error: $it", color = MaterialTheme.colorScheme.error) } }
        item { HorizontalDivider() }
        item { Text("Request breakdown", style = MaterialTheme.typography.titleMedium) }
        if (breakdowns.isEmpty()) item { Text("No Steam requests were recorded for this run.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(breakdowns, key = { it.id }) { row ->
            Text("${row.requestCount}x ${row.endpoint} - ${row.status ?: "failed"} - ${row.durationMs} ms", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PresenceRow(decision: PresenceDecision) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text(decision.outcome); Text("${decision.trigger} - ${UiFormat.dateTime(decision.at)}", style = MaterialTheme.typography.bodySmall) }
            decision.appId?.let { Text("App $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
