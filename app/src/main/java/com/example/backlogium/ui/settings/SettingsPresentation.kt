package com.example.backlogium.ui.settings

import androidx.annotation.StringRes
import com.example.backlogium.R
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.updates.AppUpdateState
import com.example.backlogium.work.SteamAssetDownloadStatus

/** Stable routes owned by the nested Settings graph. */
object SettingsRoutes {
    const val GRAPH = "settings_graph"
    const val OVERVIEW = "settings"
    const val ACCOUNT_SYNC = "settings/account-sync"
    const val GAMEPLAY = "settings/gameplay"
    const val DATA_PRIVACY = "settings/data-privacy"
    const val ADVANCED = "settings/advanced"

    val detailRoutes: Set<String> = setOf(ACCOUNT_SYNC, GAMEPLAY, DATA_PRIVACY, ADVANCED)
}

/** The nested graph's toolbar and system back target; top-level destinations are outside it. */
fun settingsBackStackTarget(route: String): String? = when {
    route == SettingsRoutes.OVERVIEW -> SettingsRoutes.OVERVIEW
    route in SettingsRoutes.detailRoutes -> SettingsRoutes.OVERVIEW
    else -> null
}

enum class SettingsGroup(
    @StringRes val titleRes: Int,
    val route: String,
) {
    ACCOUNT_SYNC(R.string.settings_group_account_sync_title, SettingsRoutes.ACCOUNT_SYNC),
    GAMEPLAY(R.string.settings_group_gameplay_title, SettingsRoutes.GAMEPLAY),
    DATA_PRIVACY(R.string.settings_group_data_privacy_title, SettingsRoutes.DATA_PRIVACY),
    ADVANCED(R.string.settings_group_advanced_title, SettingsRoutes.ADVANCED),
}

/** Every former first-level card is recorded once so extraction cannot silently lose a control. */
enum class SettingsSection {
    ACCOUNT,
    SETUP,
    SYNC,
    CLOUD_PRESENCE,
    COMPLETION_TIMES,
    OFFLINE_STEAM_ASSETS,
    UPDATES,
    LIVE_MONITOR,
    FAMILY_SHARING,
    REMOVED_SHARED_GAMES,
    DAILY_QUEST,
    HIDDEN_GAMES,
    HISTORY,
    DATA_BACKUP,
    DIAGNOSTICS,
    ADVANCED_RULES,
}

enum class SettingsOperation {
    EDIT_CREDENTIALS,
    OPEN_SETUP,
    SYNC_NOW,
    RECONCILE_NOW,
    CHECK_FOR_UPDATES,
    REVIEW_UPDATE,
    VERIFY_CLOUD_READER,
    READ_CLOUD_PRESENCE,
    REMOVE_CLOUD_READER,
    REFILE_CLOUD_PRESENCE,
    REVERSE_CLOUD_REFILING,
    CHECK_COMPLETION_DATASET,
    EXPORT_COMPLETION_CONTRIBUTION,
    DOWNLOAD_STEAM_ASSETS,
    CANCEL_STEAM_ASSET_DOWNLOAD,
    TOGGLE_LIVE_MONITOR,
    IMPORT_SHARED_GAME,
    RESTORE_SHARED_GAME,
    OPEN_HIDDEN_GAMES,
    CHANGE_QUEST_RULES,
    IMPORT_STEAM_HISTORY,
    RESET_STEAM_HISTORY,
    TOGGLE_AUTO_SNAPSHOTS,
    EDIT_SNAPSHOT_SETTINGS,
    EXPORT_BACKUP,
    IMPORT_BACKUP,
    RESTORE_SNAPSHOT,
    DELETE_SNAPSHOT,
    OPEN_DIAGNOSTICS,
    EDIT_ADVANCED_RULES,
    SAVE_RULES,
    DISCARD_RULES,
}

enum class SettingsDialog {
    ASSET_DOWNLOAD_MODE,
    CONTRIBUTION_DISCLOSURE,
    HISTORY_IMPORT,
    HISTORY_RESET,
    BACKUP_MESSAGE,
    BACKUP_ACCOUNT_MISMATCH,
    SNAPSHOT_DELETE,
    RULE_CHANGE_CONFIRMATION,
}

enum class SettingsBusyState {
    SYNC,
    RECONCILIATION,
    UPDATE_CHECK,
    CLOUD_READ,
    CLOUD_REFILING,
    COMPLETION_DATASET,
    COMPLETION_CONTRIBUTION,
    STEAM_ASSET_DOWNLOAD,
    MANUAL_SHARED_GAME,
    HISTORY_IMPORT,
    BACKUP,
    RULE_PREVIEW,
}

data class SettingsInventoryEntry(
    val section: SettingsSection,
    val group: SettingsGroup,
    val operations: Set<SettingsOperation>,
    val dialogs: Set<SettingsDialog> = emptySet(),
    val busyStates: Set<SettingsBusyState> = emptySet(),
    val testTags: Set<String> = emptySet(),
)

val SETTINGS_INVENTORY: List<SettingsInventoryEntry> = listOf(
    SettingsInventoryEntry(
        SettingsSection.ACCOUNT,
        SettingsGroup.ACCOUNT_SYNC,
        setOf(SettingsOperation.EDIT_CREDENTIALS),
    ),
    SettingsInventoryEntry(
        SettingsSection.SETUP,
        SettingsGroup.ACCOUNT_SYNC,
        setOf(SettingsOperation.OPEN_SETUP),
    ),
    SettingsInventoryEntry(
        SettingsSection.SYNC,
        SettingsGroup.ACCOUNT_SYNC,
        setOf(SettingsOperation.SYNC_NOW, SettingsOperation.RECONCILE_NOW),
        busyStates = setOf(SettingsBusyState.SYNC, SettingsBusyState.RECONCILIATION),
    ),
    SettingsInventoryEntry(
        SettingsSection.CLOUD_PRESENCE,
        SettingsGroup.DATA_PRIVACY,
        setOf(
            SettingsOperation.VERIFY_CLOUD_READER,
            SettingsOperation.READ_CLOUD_PRESENCE,
            SettingsOperation.REMOVE_CLOUD_READER,
            SettingsOperation.REFILE_CLOUD_PRESENCE,
            SettingsOperation.REVERSE_CLOUD_REFILING,
        ),
        busyStates = setOf(SettingsBusyState.CLOUD_READ, SettingsBusyState.CLOUD_REFILING),
    ),
    SettingsInventoryEntry(
        SettingsSection.COMPLETION_TIMES,
        SettingsGroup.DATA_PRIVACY,
        setOf(SettingsOperation.CHECK_COMPLETION_DATASET, SettingsOperation.EXPORT_COMPLETION_CONTRIBUTION),
        dialogs = setOf(SettingsDialog.CONTRIBUTION_DISCLOSURE),
        busyStates = setOf(SettingsBusyState.COMPLETION_DATASET, SettingsBusyState.COMPLETION_CONTRIBUTION),
        testTags = setOf("completion_times_section"),
    ),
    SettingsInventoryEntry(
        SettingsSection.OFFLINE_STEAM_ASSETS,
        SettingsGroup.DATA_PRIVACY,
        setOf(SettingsOperation.DOWNLOAD_STEAM_ASSETS, SettingsOperation.CANCEL_STEAM_ASSET_DOWNLOAD),
        dialogs = setOf(SettingsDialog.ASSET_DOWNLOAD_MODE),
        busyStates = setOf(SettingsBusyState.STEAM_ASSET_DOWNLOAD),
        testTags = setOf("offline-steam-assets"),
    ),
    SettingsInventoryEntry(
        SettingsSection.UPDATES,
        SettingsGroup.ACCOUNT_SYNC,
        setOf(SettingsOperation.CHECK_FOR_UPDATES, SettingsOperation.REVIEW_UPDATE),
        busyStates = setOf(SettingsBusyState.UPDATE_CHECK),
        testTags = setOf("updates_section"),
    ),
    SettingsInventoryEntry(
        SettingsSection.LIVE_MONITOR,
        SettingsGroup.GAMEPLAY,
        setOf(SettingsOperation.TOGGLE_LIVE_MONITOR),
    ),
    SettingsInventoryEntry(
        SettingsSection.FAMILY_SHARING,
        SettingsGroup.GAMEPLAY,
        setOf(SettingsOperation.IMPORT_SHARED_GAME),
        busyStates = setOf(SettingsBusyState.MANUAL_SHARED_GAME),
        testTags = setOf(
            "settings-manual-shared-game-input",
            "settings-manual-shared-game-import",
            "settings-manual-shared-game-feedback",
        ),
    ),
    SettingsInventoryEntry(
        SettingsSection.REMOVED_SHARED_GAMES,
        SettingsGroup.GAMEPLAY,
        setOf(SettingsOperation.RESTORE_SHARED_GAME),
    ),
    SettingsInventoryEntry(
        SettingsSection.DAILY_QUEST,
        SettingsGroup.GAMEPLAY,
        setOf(SettingsOperation.CHANGE_QUEST_RULES),
    ),
    SettingsInventoryEntry(
        SettingsSection.HIDDEN_GAMES,
        SettingsGroup.GAMEPLAY,
        setOf(SettingsOperation.OPEN_HIDDEN_GAMES),
    ),
    SettingsInventoryEntry(
        SettingsSection.HISTORY,
        SettingsGroup.DATA_PRIVACY,
        setOf(SettingsOperation.IMPORT_STEAM_HISTORY, SettingsOperation.RESET_STEAM_HISTORY),
        dialogs = setOf(SettingsDialog.HISTORY_IMPORT, SettingsDialog.HISTORY_RESET),
        busyStates = setOf(SettingsBusyState.HISTORY_IMPORT),
    ),
    SettingsInventoryEntry(
        SettingsSection.DATA_BACKUP,
        SettingsGroup.DATA_PRIVACY,
        setOf(
            SettingsOperation.TOGGLE_AUTO_SNAPSHOTS,
            SettingsOperation.EDIT_SNAPSHOT_SETTINGS,
            SettingsOperation.EXPORT_BACKUP,
            SettingsOperation.IMPORT_BACKUP,
            SettingsOperation.RESTORE_SNAPSHOT,
            SettingsOperation.DELETE_SNAPSHOT,
        ),
        dialogs = setOf(
            SettingsDialog.BACKUP_MESSAGE,
            SettingsDialog.BACKUP_ACCOUNT_MISMATCH,
            SettingsDialog.SNAPSHOT_DELETE,
        ),
        busyStates = setOf(SettingsBusyState.BACKUP),
    ),
    SettingsInventoryEntry(
        SettingsSection.DIAGNOSTICS,
        SettingsGroup.ADVANCED,
        setOf(SettingsOperation.OPEN_DIAGNOSTICS),
    ),
    SettingsInventoryEntry(
        SettingsSection.ADVANCED_RULES,
        SettingsGroup.ADVANCED,
        setOf(SettingsOperation.EDIT_ADVANCED_RULES, SettingsOperation.SAVE_RULES, SettingsOperation.DISCARD_RULES),
        dialogs = setOf(SettingsDialog.RULE_CHANGE_CONFIRMATION),
        busyStates = setOf(SettingsBusyState.RULE_PREVIEW),
        testTags = setOf("settings-advanced-toggle"),
    ),
)

fun settingsInventoryIssues(): List<String> = buildList {
    val sections = SETTINGS_INVENTORY.map { it.section }
    if (sections.size != SettingsSection.entries.size) add("section count does not cover every Settings section")
    if (sections.toSet().size != sections.size) add("a Settings section is assigned more than once")
    if (sections.toSet() != SettingsSection.entries.toSet()) add("a Settings section is unmapped")

    val operations = SETTINGS_INVENTORY.flatMap { it.operations }
    if (operations.toSet().size != operations.size) add("a Settings operation is assigned more than once")
    if (operations.toSet() != SettingsOperation.entries.toSet()) add("a Settings operation is unmapped")

    val dialogs = SETTINGS_INVENTORY.flatMap { it.dialogs }
    if (dialogs.toSet().size != dialogs.size) add("a Settings dialog is assigned more than once")
    if (dialogs.toSet() != SettingsDialog.entries.toSet()) add("a Settings dialog is unmapped")

    val busyStates = SETTINGS_INVENTORY.flatMap { it.busyStates }
    if (busyStates.toSet().size != busyStates.size) add("a Settings busy state is assigned more than once")
    if (busyStates.toSet() != SettingsBusyState.entries.toSet()) add("a Settings busy state is unmapped")
}

@StringRes
private fun SettingsGroup.summaryTitleRes(): Int = titleRes

data class SettingsSummaryText(
    val resId: Int,
    val args: List<Any> = emptyList(),
    val quantity: Int? = null,
)

enum class SettingsAttention {
    BLOCKING,
    IN_PROGRESS,
    RECOMMENDED,
    HEALTHY,
}

data class SettingsGroupSummary(
    val group: SettingsGroup,
    val title: SettingsSummaryText,
    val status: SettingsSummaryText,
    val nextAction: SettingsSummaryText,
    val attention: SettingsAttention,
    val destination: String = group.route,
    val loading: Boolean = false,
)

/** Blocking always wins over in-progress, which wins over a recommendation, then quiet health. */
fun settingsAttention(
    blocking: Boolean,
    inProgress: Boolean,
    recommended: Boolean,
): SettingsAttention = when {
    blocking -> SettingsAttention.BLOCKING
    inProgress -> SettingsAttention.IN_PROGRESS
    recommended -> SettingsAttention.RECOMMENDED
    else -> SettingsAttention.HEALTHY
}

fun settingsGroupSummaries(state: SettingsUiState): List<SettingsGroupSummary> {
    if (state.loading) {
        return SettingsGroup.entries.map { group ->
            SettingsGroupSummary(
                group = group,
                title = SettingsSummaryText(group.summaryTitleRes()),
                status = SettingsSummaryText(R.string.settings_summary_loading),
                nextAction = SettingsSummaryText(R.string.settings_summary_loading_action),
                attention = SettingsAttention.IN_PROGRESS,
                loading = true,
            )
        }
    }

    val accountBlocking = !state.configured ||
        state.lastSyncError != null ||
        state.updateCheckSeverity == SettingsResultSeverity.ERROR
    val accountInProgress = state.isSyncing || state.isReconciling || state.updateCheckInProgress
    val accountRecommended = state.appUpdateState.available != null
    val accountAttention = settingsAttention(accountBlocking, accountInProgress, accountRecommended)

    val gameplayBlocking = state.manualSharedGameFeedback?.tone == ManualImportFeedbackTone.ERROR
    val gameplayInProgress = state.manualSharedGameBusy
    val gameplayRecommended = state.nonGameCandidateCount > 0
    val gameplayAttention = settingsAttention(gameplayBlocking, gameplayInProgress, gameplayRecommended)

    val dataBlocking = state.hasDataAttention()
    val dataInProgress = state.cloudBusy ||
        state.cloudPresenceRefilingBusy ||
        state.backupBusy ||
        state.isImportingHistory ||
        state.hltbDatasetCheckInProgress ||
        state.hltbContributionBusy ||
        state.steamAssetStatus in setOf(
            SteamAssetDownloadStatus.QUEUED,
            SteamAssetDownloadStatus.PREPARING,
            SteamAssetDownloadStatus.RUNNING,
        )
    val dataRecommended = state.cloudEndpoint.isBlank() && state.cloudHealthy == null
    val dataAttention = settingsAttention(dataBlocking, dataInProgress, dataRecommended)

    val advancedInProgress = state.previewing
    val advancedRecommended = state.dirty || state.confirmation != null
    val advancedAttention = settingsAttention(state.hasInvalidField, advancedInProgress, advancedRecommended)

    return listOf(
        SettingsGroupSummary(
            group = SettingsGroup.ACCOUNT_SYNC,
            title = SettingsSummaryText(R.string.settings_group_account_sync_title),
            status = when (accountAttention) {
                SettingsAttention.BLOCKING if !state.configured ->
                    SettingsSummaryText(R.string.settings_summary_account_connect)
                SettingsAttention.BLOCKING if state.updateCheckSeverity == SettingsResultSeverity.ERROR &&
                    state.lastSyncError == null ->
                    SettingsSummaryText(R.string.settings_summary_account_update_check_failed)
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_summary_account_sync_failed)
                SettingsAttention.IN_PROGRESS -> SettingsSummaryText(R.string.settings_summary_account_syncing)
                SettingsAttention.RECOMMENDED -> SettingsSummaryText(R.string.settings_summary_account_update_ready)
                SettingsAttention.HEALTHY -> SettingsSummaryText(R.string.settings_summary_account_ready)
            },
            nextAction = when (accountAttention) {
                SettingsAttention.BLOCKING if !state.configured ->
                    SettingsSummaryText(R.string.settings_action_connect_account)
                SettingsAttention.BLOCKING if state.updateCheckSeverity == SettingsResultSeverity.ERROR &&
                    state.lastSyncError == null ->
                    SettingsSummaryText(R.string.settings_action_retry_update_check)
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_action_retry_sync)
                SettingsAttention.IN_PROGRESS -> SettingsSummaryText(R.string.settings_action_view_progress)
                SettingsAttention.RECOMMENDED -> SettingsSummaryText(R.string.settings_action_review_update)
                SettingsAttention.HEALTHY -> SettingsSummaryText(R.string.settings_action_manage_account)
            },
            attention = accountAttention,
        ),
        SettingsGroupSummary(
            group = SettingsGroup.GAMEPLAY,
            title = SettingsSummaryText(R.string.settings_group_gameplay_title),
            status = when (gameplayAttention) {
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_summary_gameplay_attention)
                SettingsAttention.IN_PROGRESS -> SettingsSummaryText(R.string.settings_summary_gameplay_checking)
                SettingsAttention.RECOMMENDED -> SettingsSummaryText(
                    R.plurals.settings_summary_gameplay_candidates,
                    listOf(state.nonGameCandidateCount),
                    quantity = state.nonGameCandidateCount,
                )
                else -> SettingsSummaryText(
                    if (state.liveMonitorEnabled) {
                        R.string.settings_summary_gameplay_monitoring
                    } else {
                        R.string.settings_summary_gameplay_ready
                    },
                )
            },
            nextAction = SettingsSummaryText(R.string.settings_action_manage_gameplay),
            attention = gameplayAttention,
        ),
        SettingsGroupSummary(
            group = SettingsGroup.DATA_PRIVACY,
            title = SettingsSummaryText(R.string.settings_group_data_privacy_title),
            status = when (dataAttention) {
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_summary_data_attention)
                SettingsAttention.IN_PROGRESS -> SettingsSummaryText(R.string.settings_summary_data_working)
                SettingsAttention.RECOMMENDED -> SettingsSummaryText(R.string.settings_summary_data_optional)
                SettingsAttention.HEALTHY -> SettingsSummaryText(R.string.settings_summary_data_healthy)
            },
            nextAction = when (dataAttention) {
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_action_review_data)
                SettingsAttention.IN_PROGRESS -> SettingsSummaryText(R.string.settings_action_view_progress)
                else -> SettingsSummaryText(R.string.settings_action_manage_data)
            },
            attention = dataAttention,
        ),
        SettingsGroupSummary(
            group = SettingsGroup.ADVANCED,
            title = SettingsSummaryText(R.string.settings_group_advanced_title),
            status = when (advancedAttention) {
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_summary_advanced_invalid)
                SettingsAttention.IN_PROGRESS -> SettingsSummaryText(R.string.settings_summary_advanced_working)
                SettingsAttention.RECOMMENDED -> SettingsSummaryText(R.string.settings_summary_advanced_review)
                SettingsAttention.HEALTHY -> SettingsSummaryText(R.string.settings_summary_advanced_quiet)
            },
            nextAction = when (advancedAttention) {
                SettingsAttention.BLOCKING -> SettingsSummaryText(R.string.settings_action_fix_invalid_rules)
                SettingsAttention.RECOMMENDED -> SettingsSummaryText(R.string.settings_action_review_rules)
                else -> SettingsSummaryText(R.string.settings_action_manage_advanced)
            },
            attention = advancedAttention,
        ),
    )
}

/** Useful to callers that need to reason about a failure without exposing its technical detail. */
fun SettingsUiState.hasDataAttention(): Boolean = steamAssetStatus == SteamAssetDownloadStatus.FAILED ||
    cloudHealthy == false ||
    cloudMessageSeverity == SettingsResultSeverity.ERROR ||
    cloudPresenceRefilingMessageSeverity == SettingsResultSeverity.ERROR ||
    hltbDatasetCheckSeverity == SettingsResultSeverity.ERROR ||
    hltbContributionSeverity == SettingsResultSeverity.ERROR

/** Keep the model's privacy contract obvious to tests and future summary additions. */
fun SettingsSummaryText.containsSensitiveSettingsValue(state: SettingsUiState): Boolean =
    args.any { value ->
        value is String && value.isNotBlank() && listOf(
            state.steamId,
            state.apiKeyMasked,
            state.cloudEndpoint,
            state.cloudTokenMasked,
        ).contains(value)
    }

@Suppress("UNUSED_PARAMETER")
private fun AppUpdateState.hasNoSensitiveSummaryValue(): Boolean = true
