package com.example.backlogium.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.NotificationPermissionRequest
import com.example.backlogium.R
import com.example.backlogium.work.setup.SetupOperationState
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Minus

/**
 * The staged setup checklist, shared verbatim between the onboarding step and the Settings re-run
 * entry. Every row comes from [SetupUiState.stages], which comes from the registry — so a stage
 * registered later appears here, in the run order, in the progress display, and in the summary
 * without this file being touched.
 *
 * Both entries offer recovery once the foreground wait settles. Pending work offers re-observation,
 * never a promise to bypass its existing constraints or backoff.
 */
@Composable
fun SetupChecklist(
    state: SetupUiState,
    onToggle: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
    showRetry: Boolean,
    modifier: Modifier = Modifier,
    onReobserve: (String) -> Unit = onRetry,
) {
    if (state.loading) return

    // Ask for the notification permission before the first detached stage starts, through the same
    // single, once-per-install in-app request the shell uses. Detached stages report progress in
    // their own notifications, so this is the moment it is worth something — and because the request
    // records that it was made, mounting it here can only ask a user who has never been asked.
    // Setup proceeds either way: a declined permission is not a stage failure.
    if (state.willDetachWork) NotificationPermissionRequest()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.credentialsConfigured) {
            Text(
                text = stringResource(R.string.setup_connect_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        state.stages.forEach { stage ->
            StageRow(
                stage = stage,
                enabled = state.credentialsConfigured && !state.running,
                showRetry = showRetry && !state.running,
                onToggle = onToggle,
                onRetry = onRetry,
                onReobserve = onReobserve,
            )
        }
    }
}

@Composable
private fun StageRow(
    stage: SetupStageUi,
    enabled: Boolean,
    showRetry: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
    onReobserve: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    modifier = Modifier.semantics { contentDescription = stage.title },
                    checked = stage.selected,
                    onCheckedChange = { onToggle(stage.id, it) },
                    enabled = enabled && stage.selectable,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stage.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stage.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutcomeBadge(stage)
            }

            stage.unavailableReason?.let { reason ->
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (stage.running) RunningProgress(stage)

            operationExplanation(stage.operationState)?.let { explanation ->
                Text(
                    text = explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stage.operationState is SetupOperationState.Failed ||
                        stage.operationState is SetupOperationState.RecoveryRequired)
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (showRetry && stage.available && stage.operationState !is SetupOperationState.NeverRun) {
                val pending = stage.operationState.isPending
                TextButton(onClick = { if (pending) onReobserve(stage.id) else onRetry(stage.id) }, enabled = enabled) {
                    Text(stringResource(when {
                        pending -> R.string.setup_view_progress
                        stage.operationState is SetupOperationState.Succeeded -> R.string.setup_run_again
                        else -> R.string.setup_retry
                    }))
                }
            }
        }
    }
}

/**
 * Determinate where the underlying work publishes a total, indeterminate where it does not. The
 * library sync publishes none, so it deliberately shows a bar with no figure rather than a `0 / 0`
 * that reads as stalled.
 */
@Composable
private fun RunningProgress(stage: SetupStageUi) {
    val progress = stage.progress
    if (progress != null && progress.isDeterminate && progress.processed >= 0) {
        Text(
            text = stringResource(R.string.setup_progress, progress.processed, progress.total) +
                if (progress.label.isNotBlank()) " — ${progress.label}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { (progress.processed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OutcomeBadge(stage: SetupStageUi) {
    if (stage.running) {
        val label = stringResource(R.string.setup_running)
        CircularProgressIndicator(modifier = Modifier.size(18.dp).semantics { contentDescription = label }, strokeWidth = 2.dp)
        return
    }
    when (stage.operationState) {
        is SetupOperationState.Succeeded -> Icon(
            TablerIcons.CircleCheck,
            contentDescription = stringResource(R.string.setup_done),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )

        is SetupOperationState.Failed, is SetupOperationState.RecoveryRequired -> Icon(
            TablerIcons.AlertCircle,
            contentDescription = operationStatus(stage.operationState),
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )

        SetupOperationState.Skipped, SetupOperationState.Cancelled -> Icon(
            TablerIcons.Minus,
            contentDescription = operationStatus(stage.operationState),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        SetupOperationState.NeverRun -> Spacer(Modifier.width(20.dp))
        is SetupOperationState.Waiting, is SetupOperationState.RetryScheduled -> Text(
            text = operationStatus(stage.operationState),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is SetupOperationState.Running -> Unit
    }
}

@Composable
private fun operationStatus(operation: SetupOperationState): String = stringResource(when (operation) {
    SetupOperationState.NeverRun -> R.string.setup_not_run
    is SetupOperationState.Waiting -> R.string.setup_waiting
    is SetupOperationState.Running -> R.string.setup_running
    is SetupOperationState.RetryScheduled -> R.string.setup_retry_scheduled
    is SetupOperationState.Succeeded -> R.string.setup_done
    is SetupOperationState.Failed -> R.string.setup_failed
    SetupOperationState.Cancelled -> R.string.setup_cancelled
    SetupOperationState.Skipped -> R.string.setup_skipped
    is SetupOperationState.RecoveryRequired -> R.string.setup_recovery_required
})

@Composable
private fun operationExplanation(operation: SetupOperationState): String? = when (operation) {
    is SetupOperationState.Waiting -> operation.reason
    is SetupOperationState.RetryScheduled -> operation.reason
    is SetupOperationState.Failed -> operation.reason
    is SetupOperationState.RecoveryRequired -> operation.reason ?: stringResource(R.string.setup_recovery_required)
    is SetupOperationState.Succeeded -> operation.detail
    else -> null
}

/**
 * The per-stage completion summary. Never "setup failed" — the stages are unrelated, and at most one
 * of them is what went wrong.
 *
 * The per-stage lines appear only once the run has finished. Rendering them mid-run listed every
 * unstarted stage as "not run" beneath rows that were already showing the same thing live, which on
 * device read as a summary of nothing. While stages are still going, the note that they continue in
 * the background is the only part worth saying.
 */
@Composable
fun SetupSummary(state: SetupUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.finished) {
            Text(
                text = stringResource(R.string.setup_settled),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            state.stages.forEach { stage ->
                Text(text = stringResource(R.string.setup_result_line, stage.title,
                    if (stage.available) operationStatus(stage.operationState) else stringResource(R.string.setup_unavailable)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.pendingCount > 0) {
            Text(text = pluralStringResource(R.plurals.setup_pending_count, state.pendingCount, state.pendingCount),
                style = MaterialTheme.typography.bodySmall)
        }
        if (state.pendingCount > 0) {
            Text(
                text = stringResource(R.string.setup_background),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "Start setup" / "Skip setup", shared so both surfaces present the same pair of choices. */
@Composable
fun SetupActions(
    state: SetupUiState,
    startLabel: String,
    onStart: () -> Unit,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onSkip?.let {
            // Offered throughout, including while stages run: setup must never be a trap.
            TextButton(onClick = it) { Text(stringResource(if (state.running || state.finished)
                R.string.setup_continue_later else R.string.setup_skip)) }
        }
        Button(
            onClick = onStart,
            enabled = state.canStart,
            modifier = Modifier.weight(1f),
        ) {
            Text(startLabel)
        }
    }
}
