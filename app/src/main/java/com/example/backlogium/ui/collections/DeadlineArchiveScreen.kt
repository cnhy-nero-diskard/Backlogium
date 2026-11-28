package com.example.backlogium.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.domain.DeadlineArchive
import com.example.backlogium.domain.DeadlineArchivedMember
import com.example.backlogium.domain.DeadlineCollectionIdentity
import com.example.backlogium.domain.DeadlineEvidenceGap
import com.example.backlogium.domain.DeadlineOutcome
import com.example.backlogium.domain.label
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import java.time.Instant
import java.time.ZoneId

@Composable
fun DeadlineArchiveScreen(
    onBack: () -> Unit,
    onOpen: (DeadlineCollectionIdentity) -> Unit,
    viewModel: DeadlineArchiveViewModel = hiltViewModel(),
) {
    val archives by viewModel.archives.collectAsStateWithLifecycle(initialValue = null)
    val hidden by viewModel.hiddenAppIds.collectAsStateWithLifecycle(initialValue = null)
    val selected = viewModel.archiveIdentity
    val archive = archives?.firstOrNull { it.identity == selected }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(TablerIcons.ArrowBack, contentDescription = "Back") }
            Text(archive?.name ?: "Archived collections", style = MaterialTheme.typography.titleLarge)
        }
        when {
            archives == null -> Text("Loading archives", Modifier.padding(16.dp))
            archive != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeadlineResultSummary(archive)
                        val day = Instant.ofEpochMilli(archive.finalizedAt).atZone(ZoneId.of(archive.zone)).toLocalDate()
                        Text("Archived $day")
                    }
                }
                items(archive.members, key = { it.appId }) { member ->
                    DeadlineMemberEvidence(member, hidden)
                }
            }
            selected != null -> Text("This archive is unavailable.", Modifier.padding(16.dp))
            archives.orEmpty().isEmpty() -> Text("No archived collections yet.", Modifier.padding(16.dp))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(archives.orEmpty(), key = { "${it.identity.originId}-${it.identity.createdAt}" }) { result ->
                    Card(onClick = { onOpen(result.identity) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(result.name, style = MaterialTheme.typography.titleMedium)
                            Text("${result.targetDate} · ${result.outcome.label()}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FinishDeadlineDialog(
    archive: DeadlineArchive,
    hidden: Set<Long>?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finish and archive?") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("This saves the deadline result and removes the collection from active use. Archiving is permanent.")
                DeadlineResultSummary(archive)
                archive.members.forEach { DeadlineMemberEvidence(it, hidden) }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) { Text(if (busy) "Archiving…" else "Archive collection") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun DeadlineResultSummary(archive: DeadlineArchive) {
    Text(archive.outcome.label(), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    Text("Deadline ${archive.targetDate} (${archive.zone})")
    Text("HowLongToBeat basis · ${archive.timeBasis.label()}")
    Text("${archive.evaluatedMembers.size} of ${archive.members.size} cutoff members evaluated")
    archive.gaps.forEach { gap ->
        Text(when (gap) {
            DeadlineEvidenceGap.GOAL_HISTORY -> "The goal at the cutoff could not be confirmed."
            DeadlineEvidenceGap.MEMBERSHIP_HISTORY -> "Membership history does not cover the cutoff."
            DeadlineEvidenceGap.VISIBILITY_HISTORY -> "Some members’ visibility at the cutoff is unknown."
            DeadlineEvidenceGap.EMPTY_MEMBERSHIP -> "The collection was empty at the cutoff."
            DeadlineEvidenceGap.ALL_MEMBERS_HIDDEN -> "All members were hidden at the cutoff."
            DeadlineEvidenceGap.ESTIMATE -> "Some selected estimates were unavailable."
            DeadlineEvidenceGap.PLAYTIME -> "Some credited playtime could not be established at the cutoff."
            DeadlineEvidenceGap.CUTOFF_AMBIGUITY -> "A collection update crossed the cutoff, so its committed state is uncertain."
        })
    }
}

@Composable
private fun DeadlineMemberEvidence(member: DeadlineArchivedMember, hidden: Set<Long>?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (hidden == null || member.appId in hidden) "Hidden game" else member.name ?: "Game ${member.appId}",
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(when (member.hiddenAtCutoff) {
                true -> "Hidden at cutoff · excluded from the goal"
                false -> "Visible at cutoff · included in the goal"
                null -> "Visibility at cutoff unavailable"
            })
            Text("Selected estimate · ${minutes(member.estimateMinutes)}")
            Text("Credited playtime · ${minutes(member.play?.creditedMinutes)}")
            Text("Remaining estimate · ${minutes(member.remainingMinutes)}")
            Text("Recorded sessions · ${member.recordedSessions ?: "Unavailable"}")
            Text("Recorded trophies · ${member.recordedTrophies ?: "Unavailable"}")
            if (member.play != null) Text("Playtime supported by Steam readings on both sides of the cutoff.")
            member.sessionPlay?.let {
                Text("Dated closed-session play lower bound · ${minutes(it.creditedMinutes)} (${it.sessionCount} sessions)")
                Text("This lower bound proves completion only when it reaches the selected estimate.")
            }
        }
    }
}

private fun minutes(value: Number?) = value?.let { "$it min" } ?: "Unavailable"

private fun DeadlineOutcome.label() = when (this) {
    DeadlineOutcome.ESTIMATED_MET -> "Estimated goal met"
    DeadlineOutcome.ESTIMATED_NOT_MET -> "Estimated goal not met"
    DeadlineOutcome.UNDETERMINED -> "Outcome undetermined"
}
