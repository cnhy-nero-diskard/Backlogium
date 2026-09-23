package com.example.backlogium.ui.settings

import android.net.Uri
import android.widget.Toast
import com.example.backlogium.BuildConfig
import com.example.backlogium.R
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.backlogium.data.backup.SnapshotMeta
import com.example.backlogium.data.repo.RemovedSharedGame
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.updates.AppUpdateState
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.ui.util.HapticPlayer
import com.example.backlogium.ui.util.UiFormat
import com.example.backlogium.ui.util.playIfNotSilent
import com.example.backlogium.ui.util.rememberHaptics
import com.example.backlogium.work.GenreEnrichmentStatus
import com.example.backlogium.work.SteamAssetDownloadStatus
import compose.icons.TablerIcons
import compose.icons.tablericons.BrandSteam
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.ChevronUp
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Download
import compose.icons.tablericons.Pencil
import compose.icons.tablericons.Upload
import kotlinx.coroutines.flow.collect

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
    onOpenDiagnostics: () -> Unit,
    onOpenSetup: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenHiddenGames: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    SettingsGraphScreen(viewModel = viewModel) { state, actions, _ ->
        SettingsScreen(
            state = state,
            onEditCredentials = onEditCredentials,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenSetup = onOpenSetup,
            onOpenUpdate = onOpenUpdate,
            onOpenHiddenGames = onOpenHiddenGames,
            actions = actions,
        )
    }
}

/**
 * Shared Settings-graph host. It owns activity-result launchers, transient dialogs, haptics, and
 * toasts so every overview/detail destination talks to one state holder and one action surface.
 */
@Composable
internal fun SettingsGraphScreen(
    viewModel: SettingsViewModel,
    content: @Composable (SettingsUiState, SettingsActions, HapticPlayer) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()

    LaunchedEffect(viewModel, haptics) {
        viewModel.hapticIntents.collect(haptics::playIfNotSilent)
    }
    val context = LocalContext.current
    LaunchedEffect(viewModel, context) {
        viewModel.toastMessages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::onExportBackup) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onImportBackupPicked) }
    val contributionExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val resolver = context.contentResolver
        if (uri != null) {
            viewModel.onContributionDestinationPicked(uri, resolver)
        } else {
            viewModel.onContributionExportCancelled()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.contributionExportRequests.collect { fileName -> contributionExportLauncher.launch(fileName) }
    }

    val actions = remember(viewModel, exportLauncher, importLauncher) {
        SettingsActions(
            onSyncNow = viewModel::syncNow,
            onReconcileNow = viewModel::reconcileNow,
            onDownloadSteamAssets = viewModel::downloadSteamAssets,
            onCancelSteamAssetDownload = viewModel::cancelSteamAssetDownload,
            onLiveMonitorEnabledChanged = viewModel::onLiveMonitorEnabledChanged,
            onFieldChanged = viewModel::onFieldChanged,
            onQuestModeChanged = viewModel::onQuestModeChanged,
            onAdvancedExpandedChanged = viewModel::setAdvancedExpanded,
            onRequestSave = viewModel::requestSave,
            onDiscardChanges = viewModel::discardChanges,
            onConfirmSave = viewModel::confirmSave,
            onDismissConfirmation = viewModel::dismissConfirmation,
            onImportHistory = viewModel::importSteamHistory,
            onResetHistoryImport = viewModel::resetHistoryImport,
            onAutoSnapshotEnabledChanged = viewModel::onAutoSnapshotEnabledChanged,
            onSnapshotRetentionCountChanged = viewModel::onSnapshotRetentionCountChanged,
            onSnapshotIntervalHoursChanged = viewModel::onSnapshotIntervalHoursChanged,
            onExportBackup = { exportLauncher.launch("backlogium-backup-${System.currentTimeMillis()}.json") },
            onImportBackup = { importLauncher.launch(arrayOf("application/json")) },
            onRestoreSnapshot = viewModel::onRestoreSnapshot,
            onDeleteSnapshot = viewModel::onDeleteSnapshot,
            onConfirmMismatchImport = viewModel::onConfirmMismatchImport,
            onDismissMismatchImport = viewModel::onDismissMismatchImport,
            onDismissBackupMessage = viewModel::onDismissBackupMessage,
            onCheckForUpdates = viewModel::checkForUpdates,
            onRestoreSharedGame = viewModel::restoreSharedGame,
            onManualSharedGameInputChanged = viewModel::onManualSharedGameInputChanged,
            onImportManualSharedGame = viewModel::importManualSharedGame,
            onCheckHltbDataset = viewModel::checkHltbDataset,
            onRequestContributionExport = viewModel::onRequestContributionExport,
            onDismissContributionDisclosure = viewModel::onDismissContributionDisclosure,
            onConfirmContributionDisclosure = viewModel::onConfirmContributionDisclosure,
            onVerifyCloudPresence = viewModel::verifyCloudPresence,
            onReadCloudPresence = viewModel::readCloudPresence,
            onRemoveCloudPresence = viewModel::removeCloudPresence,
            onRefileCloudPresence = viewModel::refileCloudPresence,
            onReverseCloudPresenceRefiling = viewModel::reverseCloudPresenceRefiling,
        )
    }

    content(state, actions, haptics)
    SettingsDialogs(state = state, actions = actions)
}

/** Every action the screen can raise, so the rendering half stays free of the view model. */
data class SettingsActions(
    val onSyncNow: () -> Unit,
    val onReconcileNow: () -> Unit,
    val onDownloadSteamAssets: (SteamAssetDownloadMode) -> Unit = {},
    val onCancelSteamAssetDownload: () -> Unit = {},
    val onLiveMonitorEnabledChanged: (Boolean) -> Unit,
    val onFieldChanged: (RuleField, String) -> Unit,
    val onQuestModeChanged: (QuestMode) -> Unit,
    val onAdvancedExpandedChanged: (Boolean) -> Unit,
    val onRequestSave: () -> Unit,
    val onDiscardChanges: () -> Unit,
    val onConfirmSave: () -> Unit,
    val onDismissConfirmation: () -> Unit,
    val onImportHistory: () -> Unit,
    val onResetHistoryImport: () -> Unit,
    val onAutoSnapshotEnabledChanged: (Boolean) -> Unit,
    val onSnapshotRetentionCountChanged: (Int) -> Unit,
    val onSnapshotIntervalHoursChanged: (Int) -> Unit,
    val onExportBackup: () -> Unit,
    val onImportBackup: () -> Unit,
    val onRestoreSnapshot: (SnapshotMeta) -> Unit,
    val onDeleteSnapshot: (SnapshotMeta) -> Unit,
    val onConfirmMismatchImport: () -> Unit,
    val onDismissMismatchImport: () -> Unit,
    val onDismissBackupMessage: () -> Unit,
    val onCheckForUpdates: () -> Unit = {},
    val onOpenUpdate: () -> Unit = {},
    val onRestoreSharedGame: (Long) -> Unit = {},
    val onManualSharedGameInputChanged: (String) -> Unit = {},
    val onImportManualSharedGame: () -> Unit = {},
    val onCheckHltbDataset: () -> Unit = {},
    val onRequestContributionExport: () -> Unit = {},
    val onDismissContributionDisclosure: () -> Unit = {},
    val onConfirmContributionDisclosure: () -> Unit = {},
    val onVerifyCloudPresence: (String, String) -> Unit = { _, _ -> },
    val onReadCloudPresence: () -> Unit = {},
    val onRemoveCloudPresence: () -> Unit = {},
    val onRefileCloudPresence: () -> Unit = {},
    val onReverseCloudPresenceRefiling: () -> Unit = {},
)

/** The stateless half: renders [state] and raises [actions]. */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEditCredentials: () -> Unit,
    onOpenDiagnostics: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenHiddenGames: () -> Unit = {},
    actions: SettingsActions,
) {
    if (state.loading) {
        SettingsLoadingContent()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AccountSyncSettingsContent(
            state = state,
            actions = actions,
            onEditCredentials = onEditCredentials,
            onOpenSetup = onOpenSetup,
            onOpenUpdate = onOpenUpdate,
        )
        GameplaySettingsContent(
            state = state,
            actions = actions,
            onOpenHiddenGames = onOpenHiddenGames,
        )
        DataPrivacySettingsContent(state = state, actions = actions)
        AdvancedSettingsContent(
            state = state,
            actions = actions,
            onOpenDiagnostics = onOpenDiagnostics,
        )
    }
}

@Composable
internal fun AccountSyncSettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onEditCredentials: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_section_account))
    SteamAccountCard(
        configured = state.configured,
        steamId = state.steamId,
        apiKeyMasked = state.apiKeyMasked,
        onEdit = onEditCredentials,
    )
    SectionHeader(stringResource(R.string.settings_section_setup))
    RunSetupCard(configured = state.configured, onOpenSetup = onOpenSetup)
    SectionHeader(stringResource(R.string.settings_section_sync))
    SyncCard(
        lastSyncAt = state.lastSyncAt,
        syncing = state.isSyncing,
        reconciling = state.isReconciling,
        genreStatus = state.genreEnrichmentStatus,
        onSyncNow = actions.onSyncNow,
        onReconcileNow = actions.onReconcileNow,
    )
    if (!BuildConfig.DEBUG) {
        SectionHeader(stringResource(R.string.settings_section_updates))
        UpdateCard(
            state = state.appUpdateState,
            checking = state.updateCheckInProgress,
            message = state.updateCheckMessage,
            onCheck = actions.onCheckForUpdates,
            onOpenUpdate = onOpenUpdate,
        )
    }
}

@Composable
internal fun GameplaySettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenHiddenGames: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_section_live_monitor))
    LiveMonitorCard(
        enabled = state.liveMonitorEnabled,
        configured = state.configured,
        onEnabledChanged = actions.onLiveMonitorEnabledChanged,
    )
    SectionHeader(stringResource(R.string.settings_section_family_sharing))
    ManualSharedGameCard(state, actions)
    if (state.removedSharedGames.isNotEmpty()) {
        SectionHeader(stringResource(R.string.settings_section_removed_shared_games))
        RemovedSharedGamesCard(
            removed = state.removedSharedGames,
            onRestore = actions.onRestoreSharedGame,
        )
    }
    SectionHeader(stringResource(R.string.settings_section_daily_quest))
    DailyQuestCard(state = state, actions = actions)
    SectionHeader(stringResource(R.string.settings_section_hidden_games))
    HiddenGamesCard(
        hiddenCount = state.hiddenGameCount,
        nonGameCandidateCount = state.nonGameCandidateCount,
        onOpen = onOpenHiddenGames,
    )
}

@Composable
internal fun DataPrivacySettingsContent(state: SettingsUiState, actions: SettingsActions) {
    SectionHeader(stringResource(R.string.settings_section_cloud_presence))
    CloudPresenceCard(state = state, actions = actions)
    SectionHeader(stringResource(R.string.settings_section_completion_times))
    CompletionTimesCard(
        gatheredAt = state.hltbDatasetGatheredAt,
        coveredGameCount = state.hltbDatasetCoveredGameCount,
        checking = state.hltbDatasetCheckInProgress,
        checkMessage = state.hltbDatasetCheckMessage,
        contributionBusy = state.hltbContributionBusy,
        contributionMessage = state.hltbContributionMessage,
        onCheck = actions.onCheckHltbDataset,
        onRequestContributionExport = actions.onRequestContributionExport,
    )
    SectionHeader(stringResource(R.string.settings_section_offline_assets))
    OfflineSteamAssetsCard(
        state = state,
        onStart = actions.onDownloadSteamAssets,
        onCancel = actions.onCancelSteamAssetDownload,
    )
    SectionHeader(stringResource(R.string.settings_section_data))
    HistoryImportCard(
        imported = state.historyImported,
        importing = state.isImportingHistory,
        onImport = actions.onImportHistory,
        onReset = actions.onResetHistoryImport,
    )
    SectionHeader(stringResource(R.string.settings_section_data_backup))
    DataBackupCard(state = state, actions = actions)
}

@Composable
internal fun AdvancedSettingsContent(
    state: SettingsUiState,
    actions: SettingsActions,
    onOpenDiagnostics: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_section_diagnostics))
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenDiagnostics() }) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_diagnostics_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_diagnostics_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    SectionHeader(stringResource(R.string.settings_section_advanced))
    AdvancedCard(state = state, actions = actions)
    RuleSaveBar(state = state, actions = actions)
}

@Composable
internal fun SettingsDetailScreen(
    group: SettingsGroup,
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit = {},
    onEditCredentials: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenSetup: () -> Unit = {},
    onOpenUpdate: () -> Unit = {},
    onOpenHiddenGames: () -> Unit = {},
) {
    if (state.loading) {
        SettingsLoadingContent()
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.testTag("settings-back"),
            ) {
                Icon(
                    imageVector = TablerIcons.ArrowLeft,
                    contentDescription = stringResource(com.example.backlogium.R.string.settings_back),
                )
            }
            Text(
                stringResource(group.titleRes),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag("settings-detail-title"),
            )
        }
        when (group) {
            SettingsGroup.ACCOUNT_SYNC -> AccountSyncSettingsContent(
                state,
                actions,
                onEditCredentials,
                onOpenSetup,
                onOpenUpdate,
            )
            SettingsGroup.GAMEPLAY -> GameplaySettingsContent(state, actions, onOpenHiddenGames)
            SettingsGroup.DATA_PRIVACY -> DataPrivacySettingsContent(state, actions)
            SettingsGroup.ADVANCED -> AdvancedSettingsContent(state, actions, onOpenDiagnostics)
        }
    }
}

@Composable
private fun SettingsLoadingContent() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        repeat(4) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_summary_loading), style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun SettingsDialogs(state: SettingsUiState, actions: SettingsActions) {
    state.confirmation?.let { confirmation ->
        RuleChangeDialog(
            confirmation = confirmation,
            onConfirm = actions.onConfirmSave,
            onDismiss = actions.onDismissConfirmation,
        )
    }
    if (state.mismatchImportPending) {
        MismatchImportDialog(
            currentSteamId = state.steamId,
            backupSteamId = state.mismatchImportSteamId,
            onConfirm = actions.onConfirmMismatchImport,
            onDismiss = actions.onDismissMismatchImport,
        )
    }
    state.backupMessage?.let { message ->
        AlertDialog(
            onDismissRequest = actions.onDismissBackupMessage,
            title = { Text(stringResource(R.string.settings_backup_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = actions.onDismissBackupMessage) {
                    Text(stringResource(R.string.settings_ok))
                }
            },
        )
    }
    if (state.hltbContributionDisclosurePending) {
        AlertDialog(
            onDismissRequest = actions.onDismissContributionDisclosure,
            title = { Text(stringResource(R.string.settings_contribution_disclosure_title)) },
            text = { Text(stringResource(R.string.settings_contribution_disclosure)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmContributionDisclosure) {
                    Text(stringResource(R.string.settings_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissContributionDisclosure) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * The way back into first-run setup. Skipping setup during onboarding is a legitimate choice, and
 * making it unrecoverable except by clearing credentials would turn a reasonable "not now" into a
 * trap. Present whether or not setup has ever run; the checklist itself shows each stage's last
 * outcome and explains the missing-credentials case rather than starting stages that cannot succeed.
 */
@Composable
private fun RunSetupCard(configured: Boolean, onOpenSetup: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenSetup() }) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_run_setup_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (configured) {
                    stringResource(R.string.settings_run_setup_configured)
                } else {
                    stringResource(R.string.settings_run_setup_unconfigured)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The way back to anything hidden (add-hidden-games). Present whether or not anything is hidden:
 * the section is where a player looks to find out, and a hide with no route back would be a trap.
 * The count lives here; the section itself owns the list and every mutation.
 */
@Composable
private fun HiddenGamesCard(
    hiddenCount: Int,
    nonGameCandidateCount: Int,
    onOpen: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_section_hidden_games), style = MaterialTheme.typography.titleMedium)
            Text(
                text = when {
                    hiddenCount > 0 -> pluralStringResource(R.plurals.settings_hidden_count, hiddenCount, hiddenCount)
                    nonGameCandidateCount > 0 -> pluralStringResource(
                        R.plurals.settings_hidden_candidates,
                        nonGameCandidateCount,
                        nonGameCandidateCount,
                    )
                    else -> stringResource(R.string.settings_hidden_none)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                Text(stringResource(R.string.settings_steam_account_title), style = MaterialTheme.typography.titleMedium)
                if (configured) {
                    Text(
                        text = stringResource(R.string.settings_steam_id, steamId),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_api_key, apiKeyMasked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_steam_not_connected),
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
                    Text(stringResource(R.string.settings_edit))
                }
            } else {
                Button(onClick = onEdit) { Text(stringResource(R.string.settings_connect)) }
            }
        }
    }
}

/**
 * Last successful sync plus the manual triggers. "Sync now" disables while a sync is already in
 * flight; "Full achievement refresh" disables while a reconciliation pass — forced or deferred —
 * is already enqueued or running, so a second tap can't cancel and restart an in-progress one.
 */
@Composable
private fun SyncCard(
    lastSyncAt: Long,
    syncing: Boolean,
    reconciling: Boolean,
    genreStatus: GenreEnrichmentStatus,
    onSyncNow: () -> Unit,
    onReconcileNow: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (syncing) {
                        stringResource(R.string.settings_syncing)
                    } else {
                        stringResource(R.string.settings_last_sync, UiFormat.dateTime(lastSyncAt))
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(genreStatusLabelRes(genreStatus)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Button(onClick = onSyncNow, enabled = !syncing) {
                    Text(
                        stringResource(
                            if (syncing) R.string.settings_sync_in_progress else R.string.settings_sync_now,
                        ),
                    )
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onReconcileNow, enabled = !reconciling) {
                    Text(
                        stringResource(
                            if (reconciling) R.string.settings_refreshing else R.string.settings_full_achievement_refresh,
                        ),
                    )
                }
            }
        }
    }
}

@StringRes
private fun genreStatusLabelRes(status: GenreEnrichmentStatus): Int = when (status) {
    GenreEnrichmentStatus.IDLE -> R.string.settings_genres_idle
    GenreEnrichmentStatus.QUEUED -> R.string.settings_genres_queued
    GenreEnrichmentStatus.RUNNING -> R.string.settings_genres_fetching
    GenreEnrichmentStatus.RETRYING -> R.string.settings_genres_retrying
}

internal const val CLOUD_PRESENCE_DISCLOSURE =
    "Compare a bounded cloud presence window with this phone's local ledger. " +
        "Cloud presence adds a record of when play happened, even while Backlogium is closed. " +
        "Recovered shared-game sessions update observed time and progress without retroactive celebrations. " +
        "Backlogium functions fully without it. " +
        "It never imports Steam lifetime playtime or invents unobserved time."

internal const val CLOUD_PRESENCE_REFILING_DISCLOSURE =
    "Dates, quests, and streaks may change. Experience, levels, and total playtime will not."

@Composable
private fun CloudPresenceCard(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    var endpoint by remember(state.cloudEndpoint) { mutableStateOf(state.cloudEndpoint) }
    var token by remember { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_cloud_reader_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.settings_cloud_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.cloudEndpoint.isNotBlank()) {
                Text(
                    stringResource(R.string.settings_cloud_connected, state.cloudEndpoint),
                    style = MaterialTheme.typography.bodySmall,
                )
                val healthText = when {
                    state.cloudHealthy == true ->
                        state.cloudLastSuccessAt?.let {
                            stringResource(R.string.settings_cloud_healthy_with_last_read, UiFormat.dateTime(it))
                        } ?: stringResource(R.string.settings_cloud_healthy)
                    state.cloudHealthy == false ->
                        stringResource(
                            R.string.settings_cloud_last_read_failed,
                            stringResource(cloudFailureLabelRes(state.cloudLastFailure)),
                        ) + state.cloudLastSuccessAt?.let {
                            stringResource(R.string.settings_cloud_last_success, UiFormat.dateTime(it))
                        }.orEmpty()
                    else -> stringResource(R.string.settings_cloud_configured_no_read)
                }
                Text(
                    text = healthText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.cloudHealthy == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    stringResource(R.string.settings_cloud_credential, state.cloudTokenMasked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.settings_cloud_refiling_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.cloudPresenceRefilingApplied) {
                    OutlinedButton(
                        onClick = actions.onReverseCloudPresenceRefiling,
                        enabled = !state.cloudBusy && !state.cloudPresenceRefilingBusy,
                    ) {
                        Text(
                            stringResource(
                                if (state.cloudPresenceRefilingBusy) {
                                    R.string.settings_cloud_restoring
                                } else {
                                    R.string.settings_cloud_undo_refiling
                                },
                            ),
                        )
                    }
                } else {
                    Button(
                        onClick = actions.onRefileCloudPresence,
                        enabled = !state.cloudBusy && !state.cloudPresenceRefilingBusy,
                    ) {
                        Text(
                            stringResource(
                                if (state.cloudPresenceRefilingBusy) {
                                    R.string.settings_cloud_refiling
                                } else {
                                    R.string.settings_cloud_refile_play
                                },
                            ),
                        )
                    }
                }
                state.cloudPresenceRefilingMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text(stringResource(R.string.settings_cloud_reader_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = {
                    Text(
                        stringResource(
                            if (state.cloudEndpoint.isBlank()) {
                                R.string.settings_cloud_reader_credential
                            } else {
                                R.string.settings_cloud_replace_credential
                            },
                        ),
                    )
                },
                placeholder = {
                    if (state.cloudEndpoint.isNotBlank()) Text(state.cloudTokenMasked)
                },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        actions.onVerifyCloudPresence(endpoint, token)
                        token = ""
                    },
                    enabled = !state.cloudBusy && endpoint.isNotBlank() && token.isNotBlank(),
                ) {
                    Text(
                        stringResource(
                            if (state.cloudEndpoint.isBlank()) {
                                R.string.settings_cloud_verify_save
                            } else {
                                R.string.settings_cloud_verify_replacement
                            },
                        ),
                    )
                }
                if (state.cloudEndpoint.isNotBlank()) {
                    OutlinedButton(
                        onClick = actions.onReadCloudPresence,
                        enabled = !state.cloudBusy,
                    ) {
                        Text(
                            stringResource(
                                if (state.cloudBusy) R.string.settings_cloud_reading
                                else R.string.settings_cloud_read_now,
                            ),
                        )
                    }
                }
            }
            if (state.cloudEndpoint.isNotBlank()) {
                TextButton(
                    onClick = actions.onRemoveCloudPresence,
                    enabled = !state.cloudBusy,
                ) {
                    Text(stringResource(R.string.settings_cloud_remove_reader))
                }
            }
            state.cloudMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (
                        state.cloudHealthy == false ||
                        message.contains("rejected", ignoreCase = true) ||
                        message.contains("match", ignoreCase = true)
                    ) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@StringRes
private fun cloudFailureLabelRes(failure: CloudReadFailure?): Int = when (failure) {
    CloudReadFailure.UNREACHABLE -> R.string.settings_cloud_failure_unreachable
    CloudReadFailure.REJECTED_CREDENTIAL -> R.string.settings_cloud_failure_rejected
    CloudReadFailure.ACCOUNT_MISMATCH -> R.string.settings_cloud_failure_mismatch
    CloudReadFailure.UNUSABLE_RESPONSE -> R.string.settings_cloud_failure_unusable
    null -> R.string.settings_cloud_failure_unknown
}
@Composable
private fun UpdateCard(
    state: AppUpdateState,
    checking: Boolean,
    message: String?,
    onCheck: () -> Unit,
    onOpenUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("updates_section"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.settings_update_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = state.lastCheckAtMillis?.let {
                    stringResource(R.string.settings_update_last_checked, UiFormat.dateTime(it))
                } ?: stringResource(R.string.settings_update_never_checked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.available != null) {
                Text(
                    text = stringResource(R.string.settings_update_available, state.available.versionName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = onOpenUpdate) {
                    Text(stringResource(R.string.settings_update_review))
                }
            } else if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall)
            } else if (state.lastCheckAtMillis != null) {
                Text(stringResource(R.string.settings_update_none), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onCheck, enabled = !checking) {
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (checking) R.string.settings_update_checking else R.string.settings_update_check,
                    ),
                )
            }
        }
    }
}

/**
 * The state of the applied shared HowLongToBeat dataset and the two operations it offers: an
 * explicit check for a newer one, and producing a contribution file. Deliberately offers no
 * control that looks up HowLongToBeat across the library — that sweep no longer exists.
 */
@Composable
private fun CompletionTimesCard(
    gatheredAt: Long?,
    coveredGameCount: Int,
    checking: Boolean,
    checkMessage: String?,
    contributionBusy: Boolean,
    contributionMessage: String?,
    onCheck: () -> Unit,
    onRequestContributionExport: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("completion_times_section"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (gatheredAt != null) {
                Text(
                    text = stringResource(
                        R.string.settings_completion_gathered,
                        UiFormat.dateTime(gatheredAt),
                        coveredGameCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_completion_none),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            checkMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onCheck, enabled = !checking) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (checking) R.string.settings_completion_checking
                            else R.string.settings_completion_check,
                        ),
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_completion_contribute),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            contributionMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = onRequestContributionExport, enabled = !contributionBusy) {
                Text(
                    stringResource(
                        if (contributionBusy) R.string.settings_completion_preparing
                        else R.string.settings_completion_export,
                    ),
                )
            }
        }
    }
}

/** Explicitly armed background presence polling, separate from the periodic full Steam sync. */
@Composable
private fun LiveMonitorCard(
    enabled: Boolean,
    configured: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_monitor_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (configured) {
                        stringResource(R.string.settings_monitor_configured)
                    } else {
                        stringResource(R.string.settings_monitor_unconfigured)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChanged,
                enabled = configured,
            )
        }
    }
}

/**
 * The family-shared games the player removed, and the way back. Shown only when something has been
 * removed: a standing empty section would explain a feature most players never touch.
 *
 * Restoring recreates the tracked shared-game row immediately, so it is visible in Library and
 * available to add to collections again. Future play observations provide its sessions.
 */
@Composable
private fun RemovedSharedGamesCard(
    removed: List<RemovedSharedGame>,
    onRestore: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_removed_shared_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            removed.forEach { game ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = game.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRestore(game.appId) }) {
                        Text(stringResource(R.string.settings_track_again))
                    }
                }
            }
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
                Text(
                    stringResource(R.string.settings_quest_counts_toward),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuestMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.draft.questMode == mode,
                            onClick = { actions.onQuestModeChanged(mode) },
                            label = { Text(stringResource(questModeLabelRes(mode))) },
                        )
                    }
                }
            }

            RuleTextField(RuleField.STREAK_GRACE_DAYS, state, actions)
            Text(
                text = stringResource(R.string.settings_quest_grace_help),
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
                    .testTag("settings-advanced-toggle")
                    .clickable { actions.onAdvancedExpandedChanged(!state.advancedExpanded) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_advanced_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.settings_advanced_description),
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
                    contentDescription = stringResource(
                        if (state.advancedExpanded) R.string.settings_collapse else R.string.settings_expand,
                    ),
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
        label = { Text(stringResource(field.labelResource())) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let {
            {
                Text(
                    stringResource(
                        field.rejectionResource(),
                        *field.rejectionResourceArgs().toTypedArray(),
                    ),
                )
            }
        },
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
                Text(stringResource(R.string.settings_save_rules))
            }
        }
        TextButton(onClick = actions.onDiscardChanges) {
            Text(stringResource(R.string.settings_discard))
        }
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
        title = { Text(stringResource(R.string.settings_apply_rules_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_apply_rules_message))
                if (confirmation.kind.questRules) {
                    Text(
                        text = stringResource(
                            R.string.settings_current_streak,
                            confirmation.currentStreakBefore,
                            confirmation.currentStreakAfter,
                        ),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_longest_streak,
                            confirmation.longestStreakBefore,
                            confirmation.longestStreakAfter,
                        ),
                    )
                }
                if (confirmation.kind.advancedRules) {
                    Text(
                        text = stringResource(
                            R.string.settings_total_xp,
                            confirmation.totalXpBefore,
                            confirmation.totalXpAfter,
                        ),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_level,
                            confirmation.levelBefore,
                            confirmation.levelAfter,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

/**
 * The "Data & Backup" section (add-backup-restore): automatic-snapshot configuration, the
 * current snapshot list with per-entry restore, and the manual export/import actions — always
 * enabled regardless of the auto-snapshot toggle.
 */
@Composable
private fun DataBackupCard(state: SettingsUiState, actions: SettingsActions) {
    var deleteTarget by remember { mutableStateOf<SnapshotMeta?>(null) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_backup_automatic_title), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(
                            R.string.settings_backup_schedule,
                            state.snapshotIntervalHours,
                            state.snapshotRetentionCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoSnapshotEnabled,
                    onCheckedChange = actions.onAutoSnapshotEnabledChanged,
                )
            }

            OutlinedTextField(
                value = state.snapshotRetentionCount.toString(),
                onValueChange = { text ->
                    text.trim().toIntOrNull()?.takeIf { it > 0 }
                        ?.let(actions.onSnapshotRetentionCountChanged)
                },
                label = { Text(stringResource(R.string.settings_backup_keep)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.snapshotIntervalHours.toString(),
                onValueChange = { text ->
                    text.trim().toIntOrNull()?.takeIf { it > 0 }
                        ?.let(actions.onSnapshotIntervalHoursChanged)
                },
                label = { Text(stringResource(R.string.settings_backup_interval)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.snapshots.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.settings_backup_snapshots), style = MaterialTheme.typography.bodyMedium)
                state.snapshots.forEach { snapshot ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = UiFormat.dateTime(snapshot.writtenAtMillis),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { actions.onRestoreSnapshot(snapshot) },
                                enabled = !state.backupBusy,
                            ) {
                                Text(stringResource(R.string.settings_restore))
                            }
                            TextButton(
                                onClick = { deleteTarget = snapshot },
                                enabled = !state.backupBusy,
                            ) {
                                Text(stringResource(R.string.settings_delete))
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = actions.onExportBackup,
                    enabled = !state.backupBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = TablerIcons.Upload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_export_backup))
                }
                OutlinedButton(
                    onClick = actions.onImportBackup,
                    enabled = !state.backupBusy,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = TablerIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_import_backup))
                }
            }
            if (state.backupBusy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }

    deleteTarget?.let { snapshot ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.settings_delete_snapshot_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_delete_snapshot_message,
                        UiFormat.dateTime(snapshot.writtenAtMillis),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        actions.onDeleteSnapshot(snapshot)
                    },
                ) {
                    Text(stringResource(R.string.settings_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * Cross-account warning shown before an import/restore proceeds when the backup's recorded
 * SteamID64 differs from the signed-in account (warn, don't block — the SteamID is public and
 * carries no credential).
 */
@Composable
private fun MismatchImportDialog(
    currentSteamId: String,
    backupSteamId: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_different_account_title)) },
        text = {
            Text(
                stringResource(
                    R.string.settings_different_account_message,
                    backupSteamId,
                    currentSteamId,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_import_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
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
            Text(stringResource(R.string.settings_history_title), style = MaterialTheme.typography.titleMedium)
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
                        text = stringResource(R.string.settings_history_imported),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Text(
                    text = stringResource(R.string.settings_history_imported_description),
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
                        Text(stringResource(R.string.settings_history_reset))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_history_unimported_description),
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
                        Text(stringResource(R.string.settings_history_import))
                    }
                }
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.settings_history_import_title)) },
            text = {
                Text(stringResource(R.string.settings_history_import_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onImport()
                    },
                ) {
                    Text(stringResource(R.string.settings_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.settings_history_reset_title)) },
            text = {
                Text(stringResource(R.string.settings_history_reset_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onReset()
                    },
                ) {
                    Text(stringResource(R.string.settings_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

/**
 * User-facing names for the quest scope. `GOAL_ONLY` keeps its engine name while its label follows
 * the Library's "Focus" wording — this chip is the one place where that section's name has a
 * functional consequence, so leaving it as "Goal games only" would leave the relabel half-done.
 */
@StringRes
private fun questModeLabelRes(mode: QuestMode): Int = when (mode) {
    QuestMode.ANY -> R.string.settings_quest_any_game
    QuestMode.GOAL_ONLY -> R.string.settings_quest_focus_only
}
