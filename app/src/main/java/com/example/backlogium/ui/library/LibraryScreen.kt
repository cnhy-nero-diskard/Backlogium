package com.example.backlogium.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.clickable
import coil.compose.SubcomposeAsyncImage
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
fun LibraryScreen(viewModel: LibraryViewModel = hiltViewModel()) {
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
        GoalDialog(
            target = target,
            onDismiss = { dialogTarget = null },
            onTag = {
                viewModel.tagGoal(target.appId)
                dialogTarget = null
            },
            onUntag = {
                viewModel.untagGoal(target.appId)
                dialogTarget = null
            },
        )
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
            Column {
                Text(game.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = UiFormat.minutes(game.playtimeForever) + " played",
                    style = MaterialTheme.typography.bodySmall,
                )
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
            }
        }
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
 * Confirm marking or unmarking a game as a goal. No typed target is collected (restyle-fixes):
 * completion lengths will come from HowLongToBeat, so the dialog is purely a flag toggle.
 */
@Composable
private fun GoalDialog(
    target: GoalDialogTarget,
    onDismiss: () -> Unit,
    onTag: () -> Unit,
    onUntag: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.isGoal) "Remove goal" else "Set as goal") },
        text = {
            Text(
                text = if (target.isGoal) {
                    "Remove \"${target.name}\" from your goal games?"
                } else {
                    "Mark \"${target.name}\" as a goal game?"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
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
