package com.example.backlogium.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.annotation.PluralsRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.R
import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.ui.util.UiFormat
import kotlinx.coroutines.delay

@Composable
fun CloudActivityScreen(viewModel: HistoryViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.refreshStatusTime() }
    RefreshCloudStatusTimeOnResume(viewModel::refreshStatusTime)
    CloudActivityContent(
        state = state,
        onStatusTimeRefresh = viewModel::refreshStatusTime,
        onOpenSession = { item ->
            viewModel.revealSession(item)
            onBack()
        },
        onBack = onBack,
    )
}

@Composable
internal fun RefreshCloudStatusTimeOnResume(onRefresh: () -> Unit) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME, onEvent = onRefresh)
}

@Composable
internal fun CloudActivityContent(
    state: HistoryUiState,
    onOpenSession: (HistoryContribution) -> Unit = {},
    onBack: () -> Unit = {},
    onStatusTimeRefresh: () -> Unit = {},
) {
    LaunchedEffect(state.cloudReadSummary.latestObservationAt, state.statusNow) {
        val untilStale = observationMillisUntilStale(state.cloudReadSummary, state.statusNow)
            ?: return@LaunchedEffect
        delay(untilStale)
        onStatusTimeRefresh()
    }
    val activity = historyCloudActivity(state.days, state.cloudReaderConfigured)
    val loadingDescription = stringResource(R.string.history_cloud_loading)
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.history_cloud_back)) }
        if (state.loading) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = loadingDescription
                    },
                )
            }
        } else if (state.cloudReaderConfigured) {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                item(key = "cloud-activity-heading") {
                    Text(stringResource(R.string.history_cloud_activity),
                        style = MaterialTheme.typography.headlineSmall)
                    if (state.windowStartDate.isNotBlank() && state.today.isNotBlank()) {
                        Text(
                            stringResource(
                                R.string.history_cloud_window_scope,
                                formatHistoryDate(state.windowStartDate),
                                formatHistoryDate(state.today),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (activity.hasContributions) {
                        Text(stringResource(R.string.history_cloud_activity_overlap),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (activity.hasContributions) {
                    cloudGroup(CloudContributionGroup.RECOVERED, activity.recovered, onOpenSession)
                    cloudGroup(CloudContributionGroup.TIMED, activity.timed, onOpenSession)
                } else {
                    item(key = "cloud-activity-empty") {
                        Text(
                            stringResource(R.string.history_cloud_no_contributions),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                        )
                    }
                }
                item(key = "cloud-reader-status") {
                    CloudReaderStatus(state.cloudReadSummary, state.statusNow)
                }
            }
        }
    }
}

internal fun observationOlderThanDay(summary: CloudReadSummary, now: Long): Boolean =
    summary.latestObservationAt?.let { now - it > OBSERVATION_STALE_AFTER_MILLIS } ?: false

internal fun observationMillisUntilStale(summary: CloudReadSummary, now: Long): Long? {
    val latestObservationAt = summary.latestObservationAt ?: return null
    val elapsed = (now - latestObservationAt).coerceAtLeast(0L)
    return (OBSERVATION_STALE_AFTER_MILLIS + 1L - elapsed).takeIf { it > 0L }
}

private const val OBSERVATION_STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L

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
    group: CloudContributionGroup,
    entries: List<HistoryContribution>,
    onOpen: (HistoryContribution) -> Unit,
) {
    item {
        Text(pluralStringResource(group.countResource, entries.size, entries.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    }
    items(entries, key = { "${group.name}-${it.session.id}-${it.date}" }) { item ->
        val partial = if (group == CloudContributionGroup.RECOVERED) {
            item.session.cloudContribution.recoveredSharedPlay == ContributionState.PARTIAL
        } else {
            item.session.cloudContribution.timingInformedSteamPlay == ContributionState.PARTIAL
        }
        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.history_cloud_open_session),
                onClick = { onOpen(item) })) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.game.name, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(when {
                    group == CloudContributionGroup.RECOVERED && partial -> R.string.history_cloud_recovered_partial
                    group == CloudContributionGroup.RECOVERED -> R.string.history_cloud_recovered
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

private enum class CloudContributionGroup(@param:PluralsRes val countResource: Int) {
    RECOVERED(R.plurals.history_cloud_recovered_contributions),
    TIMED(R.plurals.history_cloud_timed_contributions),
}
