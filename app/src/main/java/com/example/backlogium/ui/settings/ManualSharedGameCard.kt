package com.example.backlogium.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.BrandSteam
import compose.icons.tablericons.CircleCheck

@Composable
internal fun ManualSharedGameCard(state: SettingsUiState, actions: SettingsActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Paste a Steam Store link or app ID. Backlogium checks ownership, imports an eligible borrowed game, and tests whether Steam returns achievement data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.manualSharedGameInput,
                onValueChange = actions.onManualSharedGameInputChanged,
                modifier = Modifier.fillMaxWidth().testTag("settings-manual-shared-game-input"),
                label = { Text("Steam Store URL or app ID") },
                singleLine = true,
                enabled = state.configured && !state.manualSharedGameBusy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Button(
                onClick = actions.onImportManualSharedGame,
                enabled = state.configured && state.manualSharedGameInput.isNotBlank() && !state.manualSharedGameBusy,
                modifier = Modifier.testTag("settings-manual-shared-game-import"),
            ) {
                if (state.manualSharedGameBusy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (state.manualSharedGameBusy) "Checking" else "Check and import")
            }
            if (!state.configured) Text("Connect a Steam account first.")
            val feedback = state.manualSharedGameFeedback
            AnimatedVisibility(
                visible = feedback != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                feedback?.let { ManualSharedGameFeedbackCard(it) }
            }
        }
    }
}

@Composable
private fun ManualSharedGameFeedbackCard(feedback: ManualImportFeedback) {
    val containerColor = when (feedback.tone) {
        ManualImportFeedbackTone.SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        ManualImportFeedbackTone.INFO -> MaterialTheme.colorScheme.secondaryContainer
        ManualImportFeedbackTone.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (feedback.tone) {
        ManualImportFeedbackTone.SUCCESS -> MaterialTheme.colorScheme.onPrimaryContainer
        ManualImportFeedbackTone.INFO -> MaterialTheme.colorScheme.onSecondaryContainer
        ManualImportFeedbackTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
    }
    val icon = when (feedback.tone) {
        ManualImportFeedbackTone.SUCCESS -> TablerIcons.CircleCheck
        ManualImportFeedbackTone.INFO -> TablerIcons.BrandSteam
        ManualImportFeedbackTone.ERROR -> TablerIcons.AlertCircle
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings-manual-shared-game-feedback"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(32.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = feedback.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    text = feedback.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
    }
}
