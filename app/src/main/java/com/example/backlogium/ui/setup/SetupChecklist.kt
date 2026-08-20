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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.NotificationPermissionRequest
import com.example.backlogium.work.setup.SetupOutcome
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
 * @param showRetry the Settings entry offers a per-stage re-run; the onboarding step does not, since
 *   nothing has an outcome to retry until the run it is watching has finished.
 */
@Composable
fun SetupChecklist(
    state: SetupUiState,
    onToggle: (String, Boolean) -> Unit,
    onRetry: (String) -> Unit,
    showRetry: Boolean,
    modifier: Modifier = Modifier,
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
                text = "Connect your Steam account first — every step below needs it.",
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
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
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

            (stage.outcome as? SetupOutcome.Failed)?.let { failed ->
                Text(
                    text = failed.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (showRetry && stage.available && stage.outcome !is SetupOutcome.NeverRun) {
                TextButton(onClick = { onRetry(stage.id) }, enabled = enabled) {
                    Text(if (stage.outcome is SetupOutcome.Succeeded) "Run again" else "Retry")
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
    if (progress != null) {
        Text(
            text = "${progress.processed} / ${progress.total}" +
                if (progress.label.isNotBlank()) " — ${progress.label}" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { progress.processed.toFloat() / progress.total.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OutcomeBadge(stage: SetupStageUi) {
    if (stage.running) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    when (stage.outcome) {
        SetupOutcome.Succeeded -> Icon(
            TablerIcons.CircleCheck,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )

        is SetupOutcome.Failed -> Icon(
            TablerIcons.AlertCircle,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )

        SetupOutcome.Skipped -> Icon(
            TablerIcons.Minus,
            contentDescription = "Skipped",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )

        SetupOutcome.NeverRun -> Spacer(Modifier.width(20.dp))
    }
}

/** The per-stage completion summary. Never "setup failed" — the stages are unrelated. */
@Composable
fun SetupSummary(state: SetupUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (state.detachedStillRunning) "Setup started" else "Setup complete",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        setupSummaryLines(state.stages).forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodySmall)
        }
        if (state.detachedStillRunning) {
            Text(
                text = "The remaining steps keep running in the background — you can use the app " +
                    "while they do.",
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
            TextButton(onClick = it) { Text("Skip setup") }
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
