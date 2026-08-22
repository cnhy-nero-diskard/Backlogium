package com.example.backlogium.ui.settings.hidden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.repo.HiddenGameEntry
import com.example.backlogium.data.repo.NonGameCandidate
import com.example.backlogium.ui.components.GameIcon
import com.example.backlogium.ui.components.VisibilityChangeDialog
import com.example.backlogium.ui.util.UiFormat

/**
 * The hidden-games section (add-hidden-games): what is hidden, how to get it back, and the
 * non-game bulk review.
 *
 * This screen is deliberately the one place a hidden game is named. It stays reachable however
 * much of the library is hidden, because hiding without a way back is a trap rather than a
 * feature — and every unhide discloses its XP effect exactly as the hide that caused it did.
 */
@Composable
fun HiddenGamesScreen(viewModel: HiddenGamesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.nonGameCandidates.isNotEmpty()) {
            item {
                NonGameReviewCard(
                    candidates = state.nonGameCandidates,
                    open = state.reviewOpen,
                    selected = state.selectedCandidates,
                    busy = state.previewing,
                    onOpen = viewModel::openNonGameReview,
                    onClose = viewModel::closeNonGameReview,
                    onToggle = viewModel::toggleCandidate,
                    onHideSelected = viewModel::requestBulkHide,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (state.hidden.isEmpty()) {
                        "Hidden games"
                    } else {
                        "Hidden games (${state.hidden.size})"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (state.hidden.isNotEmpty()) {
                    TextButton(onClick = viewModel::requestUnhideAll, enabled = !state.previewing) {
                        Text("Unhide all")
                    }
                }
            }
        }

        if (state.nothingHidden) {
            // Said plainly rather than left as an unexplained empty list.
            item {
                Text(
                    text = "Nothing is hidden. Hiding a game from its own screen removes it from " +
                        "the Library, search, collections, analytics, and XP — and it can always " +
                        "be brought back here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.hidden, key = { it.appId }) { entry ->
            HiddenGameRow(
                entry = entry,
                busy = state.previewing,
                onUnhide = { viewModel.requestUnhide(entry.appId) },
            )
        }
    }

    state.pendingEffect?.let { effect ->
        VisibilityChangeDialog(
            effect = effect,
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismiss,
        )
    }
}

@Composable
private fun HiddenGameRow(entry: HiddenGameEntry, busy: Boolean, onUnhide: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (entry.iconUrl.isNotBlank()) {
                GameIcon(entry.iconUrl)
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                )
                Text(
                    text = hiddenWhenLabel(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onUnhide, enabled = !busy) { Text("Unhide") }
        }
    }
}

private fun hiddenWhenLabel(entry: HiddenGameEntry): String {
    val when_ = "Hidden ${UiFormat.dateTime(entry.hiddenAt)}"
    return if (entry.fromBulkAction) "$when_ · non-game review" else when_
}

/**
 * The bulk offer. It proposes and never acts: store types are occasionally wrong, and a
 * misclassified game vanishing with its XP is exactly what the confirmation exists to prevent —
 * so each proposed item can be deselected before the group is confirmed.
 */
@Composable
private fun NonGameReviewCard(
    candidates: List<NonGameCandidate>,
    open: Boolean,
    selected: Set<Long>,
    busy: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onToggle: (Long) -> Unit,
    onHideSelected: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "${candidates.size} items in your library are applications or tools",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Steam reports these as something other than a game. Nothing is hidden " +
                    "until you confirm it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!open) {
                TextButton(onClick = onOpen) { Text("Review them") }
                return@Column
            }

            Spacer(Modifier.height(8.dp))
            candidates.forEachIndexed { index, candidate ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = candidate.appId in selected,
                        onCheckedChange = { onToggle(candidate.appId) },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = candidate.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = candidate.typeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("Cancel") }
                TextButton(
                    onClick = onHideSelected,
                    enabled = !busy && selected.isNotEmpty(),
                ) {
                    Text("Hide ${selected.size} selected")
                }
            }
        }
    }
}
