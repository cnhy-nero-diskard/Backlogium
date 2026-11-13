package com.example.backlogium.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.repo.HistoryImportReadiness

@Composable
fun HistoryImportStep(onDone: () -> Unit, viewModel: HistoryImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) { if (state.completed) onDone() }
    HistoryImportContent(state, onImport = viewModel::importHistory, onSkip = viewModel::skip)
}

@Composable
fun HistoryImportContent(state: HistoryImportUiState, onImport: () -> Unit, onSkip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Count your pre-existing Steam playtime toward XP. This optional import credits each game's past hours once.")
        Text("Steam lifetime totals have no historical session timestamps. This won't create dated sessions or daily progress.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.readiness == HistoryImportReadiness.NEEDS_SYNC) {
            Text("Finish your library sync first, or skip and import later from Settings.", style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = onImport,
            enabled = !state.importing && !state.completed && state.readiness == HistoryImportReadiness.READY,
            modifier = Modifier.fillMaxWidth()) {
            if (state.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (state.error != null) "Retry import" else "Import Steam history")
        }
        TextButton(
            onClick = onSkip,
            enabled = !state.importing && !state.completed,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skip")
        }
        Text("You can import or undo this later in Settings.", style = MaterialTheme.typography.bodySmall)
    }
}
