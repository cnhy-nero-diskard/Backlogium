package com.example.backlogium.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.gamification.Gamification
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad

/** Mutable dialog state: which game is being edited and whether it is already a goal. */
private data class GoalDialogTarget(
    val appId: Long,
    val name: String,
    val isGoal: Boolean,
)

@Composable
fun LibraryScreen(
    onOpenReview: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dialogTarget by remember { mutableStateOf<GoalDialogTarget?>(null) }

    if (!state.configured) {
        EmptyState(
            title = "Steam not configured",
            message = "Add your Steam credentials to local.properties and rebuild to load your library.",
        )
        return
    }

    if (state.goalGames.isEmpty() && state.backlog.isEmpty()) {
        EmptyState(
            title = "No games yet",
            message = "Once a sync completes, your Steam library appears here. " +
                "If it stays empty, your profile may be private.",
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        item {
            HltbControls(
                refreshing = state.refreshing,
                reviewCount = state.reviewCount,
                onRefresh = { viewModel.refreshHltb(force = false) },
                onForceRefresh = { viewModel.refreshHltb(force = true) },
                onOpenReview = onOpenReview,
            )
        }

        if (state.goalGames.isNotEmpty()) {
            item {
                SectionHeader("Goal games")
            }
            items(state.goalGames, key = { it.appId }) { game ->
                GoalGameRow(
                    game = game,
                    onClick = {
                        dialogTarget = GoalDialogTarget(
                            appId = game.appId,
                            name = game.name,
                            isGoal = true,
                        )
                    },
                )
            }
        }

        item { SectionHeader("Backlog") }
        items(state.backlog, key = { it.appId }) { game ->
            BacklogGameRow(
                game = game,
                onClick = {
                    dialogTarget = GoalDialogTarget(
                        appId = game.appId,
                        name = game.name,
                        isGoal = false,
                    )
                },
            )
        }
    }

    dialogTarget?.let { target ->
        // Read live status/op so the dialog reflects a lookup started from within it.
        val liveGoal = state.goalGames.firstOrNull { it.appId == target.appId }
        val liveBacklog = state.backlog.firstOrNull { it.appId == target.appId }
        GoalDialog(
            target = target,
            hltbStatus = liveGoal?.hltbStatus ?: liveBacklog?.hltbStatus,
            fetchOp = liveGoal?.fetchOp ?: liveBacklog?.fetchOp,
            onDismiss = { dialogTarget = null },
            onTag = {
                viewModel.tagGoal(target.appId)
                dialogTarget = null
            },
            onUntag = {
                viewModel.untagGoal(target.appId)
                dialogTarget = null
            },
            onRefresh = { viewModel.refreshGame(target.appId, target.name) },
        )
    }
}

/**
 * Batch HLTB refresh control (with a force-all option) plus the match-review entry point.
 * Reflects the running state via [refreshing]; surfaces the pending [reviewCount].
 */
@Composable
private fun HltbControls(
    refreshing: Boolean,
    reviewCount: Int,
    onRefresh: () -> Unit,
    onForceRefresh: () -> Unit,
    onOpenReview: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onRefresh,
                enabled = !refreshing,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Refreshing…")
                } else {
                    Text("Refresh HLTB library")
                }
            }
            OutlinedButton(
                onClick = onForceRefresh,
                enabled = !refreshing,
            ) {
                Text("Force all")
            }
        }
        TextButton(
            onClick = onOpenReview,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                if (reviewCount > 0) "Review HLTB matches ($reviewCount)" else "Review HLTB matches",
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun GoalGameRow(game: GoalGameUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameIcon(game.iconUrl)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(game.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = UiFormat.minutes(game.playtimeForever) + " played",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Goal progress against the HowLongToBeat Main Story length, shown only when one
                // is available (add-hltb-integration). No length yet → no completion progress.
                game.mainStoryMinutes?.let { mainStory ->
                    val fraction = Gamification.goalProgress(game.playtimeForever, mainStory)
                        .fraction
                        .toFloat()
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${UiFormat.minutes(game.playtimeForever)} / " +
                            "${UiFormat.minutes(mainStory)} to beat",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(4.dp))
                HltbStatusLabel(status = game.hltbStatus, op = game.fetchOp)
            }
        }
    }
}

@Composable
private fun BacklogGameRow(game: BacklogGameUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GameIcon(game.iconUrl)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(game.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = UiFormat.minutes(game.playtimeForever) + " played",
                    style = MaterialTheme.typography.bodySmall,
                )
                // Only surface HLTB state for backlog games once there is something to report,
                // so the common "no data" case stays uncluttered.
                if (game.hltbStatus != null || game.fetchOp != null) {
                    Spacer(Modifier.height(4.dp))
                    HltbStatusLabel(status = game.hltbStatus, op = game.fetchOp)
                }
            }
        }
    }
}

/** Compact, live HLTB state for a game: in-flight, failed, or the persisted match status. */
@Composable
private fun HltbStatusLabel(
    status: HltbMatchStatus?,
    op: HltbFetchOp?,
    modifier: Modifier = Modifier,
) {
    when {
        op == HltbFetchOp.IN_PROGRESS -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Looking up HowLongToBeat…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        op == HltbFetchOp.FAILED -> Text(
            text = "HowLongToBeat lookup failed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )

        status == HltbMatchStatus.RESOLVED -> Text(
            text = "HowLongToBeat matched",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )

        status == HltbMatchStatus.NEEDS_REVIEW -> Text(
            text = "Needs match review",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = modifier,
        )

        status == HltbMatchStatus.UNMATCHED -> Text(
            text = "No HowLongToBeat match",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        else -> Text(
            text = "No HowLongToBeat data yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
    }
}

@Composable
private fun GameIcon(iconUrl: String) {
    val shape = RoundedCornerShape(8.dp)
    SubcomposeAsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = Modifier
            .size(40.dp)
            .clip(shape),
        // Themed placeholder while the Steam CDN thumbnail loads.
        loading = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        },
        // Themed fallback (generic controller glyph) when the image fails to load.
        error = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TablerIcons.DeviceGamepad,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    )
}

/**
 * Confirm marking or unmarking a game as a goal (no typed target — completion lengths come
 * from HowLongToBeat), and surface/refresh this game's HLTB state: the current match status,
 * plus a "Refresh HowLongToBeat" action that forces a fresh single-game lookup.
 */
@Composable
private fun GoalDialog(
    target: GoalDialogTarget,
    hltbStatus: HltbMatchStatus?,
    fetchOp: HltbFetchOp?,
    onDismiss: () -> Unit,
    onTag: () -> Unit,
    onUntag: () -> Unit,
    onRefresh: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.isGoal) "Remove goal" else "Set as goal") },
        text = {
            Column {
                Text(
                    text = if (target.isGoal) {
                        "Remove \"${target.name}\" from your goal games?"
                    } else {
                        "Mark \"${target.name}\" as a goal game?"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                HltbStatusLabel(status = hltbStatus, op = fetchOp)
                TextButton(
                    onClick = onRefresh,
                    enabled = fetchOp != HltbFetchOp.IN_PROGRESS,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text("Refresh HowLongToBeat")
                }
            }
        },
        confirmButton = {
            if (target.isGoal) {
                TextButton(onClick = onUntag) { Text("Remove") }
            } else {
                TextButton(onClick = onTag) { Text("Set goal") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
