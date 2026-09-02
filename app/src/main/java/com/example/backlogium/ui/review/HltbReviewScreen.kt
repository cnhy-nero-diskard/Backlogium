package com.example.backlogium.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.components.HltbCandidateCard
import com.example.backlogium.ui.components.HltbLengthsRow

/**
 * HLTB match-center surface — one Steam game at a time with an adaptive candidate grid.
 * Presents Steam identity separately from HLTB candidates, with navigation between games,
 * broader-search rescue for unmatched games, and manual HLTB link entry.
 *
 * [initialAppId], when present, seeds the selection on entry — used when a caller (e.g. a
 * single-game lookup from the Library) navigates here already knowing which game needs attention,
 * so the user lands directly on it instead of the default first-in-queue game. Resolving *that*
 * game (and only that game — browsing to a different one first does not) then calls [onDone],
 * so a single-game deep link returns the user straight to where they came from instead of leaving
 * them in a multi-game review surface they never asked to browse.
 */
@Composable
fun HltbReviewScreen(
    initialAppId: Long? = null,
    onDone: () -> Unit = {},
    viewModel: HltbReviewViewModel = hiltViewModel(),
) {
    val state by viewModel.matchCenterState.collectAsStateWithLifecycle()

    // A scoped single-game route is complete when its requested app is absent from the queue once
    // loading has finished (see [isScopedAppMissing]): finish the route instead of presenting
    // whatever the ordinary selection clamped onto, so the user is never stranded reviewing an
    // unrelated game. `initialAppId` stays fixed for the route's lifetime, so this effect does
    // not re-run if Room emits a different queue — the check re-evaluates through `state` alone.
    // The early return keeps the clamped frame from ever rendering below.
    if (initialAppId != null && state.scopedAppMissing) {
        LaunchedEffect(Unit) { onDone() }
        return
    }

    LaunchedEffect(initialAppId) {
        if (initialAppId != null) viewModel.selectGame(initialAppId)
    }

    if (!state.loading && state.total == 0) {
        EmptyState(
            title = "Nothing to review or rescue",
            message = "Games needing a match or with no match appear here. Try a HowLongToBeat lookup from the Library.",
        )
        return
    }

    val selected = state.selectedGame
    if (selected == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val broaderState = state.broaderStates[selected.appId] ?: BroaderSearchUiState()
    val manualState = state.manualLinkStates[selected.appId] ?: ManualLinkUiState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Steam header separate from HLTB candidates
        Card(modifier = Modifier.fillMaxWidth()) {
            SteamGameHeader(
                game = selected,
                position = state.currentPosition,
                total = state.total,
            )
        }

        // Navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = viewModel::selectPrevious,
                enabled = state.selectedIndex > 0,
            ) { Text("Previous") }
            Text(
                "${state.currentPosition} / ${state.total}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = viewModel::selectNext,
                enabled = state.selectedIndex < state.total - 1,
            ) { Text("Next") }
        }

        // Candidate grid (distinct adaptive cards) — or broader rescue when unmatched
        if (selected.candidates.isNotEmpty()) {
            Text(
                when (selected.matchStatus) {
                    HltbMatchStatus.NEEDS_REVIEW -> "Choose the correct HowLongToBeat entry:"
                    else -> "Candidates — verify and select the correct match:"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            // Adaptive grid: one column on narrow widths, additional minimum-width columns on
            // wider devices. Column count comes from adaptiveColumnCount (GridCells.Adaptive
            // semantics); the non-lazy grid lives inside the screen's single vertical scroll so
            // every candidate stays reachable.
            AdaptiveCandidateGrid(
                candidates = selected.candidates,
                onSelect = { candidate ->
                    // Completion/navigation is queue-driven (see the scoped check above): once
                    // the persist lands, the game leaves the queue and the route finishes.
                    viewModel.resolve(selected.appId, candidate)
                },
            )
            if (selected.candidates.any { it.source == com.example.backlogium.data.hltb.HltbCandidateSource.BROADER_SEARCH }) {
                Text(
                    "Broader-search results — please verify the correct game before confirming.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (selected.matchStatus == HltbMatchStatus.UNMATCHED) {
            // Unmatched rescue: Try broader search
            BroaderSearchSection(
                state = broaderState,
                onTryBroader = { viewModel.startBroaderSearch(selected.appId, selected.name) },
                onClear = { viewModel.clearBroaderState(selected.appId) },
            )
        }

        // Manual HLTB link entry (last-resort for unmatched and needs-review)
        ManualHltbLinkSection(
            gameName = selected.name,
            state = manualState,
            onInputChange = { viewModel.updateManualLinkInput(selected.appId, it) },
            onPreview = { viewModel.previewManualLink(selected.appId) },
            onDismissPreview = { viewModel.dismissManualLinkPreview(selected.appId) },
            onConfirm = {
                viewModel.confirmManualLink(selected.appId)
            },
            onClear = { viewModel.clearManualLink(selected.appId) },
        )
    }
}

@Composable
private fun AdaptiveCandidateGrid(
    candidates: List<com.example.backlogium.data.hltb.HltbCandidate>,
    onSelect: (com.example.backlogium.data.hltb.HltbCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Non-lazy adaptive grid: computes the column count from the available width (one column on
    // narrow viewports, additional minimum-width columns on wider ones) and lays cards out
    // row-wise inside the screen's outer vertical scroll, so every candidate stays reachable
    // without a nested scrolling container or a fixed-height estimate.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val columnCount = adaptiveColumnCount(maxWidth)
        // Every cell keeps the same geometry whether or not its row fills, so a partially
        // filled final row does not stretch its cards to full-row width.
        val cellWidth = (maxWidth - CandidateGridSpacing * (columnCount - 1)) / columnCount
        val rows = candidates.chunked(columnCount)
        Column(verticalArrangement = Arrangement.spacedBy(CandidateGridSpacing)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(CandidateGridSpacing)) {
                    row.forEach { candidate ->
                        HltbCandidateCard(
                            candidate = candidate,
                            onSelect = { onSelect(candidate) },
                            modifier = Modifier.width(cellWidth),
                        )
                    }
                }
            }
        }
    }
}

/** Gap between candidate-grid cells, shared by the layout and [adaptiveColumnCount]. */
private val CandidateGridSpacing = 12.dp

/**
 * Adaptive column count for the candidate grid, matching `GridCells.Adaptive(280.dp)` semantics
 * including the [CandidateGridSpacing] between columns: the largest count whose cells all keep
 * their 280dp minimum (`n * 280 + (n - 1) * spacing <= available width`), and never fewer than one.
 * Exposed (non-private) so unit tests can verify the production adaptive behavior directly.
 */
internal fun adaptiveColumnCount(availableWidthDp: Dp): Int {
    val slotWidth = 280.dp + CandidateGridSpacing
    return ((availableWidthDp + CandidateGridSpacing) / slotWidth).toInt().coerceAtLeast(1)
}

@Composable
private fun BroaderSearchSection(
    state: BroaderSearchUiState,
    onTryBroader: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("No candidates found for the original title.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Try broader search will look up relaxed title variants (editions, subtitles, numerals) — up to three additional queries.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                state.loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Searching broader titles…")
                    }
                }
                state.exhausted -> {
                    Text("Still no matches — broader search found nothing. Try a manual HLTB link below.")
                    TextButton(onClick = onClear) { Text("Dismiss") }
                }
                state.failed -> {
                    Text(
                        "Broader search failed (${state.failureClass?.name?.lowercase() ?: "unknown"}). You can retry.",
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onTryBroader) { Text("Retry") }
                        TextButton(onClick = onClear) { Text("Dismiss") }
                    }
                }
                else -> {
                    Button(onClick = onTryBroader, modifier = Modifier.fillMaxWidth()) {
                        Text("Try broader search")
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualHltbLinkSection(
    gameName: String,
    state: ManualLinkUiState,
    onInputChange: (String) -> Unit,
    onPreview: () -> Unit,
    onDismissPreview: () -> Unit,
    onConfirm: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manual HLTB link", style = MaterialTheme.typography.titleSmall)
            Text(
                "Paste a HowLongToBeat game link (https://howlongtobeat.com/game/{id}) as a last resort.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.input,
                onValueChange = onInputChange,
                label = { Text("HLTB game link") },
                placeholder = { Text("https://howlongtobeat.com/game/12345") },
                isError = state.validationError != null,
                supportingText = state.validationError?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Loading HLTB entry…")
                }
            } else {
                Button(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Preview link") }
            }
            if (state.notFound) {
                Text("HLTB page not found for that link.", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (state.failed) {
                Text(
                    "Lookup failed (${state.failureClass?.name?.lowercase() ?: "transport"}). You can retry after correcting the link.",
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPreview) { Text("Retry") }
                    TextButton(onClick = onClear) { Text("Dismiss") }
                }
            }
            state.preview?.let { preview ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Preview — compare with Steam title:", style = MaterialTheme.typography.labelMedium)
                        Text("Steam: $gameName", style = MaterialTheme.typography.bodySmall)
                        HltbCandidateCard(candidate = preview, onSelect = {}, showSelectionButton = false)
                        // Preview presentation only: no selection button on the card; the explicit
                        // Confirm match / Dismiss actions below are the only commit path.
                        Text("HLTB: ${preview.name}", style = MaterialTheme.typography.bodyMedium)
                        HltbLengthsRow(preview)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm match") }
                            OutlinedButton(onClick = onDismissPreview, modifier = Modifier.weight(1f)) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }
}
