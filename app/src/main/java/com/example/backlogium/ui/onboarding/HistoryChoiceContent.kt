package com.example.backlogium.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.backlogium.R

/** Presentation facts only; mounting this surface never submits or implies consent. */
data class HistoryChoiceUiState(
    val baselineReady: Boolean = false,
    val imported: Boolean = false,
    val busy: Boolean = false,
    val recomputePending: Boolean = false,
    val failure: String? = null,
)

@Composable
internal fun HistoryChoiceContent(
    state: HistoryChoiceUiState,
    onImport: () -> Unit,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    onContinue: () -> Unit,
    onReviewSetup: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.history_choice_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.history_choice_effect), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.history_choice_limits), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            state.recomputePending -> Text(stringResource(R.string.history_choice_recompute_pending),
                style = MaterialTheme.typography.bodyMedium)
            state.busy -> Text(stringResource(R.string.history_choice_working),
                style = MaterialTheme.typography.bodyMedium)
            state.imported -> Text(stringResource(R.string.history_choice_already_imported),
                style = MaterialTheme.typography.bodyMedium)
            !state.baselineReady -> Text(stringResource(R.string.history_choice_needs_baseline),
                style = MaterialTheme.typography.bodyMedium)
        }
        state.failure?.let { Text(it, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error) }
        if (state.imported && !state.recomputePending) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.setup_continue))
            }
        } else {
            val recovery = state.recomputePending || state.failure != null
            Button(onClick = if (recovery) onRetry else onImport,
                enabled = !state.busy && (state.baselineReady || state.recomputePending),
                modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (recovery) R.string.history_choice_resume else R.string.history_choice_import))
            }
        }
        // Exit is deliberately available before consent and while admitted import is pending.
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.history_choice_skip))
        }
        TextButton(onClick = onReviewSetup, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.history_choice_review_setup))
        }
        Text(stringResource(R.string.history_choice_settings_later), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
