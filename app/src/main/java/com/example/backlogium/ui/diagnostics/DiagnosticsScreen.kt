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
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.ui.util.UiFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DiagnosticsUiState(val runs: List<SyncRun> = emptyList(), val decisions: List<PresenceDecision> = emptyList())

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val dao: DiagnosticsDao) : ViewModel() {
    val state: StateFlow<DiagnosticsUiState> = combine(dao.observeRuns(), dao.observePresenceDecisions()) { runs, decisions ->
        DiagnosticsUiState(runs, decisions)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiagnosticsUiState())

    fun breakdowns(runId: Long) = dao.observeBreakdowns(runId)
}

@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<SyncRun?>(null) }
    selected?.let { run ->
        val breakdowns by viewModel.breakdowns(run.id).collectAsStateWithLifecycle(emptyList())
        DiagnosticsDetail(run, breakdowns) { selected = null }
    } ?: DiagnosticsList(state, onSelect = { selected = it })
}

@Composable
private fun DiagnosticsList(state: DiagnosticsUiState, onSelect: (SyncRun) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Diagnostics", style = MaterialTheme.typography.headlineSmall) }
        item { Text("Recent sync runs", style = MaterialTheme.typography.titleMedium) }
        if (state.runs.isEmpty()) item { Text("No sync runs recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.runs, key = { it.id }) { run ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(run) }) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${run.outcome} - ${run.trigger}", style = MaterialTheme.typography.titleSmall)
                    Text(UiFormat.dateTime(run.startedAt), style = MaterialTheme.typography.bodySmall)
                    Text("${run.durationMs / 1000}s - ${run.requestCount} requests", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Presence decisions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        if (state.decisions.isEmpty()) item { Text("No presence decisions recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(state.decisions, key = { it.id }) { decision -> PresenceRow(decision) }
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
