package com.example.backlogium.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.ui.util.UiFormat
import compose.icons.TablerIcons
import compose.icons.tablericons.BrandSteam
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Download
import compose.icons.tablericons.Pencil

/**
 * The app's administration surface: the Steam account, sync, data, and rule-configuration
 * controls that Home used to carry alongside its progress cards.
 *
 * Everything here renders from locally stored state, so the screen is fully usable offline.
 * Unlike the profile header — which hides entirely while unconfigured — a tab in the navigation
 * bar cannot disappear without the bar reflowing, so the unconfigured state is a route into
 * onboarding rather than a dead end. The rule controls stay editable either way: they are local
 * preferences and need no credentials.
 */
@Composable
fun SettingsScreen(
    onEditCredentials: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onEditCredentials = onEditCredentials,
        actions = remember(viewModel) {
            SettingsActions(
                onSyncNow = viewModel::syncNow,
                onFieldChanged = viewModel::onFieldChanged,
                onQuestModeChanged = viewModel::onQuestModeChanged,
                onAdvancedExpandedChanged = viewModel::setAdvancedExpanded,
                onRequestSave = viewModel::requestSave,
                onDiscardChanges = viewModel::discardChanges,
                onConfirmSave = viewModel::confirmSave,
                onDismissConfirmation = viewModel::dismissConfirmation,
                onImportHistory = viewModel::importSteamHistory,
                onResetHistoryImport = viewModel::resetHistoryImport,
            )
        },
    )
}

/** Every action the screen can raise, so the rendering half stays free of the view model. */
data class SettingsActions(
    val onSyncNow: () -> Unit,
    val onFieldChanged: (RuleField, String) -> Unit,
    val onQuestModeChanged: (QuestMode) -> Unit,
    val onAdvancedExpandedChanged: (Boolean) -> Unit,
    val onRequestSave: () -> Unit,
    val onDiscardChanges: () -> Unit,
    val onConfirmSave: () -> Unit,
    val onDismissConfirmation: () -> Unit,
    val onImportHistory: () -> Unit,
    val onResetHistoryImport: () -> Unit,
)

/** The stateless half: renders [state] and raises [actions]. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEditCredentials: () -> Unit,
    actions: SettingsActions,
) {
    if (state.loading) return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionHeader("Account")
        SteamAccountCard(
            configured = state.configured,
            steamId = state.steamId,
            apiKeyMasked = state.apiKeyMasked,
            onEdit = onEditCredentials,
        )

        SectionHeader("Sync")
        SyncCard(
            lastSyncAt = state.lastSyncAt,
            syncing = state.isSyncing,
            onSyncNow = actions.onSyncNow,
        )

        SectionHeader("Daily quest")
        DailyQuestCard(state = state, actions = actions)

        SectionHeader("Data")
        HistoryImportCard(
            imported = state.historyImported,
            importing = state.isImportingHistory,
            onImport = actions.onImportHistory,
            onReset = actions.onResetHistoryImport,
        )

        SectionHeader("Advanced")
        AdvancedCard(state = state, actions = actions)

        RuleSaveBar(state = state, actions = actions)
    }

    state.confirmation?.let { confirmation ->
        RuleChangeDialog(
            confirmation = confirmation,
            onConfirm = actions.onConfirmSave,
            onDismiss = actions.onDismissConfirmation,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * The active SteamID and a masked API key, with an action that reopens onboarding. The raw key
 * never reaches this composable — [apiKeyMasked] is already redacted upstream. While
 * unconfigured the same card becomes the way into onboarding.
 */
@Composable
private fun SteamAccountCard(
    configured: Boolean,
    steamId: String,
    apiKeyMasked: String,
    onEdit: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.BrandSteam,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Steam account", style = MaterialTheme.typography.titleMedium)
                if (configured) {
                    Text(
                        text = "SteamID $steamId",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "API key $apiKeyMasked",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "Not connected — sync, playtime, and achievements are unavailable " +
                            "until you connect an account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (configured) {
                TextButton(onClick = onEdit) {
                    Icon(
                        imageVector = TablerIcons.Pencil,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
            } else {
                Button(onClick = onEdit) { Text("Connect") }
            }
        }
    }
}

/** Last successful sync plus the manual trigger, disabled while a sync is already in flight. */
@Composable
private fun SyncCard(lastSyncAt: Long, syncing: Boolean, onSyncNow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (syncing) "Syncing…" else "Last sync: ${UiFormat.dateTime(lastSyncAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSyncNow, enabled = !syncing) { Text("Sync now") }
        }
    }
}

@Composable
private fun DailyQuestCard(state: SettingsUiState, actions: SettingsActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuleTextField(RuleField.QUEST_GOAL_MINUTES, state, actions)

            Column {
                Text("Counts toward the quest", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.draft.questMode == mode,
                            onClick = { actions.onQuestModeChanged(mode) },
                            label = { Text(questModeLabel(mode)) },
                        )
                    }
                }
            }

            RuleTextField(RuleField.STREAK_GRACE_DAYS, state, actions)
            Text(
                text = "Grace forgives that many missed days before a streak breaks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The advanced rules, collapsed by default. The controls are composed only while expanded, so
 * an unexpanded section costs nothing and cannot be interacted with by accident.
 */
@Composable
private fun AdvancedCard(state: SettingsUiState, actions: SettingsActions) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.onAdvancedExpandedChanged(!state.advancedExpanded) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("XP and level curve", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Changing these recalculates your total XP and level.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (state.advancedExpanded) {
                        TablerIcons.ChevronUp
                    } else {
                        TablerIcons.ChevronDown
                    },
                    contentDescription = if (state.advancedExpanded) "Collapse" else "Expand",
                )
            }

            AnimatedVisibility(visible = state.advancedExpanded) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RuleField.entries.filter { it.advanced }.forEach { field ->
                        RuleTextField(field, state, actions)
                    }
                }
            }
        }
    }
}

/** A single numeric rule input, rejecting values the engine cannot meaningfully use. */
@Composable
private fun RuleTextField(
    field: RuleField,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val error = state.draft.errorFor(field)
    OutlinedTextField(
        value = state.draft.values[field].orEmpty(),
        onValueChange = { actions.onFieldChanged(field, it) },
        label = { Text(field.label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Save / discard for the pending rule edit. Save is only offered once the draft is both valid
 * and different from what is stored; while the preview recompute runs it shows progress rather
 * than blocking the tap.
 */
@Composable
private fun RuleSaveBar(state: SettingsUiState, actions: SettingsActions) {
    if (!state.dirty && !state.hasInvalidField) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = actions.onRequestSave,
            enabled = state.dirty && !state.previewing,
        ) {
            if (state.previewing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Save rules")
            }
        }
        TextButton(onClick = actions.onDiscardChanges) { Text("Discard") }
    }
}

/**
 * States the concrete effect of the pending rule change before any of it lands.
 *
 * Deriving every gamification value from raw inputs means a rule change re-evaluates the
 * player's entire recorded history, so this names the real numbers the recompute produced —
 * not a generic "this may affect your progress". The longest streak shown is the protected
 * high-water value, which is what will actually be written.
 */
@Composable
private fun RuleChangeDialog(
    confirmation: RuleChangeConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Apply new rules?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Every day you have recorded is re-evaluated under the new rules.")
                if (confirmation.kind.questRules) {
                    Text(
                        text = "Current streak: ${confirmation.currentStreakBefore} → " +
                            "${confirmation.currentStreakAfter} days",
                    )
                    Text(
                        text = "Longest streak: ${confirmation.longestStreakBefore} → " +
                            "${confirmation.longestStreakAfter} days " +
                            "(a record already earned is never lowered)",
                    )
                }
                if (confirmation.kind.advancedRules) {
                    Text(
                        text = "Total XP: ${confirmation.totalXpBefore} → " +
                            "${confirmation.totalXpAfter}",
                    )
                    Text(
                        text = "Level: ${confirmation.levelBefore} → ${confirmation.levelAfter}",
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One-time "Import Steam history" control (add-playtime-backfill). Before importing it offers
 * the action behind a confirmation that spells out the effect (counts past playtime toward XP,
 * one-time, matched games capped / unmatched counted in full). After importing it reflects the
 * completed state and offers a reset that undoes the import so it can be run again.
 */
@Composable
private fun HistoryImportCard(
    imported: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onReset: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Steam history", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (imported) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = TablerIcons.CircleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "History imported",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = "Past playtime already counts toward your XP.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = !importing,
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Reset import")
                    }
                }
            } else {
                Text(
                    text = "Count your pre-install Steam playtime toward XP. One-time only.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showConfirm = true },
                    enabled = !importing,
                ) {
                    if (importing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = TablerIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Import Steam history")
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Import Steam history?") },
            text = {
                Text(
                    "This counts your past Steam playtime toward XP and can only be done once. " +
                        "Games matched to HowLongToBeat are capped by the usual taper; unmatched " +
                        "games count their full playtime.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onImport()
                    },
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset imported history?") },
            text = {
                Text(
                    "This removes imported past playtime from your XP; your level drops back to " +
                        "reflect tracked playtime only. Streaks and tracked sessions are kept, and " +
                        "you can import again afterward.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onReset()
                    },
                ) {
                    Text("Reset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun questModeLabel(mode: QuestMode) = when (mode) {
    QuestMode.ANY -> "Any game"
    QuestMode.GOAL_ONLY -> "Goal games only"
}
