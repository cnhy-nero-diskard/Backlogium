package com.example.backlogium.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.backlogium.R

@Composable
internal fun SettingsOverviewScreen(
    state: SettingsUiState,
    onOpenGroup: (SettingsGroup) -> Unit,
) {
    val summaries = settingsGroupSummaries(state)
    val scrollState = rememberSaveable(saver = ScrollState.Saver) { ScrollState(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_overview_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("settings-overview-title"),
        )
        Text(
            text = stringResource(R.string.settings_overview_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        summaries.forEach { summary ->
            SettingsSummaryRow(summary = summary, onClick = { onOpenGroup(summary.group) })
        }
    }
}

@Composable
private fun SettingsSummaryRow(
    summary: SettingsGroupSummary,
    onClick: () -> Unit,
) {
    val title = stringResource(summary.title.resId)
    val status = summary.status.resolveText()
    val nextAction = summary.nextAction.resolveText()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings-group-${summary.group.route.substringAfterLast('/')}"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    stateDescription = status
                }
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.settings_summary_status, status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (summary.attention) {
                        SettingsAttention.BLOCKING -> MaterialTheme.colorScheme.error
                        SettingsAttention.RECOMMENDED -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = stringResource(R.string.settings_summary_action, nextAction),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
