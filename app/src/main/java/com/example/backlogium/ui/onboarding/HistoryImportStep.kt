package com.example.backlogium.ui.onboarding

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.res.stringResource
import com.example.backlogium.R
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.repo.HistoryImportReadiness

@Composable
fun HistoryImportStep(onDone: () -> Unit, viewModel: HistoryImportViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BackHandler(enabled = state.importing || state.readiness == HistoryImportReadiness.PENDING) {
        viewModel.skip()
    }
    LaunchedEffect(state.completed) { if (state.completed) onDone() }
    HistoryImportContent(
        state,
        onImport = viewModel::importHistory,
        onSkip = viewModel::skip,
        onSync = viewModel::syncLibrary,
        onResume = viewModel::resumeHistory,
    )
}

@Composable
fun HistoryImportContent(
    state: HistoryImportUiState,
    onImport: () -> Unit,
    onSkip: () -> Unit,
    onSync: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    val pending = state.readiness == HistoryImportReadiness.PENDING
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Count your pre-existing Steam playtime toward XP. This optional import credits each game's past hours once.")
        Text(
            "Steam lifetime totals have no historical session timestamps.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.error?.let { Text(stringResource(R.string.history_import_failed), color = MaterialTheme.colorScheme.error) }
        when (state.readiness) {
            HistoryImportReadiness.NEEDS_SYNC -> {
                Text("Sync your Steam library before importing history.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onSync, enabled = !state.syncingLibrary, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.syncingLibrary) "Syncing library…" else "Sync Steam library")
                }
            }
            HistoryImportReadiness.PENDING -> {
                Text("History import is pending. You can continue and check its progress in Settings.")
                TextButton(onClick = onResume, enabled = !state.importing) { Text("Resume history import") }
            }
            HistoryImportReadiness.IMPORTED -> Text("Steam history imported.")
            HistoryImportReadiness.READY -> Button(
                onClick = onImport,
                enabled = !state.importing && !state.completed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (state.error != null) "Retry import" else "Import Steam history")
            }
        }
        TextButton(
            onClick = onSkip,
            enabled = (!state.importing || pending) && !state.completed,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (pending || state.readiness == HistoryImportReadiness.IMPORTED) "Continue" else "Skip")
        }
    }
}
