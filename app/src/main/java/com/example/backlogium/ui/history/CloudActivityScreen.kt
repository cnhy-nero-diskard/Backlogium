package com.example.backlogium.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.R
import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.ui.util.UiFormat

@Composable
fun CloudActivityScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = historyCloudActivity(state.days, state.cloudReaderConfigured)
    if (state.loading) {
        CircularProgressIndicator()
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.history_cloud_back)) }
            Text(stringResource(R.string.history_cloud_activity), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.history_cloud_activity_overlap),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        cloudGroup(R.string.history_cloud_recovered_facts, activity.recovered) { item ->
            viewModel.revealSession(item)
            onBack()
        }
        cloudGroup(R.string.history_cloud_timed_facts, activity.timed) { item ->
            viewModel.revealSession(item)
            onBack()
        }
        if (state.cloudReaderConfigured) {
            item { CloudReaderStatus(state.cloudReadSummary, state.statusNow) }
        }
    }
}

internal fun observationOlderThanDay(summary: CloudReadSummary, now: Long): Boolean =
    summary.latestObservationAt?.let { now - it > 24L * 60 * 60 * 1000 } ?: false

@Composable
private fun CloudReaderStatus(summary: CloudReadSummary, now: Long) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.history_cloud_reader_status), style = MaterialTheme.typography.titleMedium)
        val outcome = when (summary.lastOutcome) {
            CloudReadSummaryOutcome.COMPLETE -> R.string.history_cloud_read_complete
            CloudReadSummaryOutcome.NO_NEW_DATA -> R.string.history_cloud_read_no_transitions
            CloudReadSummaryOutcome.PARTIAL -> R.string.history_cloud_read_partial
            CloudReadSummaryOutcome.FAILED -> R.string.history_cloud_read_failed
            null -> R.string.history_cloud_read_unknown
        }
        Text(stringResource(R.string.history_cloud_last_attempt,
            summary.lastAttemptAt?.let(UiFormat::dateTime) ?: stringResource(R.string.history_cloud_unknown),
            stringResource(outcome)))
        Text(stringResource(R.string.history_cloud_last_success,
            summary.lastSuccessAt?.let(UiFormat::dateTime) ?: stringResource(R.string.history_cloud_unknown)))
        Text(stringResource(R.string.history_cloud_last_observation,
            summary.latestObservationAt?.let(UiFormat::dateTime) ?: stringResource(R.string.history_cloud_unknown)))
        if (observationOlderThanDay(summary, now)) {
            Text(stringResource(R.string.history_cloud_observation_stale))
        }
        if (summary.lastSuccessHasMore == true) {
            Text(stringResource(R.string.history_cloud_coverage_partial))
        } else {
            Text(stringResource(R.string.history_cloud_coverage_unknown))
        }
        Text(stringResource(R.string.history_cloud_status_caution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.cloudGroup(
    titleRes: Int,
    entries: List<HistoryContribution>,
    onOpen: (HistoryContribution) -> Unit,
) {
    item {
        Text(stringResource(titleRes, entries.size), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    }
    items(entries, key = { "$titleRes-${it.session.id}-${it.date}" }) { item ->
        val partial = if (titleRes == R.string.history_cloud_recovered_facts) {
            item.session.cloudContribution.recoveredSharedPlay == ContributionState.PARTIAL
        } else {
            item.session.cloudContribution.timingInformedSteamPlay == ContributionState.PARTIAL
        }
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clickable(role = Role.Button, onClick = { onOpen(item) })) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.game.name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(when {
                    titleRes == R.string.history_cloud_recovered_facts && partial -> R.string.history_cloud_recovered_partial
                    titleRes == R.string.history_cloud_recovered_facts -> R.string.history_cloud_recovered
                    partial -> R.string.history_cloud_timed_partial
                    else -> R.string.history_cloud_timed
                }), style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.history_cloud_session_link,
                    formatHistoryDate(item.date), UiFormat.approxTime(item.session.startAt),
                    UiFormat.localizedMinutes(item.session.minutes)),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
