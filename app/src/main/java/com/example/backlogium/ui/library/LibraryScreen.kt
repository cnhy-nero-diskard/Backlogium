package com.example.backlogium.ui.library

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import coil.compose.AsyncImage
import com.example.backlogium.ui.components.EmptyState
import com.example.backlogium.ui.util.UiFormat
import kotlin.math.roundToInt

/** Mutable dialog state: which game is being edited and whether it is already a goal. */
private data class GoalDialogTarget(
    val appId: Long,
    val name: String,
    val currentTargetMinutes: Int,
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
                            currentTargetMinutes = game.targetMinutes,
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
                        currentTargetMinutes = 0,
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
            onSave = { minutes ->
                viewModel.tagGoal(target.appId, minutes)
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
            Column(Modifier.fillMaxWidth()) {
                Text(game.name, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { game.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${UiFormat.minutes(game.playtimeForever)} / " +
                        "${UiFormat.minutes(game.targetMinutes)} " +
                        "(${(game.progress * 100).roundToInt()}%)",
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
    AsyncImage(
        model = iconUrl,
        contentDescription = null,
        modifier = Modifier.size(40.dp),
    )
}

@Composable
private fun GoalDialog(
    target: GoalDialogTarget,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onUntag: () -> Unit,
) {
    var text by remember {
        mutableStateOf(if (target.currentTargetMinutes > 0) target.currentTargetMinutes.toString() else "")
    }
    val parsed = text.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.isGoal) "Edit goal" else "Set as goal") },
        text = {
            Column {
                Text(target.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit) },
                    label = { Text("Target (minutes)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onSave) },
                enabled = parsed != null && parsed > 0,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            if (target.isGoal) {
                TextButton(onClick = onUntag) { Text("Untag") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
