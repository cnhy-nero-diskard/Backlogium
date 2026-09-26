package com.example.backlogium.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.R
import com.example.backlogium.data.backup.BackupFile
import com.example.backlogium.data.backup.BackupRepository
import com.example.backlogium.data.backup.BackupValidationProblem
import com.example.backlogium.data.backup.ParsedBackup
import com.example.backlogium.data.backup.SnapshotMeta
import com.example.backlogium.data.credentials.maskApiKey
import com.example.backlogium.data.hltb.HltbContributionExporter
import com.example.backlogium.data.hltb.HltbContributionPreparation
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.CloudConfigurationResult
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.CloudRoutineState
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudPresenceSessionIngestor
import com.example.backlogium.data.repo.CloudReadTrigger
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.repo.CloudReadResult
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.data.steamassets.SteamAssetRepository
import com.example.backlogium.data.steamassets.SteamAssetRunSummary
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.FamilySharedGameRepository
import com.example.backlogium.data.repo.ManualImportUnavailableAt
import com.example.backlogium.data.repo.ManualSharedGameImportResult
import com.example.backlogium.data.repo.PlayerDataProbe
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.RemovedSharedGame
import com.example.backlogium.data.repo.HltbDatasetCheckResult
import com.example.backlogium.data.repo.HltbDatasetProgress
import com.example.backlogium.data.repo.HltbDatasetRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.updates.AppUpdateRepository
import com.example.backlogium.data.updates.AppUpdateState
import com.example.backlogium.data.updates.UpdateCheckResult
import com.example.backlogium.domain.CloudPresencePlaytimeRefilingUseCase
import com.example.backlogium.domain.CloudPresenceRefilingOperation
import com.example.backlogium.domain.CloudPresenceRefilingResult
import com.example.backlogium.domain.UpdateRuleConfigUseCase
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.work.PresenceServiceStarter
import com.example.backlogium.work.GenreEnrichmentStatus
import com.example.backlogium.work.SteamAssetDownloadProgress
import com.example.backlogium.work.SteamAssetDownloadStatus
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * The concrete before/after a rule change will produce, computed by actually running the
 * recompute under the candidate config. Held so the confirmation can name real numbers — a
 * warning that disagrees with what happens is worse than no warning.
 */
data class RuleChangeConfirmation(
    val config: RuleConfig,
    val kind: RuleChangeKind,
    val currentStreakBefore: Int,
    val currentStreakAfter: Int,
    val longestStreakBefore: Int,
    val longestStreakAfter: Int,
    val totalXpBefore: Long,
    val totalXpAfter: Long,
    val levelBefore: Int,
    val levelAfter: Int,
)

enum class SettingsResultSeverity { INFO, SUCCESS, ERROR }

internal data class SettingsActionFeedback(
    val message: SettingsText,
    val severity: SettingsResultSeverity,
) {
    companion object {
        fun success(message: SettingsText) = SettingsActionFeedback(message, SettingsResultSeverity.SUCCESS)
        fun error(message: SettingsText) = SettingsActionFeedback(message, SettingsResultSeverity.ERROR)
    }
}

internal suspend fun settingsCloudActionFeedback(
    failureMessage: SettingsText,
    action: suspend () -> SettingsActionFeedback,
): SettingsActionFeedback = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    SettingsActionFeedback.error(failureMessage)
}

internal fun CloudConfigurationResult.toSettingsActionFeedback(): SettingsActionFeedback = when (this) {
    CloudConfigurationResult.Saved -> SettingsActionFeedback.success(
        SettingsText(R.string.settings_cloud_feedback_connected),
    )
    CloudConfigurationResult.NoSteamAccount ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_verify_account_required))
    CloudConfigurationResult.InvalidEndpoint ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_invalid_endpoint))
    CloudConfigurationResult.RejectedCredential ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_credential_rejected))
    CloudConfigurationResult.Unreachable ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_verify_unreachable))
    CloudConfigurationResult.UnusableResponse ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_unusable_response))
    is CloudConfigurationResult.AccountMismatch -> SettingsActionFeedback.error(
        SettingsText(
            R.string.settings_cloud_feedback_account_mismatch,
            listOf(endpointAccount, expectedAccount),
        ),
    )
}

internal fun CloudReadResult.toSettingsActionFeedback(): SettingsActionFeedback = when (this) {
    CloudReadResult.Unconfigured ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_configure_first))
    CloudReadResult.NoSteamAccount ->
        SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_read_account_required))
    is CloudReadResult.Success -> SettingsActionFeedback.success(
        SettingsText(
            R.plurals.settings_cloud_feedback_read_success,
            listOf(snapshot.observationCount),
            quantity = snapshot.observationCount,
        ),
    )
    is CloudReadResult.Failed -> SettingsActionFeedback.error(failure.settingsMessage())
}

private fun CloudReadFailure.settingsMessage(): SettingsText = when (this) {
    CloudReadFailure.UNREACHABLE -> SettingsText(R.string.settings_cloud_feedback_read_unreachable)
    CloudReadFailure.REJECTED_CREDENTIAL -> SettingsText(R.string.settings_cloud_feedback_read_credential_rejected)
    CloudReadFailure.ACCOUNT_MISMATCH -> SettingsText(R.string.settings_cloud_feedback_read_account_mismatch)
    CloudReadFailure.UNUSABLE_RESPONSE -> SettingsText(R.string.settings_cloud_feedback_read_unusable_response)
}

internal fun CloudPresenceRefilingResult.toSettingsActionFeedback(): SettingsActionFeedback =
    SettingsActionFeedback.success(toSettingsText())

private fun CloudPresenceRefilingResult.toSettingsText(): SettingsText = when (operation) {
    CloudPresenceRefilingOperation.APPLIED -> SettingsText(
        R.string.settings_cloud_refiling_applied,
        listOf(
            SettingsText(
                R.plurals.settings_cloud_refiling_sessions_applied,
                listOf(sessionsRefiled),
                quantity = sessionsRefiled,
            ),
            SettingsText(
                R.plurals.settings_cloud_refiling_dates,
                listOf(datesAffected.size),
                quantity = datesAffected.size,
            ),
        ),
    )
    CloudPresenceRefilingOperation.REVERSED -> SettingsText(
        R.string.settings_cloud_refiling_reversed,
        listOf(
            SettingsText(
                R.plurals.settings_cloud_refiling_sessions_restored,
                listOf(sessionsRefiled),
                quantity = sessionsRefiled,
            ),
            SettingsText(
                R.plurals.settings_cloud_refiling_dates,
                listOf(datesAffected.size),
                quantity = datesAffected.size,
            ),
        ),
    )
    CloudPresenceRefilingOperation.NO_OP -> SettingsText(R.string.settings_cloud_refiling_not_needed)
}

data class SettingsUiState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    /** Active SteamID64, shown in the Account section when configured. */
    val steamId: String = "",
    /** Masked form of the API key; the raw key never reaches the UI. */
    val apiKeyMasked: String = "",
    /** Cloud reader endpoint; the credential itself is exposed only in masked form. */
    val cloudEndpoint: String = "",
    val cloudTokenMasked: String = "",
    val cloudLastSuccessAt: Long? = null,
    val cloudLastFailureAt: Long? = null,
    val cloudLastFailure: CloudReadFailure? = null,
    val cloudHealthy: Boolean? = null,
    val cloudBusy: Boolean = false,
    val cloudRoutine: CloudRoutineState = CloudRoutineState(),
    val cloudReadSummary: CloudReadSummary = CloudReadSummary(),
    val cloudMessage: SettingsText? = null,
    val cloudMessageSeverity: SettingsResultSeverity? = null,
    val cloudPresenceRefilingApplied: Boolean = false,
    val cloudPresenceRefilingBusy: Boolean = false,
    val cloudPresenceRefilingMessage: SettingsText? = null,
    val cloudPresenceRefilingMessageSeverity: SettingsResultSeverity? = null,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    val isSyncing: Boolean = false,
    val isReconciling: Boolean = false,
    val genreEnrichmentStatus: GenreEnrichmentStatus = GenreEnrichmentStatus.IDLE,
    val steamAssetStatus: SteamAssetDownloadStatus = SteamAssetDownloadStatus.IDLE,
    val steamAssetProgress: SteamAssetDownloadProgress? = null,
    val storedSteamAssetCount: Int = 0,
    val storedSteamAssetBytes: Long = 0L,
    val lastSteamAssetRun: SteamAssetRunSummary? = null,
    val hasSteamAssetInventory: Boolean = false,
    /** Explicit opt-in to poll Steam every 30 seconds before a game is detected. */
    val liveMonitorEnabled: Boolean = false,
    /** True once historical Steam playtime has been imported (one-time). */
    val historyImported: Boolean = false,
    val isImportingHistory: Boolean = false,
    /** The persisted rules — what "discard" returns to and what a change is measured against. */
    val savedConfig: RuleConfig = RuleConfig(),
    val draft: RuleDraft = RuleDraft.from(RuleConfig()),
    val advancedExpanded: Boolean = false,
    /** True while a preview recompute runs, so the save affordance can show progress. */
    val previewing: Boolean = false,
    val confirmation: RuleChangeConfirmation? = null,
    // --- Data & Backup (add-backup-restore) ---
    val autoSnapshotEnabled: Boolean = true,
    val snapshotRetentionCount: Int = 7,
    val snapshotIntervalHours: Int = 24,
    /** Retained automatic snapshots, most recent first. */
    val snapshots: List<SnapshotMeta> = emptyList(),
    /** True while an export/import/restore is in flight. */
    val backupBusy: Boolean = false,
    /** One-shot status text (export/import success, or a rejected-file reason). */
    val backupMessage: SettingsText? = null,
    /** True while a cross-account mismatch warning awaits the user's confirm/cancel. */
    val mismatchImportPending: Boolean = false,
    /** The mismatched backup's recorded SteamID64, for the warning dialog's text. */
    val mismatchImportSteamId: String = "",
    /** How many games are hidden, for the section's summary line (add-hidden-games). */
    val hiddenGameCount: Int = 0,
    /** How many library items the store reports as non-games and that are not hidden yet. */
    val nonGameCandidateCount: Int = 0,
    val appUpdateState: AppUpdateState = AppUpdateState(),
    val updateCheckInProgress: Boolean = false,
    val updateCheckMessage: SettingsText? = null,
    val updateCheckSeverity: SettingsResultSeverity? = null,
    val manualSharedGameInput: String = "",
    val manualSharedGameBusy: Boolean = false,
    val manualSharedGameFeedback: ManualImportFeedback? = null,
    /**
     * Family-shared games the player removed, newest first. Empty means nothing was ever removed,
     * and the section is not shown at all — a permanently empty list would be a standing
     * explanation of a feature most players never touch.
     */
    val removedSharedGames: List<RemovedSharedGame> = emptyList(),
    /** Gathered-at time of the currently applied HLTB dataset; null if none has ever been applied. */
    val hltbDatasetGatheredAt: Long? = null,
    /** How many of the user's owned games the applied dataset covers. */
    val hltbDatasetCoveredGameCount: Int = 0,
    val hltbDatasetCheckInProgress: Boolean = false,
    val hltbDatasetCheckMessage: SettingsText? = null,
    val hltbDatasetCheckSeverity: SettingsResultSeverity? = null,
    /** True while the contribution-export disclosure awaits the user's confirm/decline. */
    val hltbContributionDisclosurePending: Boolean = false,
    val hltbContributionBusy: Boolean = false,
    val hltbContributionMessage: SettingsText? = null,
    val hltbContributionSeverity: SettingsResultSeverity? = null,
) {
    /** The candidate config, or null while any field is invalid. */
    val candidate: RuleConfig? get() = draft.toConfig(savedConfig)

    /** True when there is a valid, saveable change pending. */
    val dirty: Boolean get() = candidate?.let { it != savedConfig } == true

    val hasInvalidField: Boolean get() = draft.invalidFields.isNotEmpty()
}

/**
 * State for the Settings destination: the account, sync, data, and rule-configuration controls
 * that used to be scattered across Home.
 *
 * Rule edits are held as a local [RuleDraft] rather than written through on each keystroke,
 * because every rule is retroactive — persisting one re-evaluates the player's entire recorded
 * history. Saving therefore goes through [UpdateRuleConfigUseCase.preview] first, so the
 * confirmation can state what will actually happen, and only [UpdateRuleConfigUseCase.apply]
 * on an explicit confirm both persists and recomputes.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val updateRuleConfig: UpdateRuleConfigUseCase,
    private val credentials: CredentialsRepository,
    private val settings: SettingsRepository,
    private val backupRepository: BackupRepository,
    private val presenceServiceStarter: PresenceServiceStarter,
    private val syncScheduler: SyncScheduler,
    private val appUpdates: AppUpdateRepository,
    private val steamAssets: SteamAssetRepository,
    private val sharedGames: FamilySharedGameRepository,
    private val hltbDatasetRepository: HltbDatasetRepository,
    private val hltbContributionExporter: HltbContributionExporter,
    hiddenGames: HiddenGamesRepository,
    private val cloudPresence: CloudPresenceRepository,
    private val cloudPresenceIngestor: CloudPresenceSessionIngestor,
    private val cloudPresenceRefiling: CloudPresencePlaytimeRefilingUseCase,
) : ViewModel() {

    // Null until the user touches something: the draft then tracks the edit rather than being
    // reseeded by every unrelated emission from the profile/credentials flows. Reset to null
    // after a save or a discard so it re-seeds from whatever was persisted.
    private val draftEdit = MutableStateFlow<RuleDraft?>(null)
    private val advancedExpanded = MutableStateFlow(false)
    private val previewing = MutableStateFlow(false)
    private val confirmation = MutableStateFlow<RuleChangeConfirmation?>(null)
    private val isImportingHistory = MutableStateFlow(false)

    private val backupBusy = MutableStateFlow(false)
    private val backupMessage = MutableStateFlow<SettingsText?>(null)
    private val pendingMismatchImport = MutableStateFlow<BackupFile?>(null)
    private val snapshots = MutableStateFlow<List<SnapshotMeta>>(emptyList())
    private val updateCheckInProgress = MutableStateFlow(false)
    private val updateCheckMessage = MutableStateFlow<SettingsText?>(null)
    private val updateCheckSeverity = MutableStateFlow<SettingsResultSeverity?>(null)
    private val manualSharedGameInput = MutableStateFlow("")
    private val manualSharedGameBusy = MutableStateFlow(false)
    private val manualSharedGameFeedback = MutableStateFlow<ManualImportFeedback?>(null)
    private val hltbDatasetCheckInProgress = MutableStateFlow(false)
    private val hltbDatasetCheckMessage = MutableStateFlow<SettingsText?>(null)
    private val hltbDatasetCheckSeverity = MutableStateFlow<SettingsResultSeverity?>(null)
    private val hltbDatasetProgress = MutableStateFlow<HltbDatasetProgress?>(null)
    private val hltbContributionDisclosurePending = MutableStateFlow(false)
    private val hltbContributionBusy = MutableStateFlow(false)
    private val hltbContributionMessage = MutableStateFlow<SettingsText?>(null)
    private val hltbContributionSeverity = MutableStateFlow<SettingsResultSeverity?>(null)
    private val cloudBusy = MutableStateFlow(false)
    private val cloudMessage = MutableStateFlow<SettingsActionFeedback?>(null)
    private val cloudRefilingBusy = MutableStateFlow(false)
    private val cloudRefilingMessage = MutableStateFlow<SettingsActionFeedback?>(null)
    // The account detail can be disposed by Back while WorkManager keeps syncing, so attribution
    // belongs to this graph-scoped state holder rather than the detail composable.
    private val manualSyncFeedback = ManualSyncFeedbackTracker()
    /** Held only between a successful [prepareContributionExport] and the SAF destination pick. */
    private var preparedContribution: HltbContributionPreparation.Ready? = null
    private val _hapticIntents = MutableSharedFlow<HapticIntent>(extraBufferCapacity = 4)
    private val _toastMessages = MutableSharedFlow<SettingsText>(extraBufferCapacity = 4)
    /** Emits a suggested file name once a contribution is prepared and ready to be written. */
    private val _contributionExportRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessages: SharedFlow<SettingsText> = _toastMessages.asSharedFlow()
    val hapticIntents: SharedFlow<HapticIntent> = _hapticIntents.asSharedFlow()
    val contributionExportRequests: SharedFlow<String> = _contributionExportRequests.asSharedFlow()

    init {
        refreshSnapshots()
        viewModelScope.launch { cloudPresence.refreshConfiguration() }
        viewModelScope.launch {
            combine(profileRepository.syncInProgress, profileRepository.profile) { syncing, profile ->
                syncing to profile?.lastSyncError
            }.collect { (syncing, lastSyncError) ->
                if (manualSyncFeedback.onSyncStateChanged(syncing, lastSyncError)) {
                    _hapticIntents.tryEmit(HapticIntent.Reject)
                }
            }
        }
    }

    private val assetWorkState = combine(syncScheduler.steamAssetDownloadStatus, syncScheduler.steamAssetDownloadProgress) { status, progress ->
        status to progress
    }

    // Counts only: the section itself owns the list and every mutation (HiddenGamesViewModel).
    private val hiddenState = combine(
        hiddenGames.hiddenGames,
        hiddenGames.nonGameCandidates,
    ) { hidden, candidates -> hidden.size to candidates.size }

    private val cloudState = combine(
        cloudPresence.configuration,
        cloudPresence.status,
    ) { configuration, status ->
        CloudLocal(
            endpoint = configuration?.endpoint.orEmpty(),
            tokenMasked = configuration?.maskedToken.orEmpty(),
            lastSuccessAt = status.lastSuccessAt,
            lastFailureAt = status.lastFailureAt,
            lastFailure = status.lastFailure,
            healthy = status.healthy,
        )
    }

    private val storedState = combine(
        profileRepository.profile,
        credentials.credentialsStateFlow,
        settings.ruleConfig,
        profileRepository.syncInProgress,
        settings.autoSnapshotSettings,
    ) { profile, credState, config, syncing, autoSnapshot ->
        val configured = credState as? CredentialsState.Configured
        SettingsUiState(
            loading = false,
            configured = configured != null,
            steamId = configured?.steamId ?: "",
            apiKeyMasked = configured?.let { maskApiKey(it.apiKey) } ?: "",
            lastSyncAt = profile?.lastSyncAt ?: 0L,
            lastSyncError = profile?.lastSyncError,
            isSyncing = syncing,
            historyImported = profile?.playtimeBackfilled ?: false,
            savedConfig = config,
            draft = RuleDraft.from(config),
            autoSnapshotEnabled = autoSnapshot.enabled,
            snapshotRetentionCount = autoSnapshot.retentionCount,
            snapshotIntervalHours = autoSnapshot.intervalHours,
        )
    }.combine(settings.liveMonitorEnabled) { state, monitorEnabled ->
        state.copy(liveMonitorEnabled = monitorEnabled)
    }.combine(settings.cloudPresenceRefilingApplied) { state, applied ->
        state.copy(cloudPresenceRefilingApplied = applied)
    }.combine(syncScheduler.genreEnrichmentStatus) { state, genreStatus ->
        state.copy(genreEnrichmentStatus = genreStatus)
    }.combine(profileRepository.reconciliationInProgress) { state, reconciling ->
        state.copy(isReconciling = reconciling)
    }.combine(steamAssets.storageState) { state, asset ->
        state.copy(
            storedSteamAssetCount = asset.storedCount,
            storedSteamAssetBytes = asset.storedBytes,
            hasSteamAssetInventory = asset.hasInventory,
            lastSteamAssetRun = asset.lastRun,
        )
    }.combine(sharedGames.removedGames) { state, removed ->
        state.copy(removedSharedGames = removed)
    }.combine(hiddenState) { state, hidden ->
        state.copy(hiddenGameCount = hidden.first, nonGameCandidateCount = hidden.second)
    }

    private val ruleLocalState = combine(
        draftEdit,
        advancedExpanded,
        previewing,
        confirmation,
        isImportingHistory,
    ) { edit, expanded, isPreviewing, pending, importing ->
        RuleLocal(edit, expanded, isPreviewing, pending, importing)
    }

    private val backupLocalState = combine(
        backupBusy,
        backupMessage,
        pendingMismatchImport,
        snapshots,
    ) { busy, message, mismatch, list ->
        BackupLocal(busy, message, mismatch, list)
    }

    private val localState = combine(ruleLocalState, backupLocalState) { rule, backup ->
        Local(rule, backup)
    }

    private val manualSharedState = combine(
        manualSharedGameInput,
        manualSharedGameBusy,
        manualSharedGameFeedback,
    ) { input, busy, message -> Triple(input, busy, message) }

    private val hltbDatasetLocalState = combine(
        hltbDatasetRepository.coverage,
        hltbDatasetCheckInProgress,
        combine(hltbDatasetCheckMessage, hltbDatasetProgress, hltbDatasetCheckSeverity) { message, progress, severity ->
            DatasetCheckLocal(progress?.describe() ?: message, severity)
        },
    ) { coverage, checking, message ->
        HltbDatasetLocal(
            coverage.gatheredAt,
            coverage.coveredGameCount,
            checking,
            message.message,
            message.severity,
        )
    }

    private val hltbContributionLocalState = combine(
        hltbContributionDisclosurePending,
        hltbContributionBusy,
        hltbContributionMessage,
        hltbContributionSeverity,
    ) { disclosurePending, busy, message, severity ->
        HltbContributionLocal(disclosurePending, busy, message, severity)
    }

    private val hltbLocalState = combine(
        hltbDatasetLocalState,
        hltbContributionLocalState,
    ) { dataset, contribution -> HltbLocal(dataset, contribution) }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(storedState, localState) { stored, local ->
            stored.copy(
                draft = local.rule.draft ?: stored.draft,
                advancedExpanded = local.rule.advancedExpanded,
                previewing = local.rule.previewing,
                confirmation = local.rule.confirmation,
                isImportingHistory = local.rule.importing,
                backupBusy = local.backup.busy,
                backupMessage = local.backup.message,
                snapshots = local.backup.snapshots,
                mismatchImportPending = local.backup.pendingMismatch != null,
                mismatchImportSteamId = local.backup.pendingMismatch?.identity?.steamId64 ?: "",
            )
        },
        appUpdates.state,
        combine(updateCheckInProgress, updateCheckMessage, updateCheckSeverity) { inProgress, message, severity ->
            UpdateCheckLocal(inProgress, message, severity)
        },
    ) { state, updates, updateLocal ->
        state.copy(
            appUpdateState = updates,
            updateCheckInProgress = updateLocal.inProgress,
            updateCheckMessage = updateLocal.message,
            updateCheckSeverity = updateLocal.severity,
        )
    }.combine(manualSharedState) { state, manual ->
        state.copy(
            manualSharedGameInput = manual.first,
            manualSharedGameBusy = manual.second,
            manualSharedGameFeedback = manual.third,
        )
    }.combine(hltbLocalState) { state, hltb ->
        state.copy(
            hltbDatasetGatheredAt = hltb.dataset.gatheredAt,
            hltbDatasetCoveredGameCount = hltb.dataset.coveredCount,
            hltbDatasetCheckInProgress = hltb.dataset.checking,
            hltbDatasetCheckMessage = hltb.dataset.checkMessage,
            hltbDatasetCheckSeverity = hltb.dataset.checkSeverity,
            hltbContributionDisclosurePending = hltb.contribution.disclosurePending,
            hltbContributionBusy = hltb.contribution.busy,
            hltbContributionMessage = hltb.contribution.message,
            hltbContributionSeverity = hltb.contribution.severity,
        )
    }.combine(cloudState) { state, cloud ->
        state.copy(
            cloudEndpoint = cloud.endpoint,
            cloudTokenMasked = cloud.tokenMasked,
            cloudLastSuccessAt = cloud.lastSuccessAt,
            cloudLastFailureAt = cloud.lastFailureAt,
            cloudLastFailure = cloud.lastFailure,
            cloudHealthy = cloud.healthy,
        )
    }.combine(settings.cloudRoutineState) { state, routine ->
        state.copy(cloudRoutine = routine)
    }.combine(cloudPresence.readSummary) { state, summary ->
        state.copy(cloudReadSummary = summary)
    }.combine(combine(cloudBusy, cloudMessage) { busy, message -> busy to message }) { state, local ->
        state.copy(
            cloudBusy = local.first,
            cloudMessage = local.second?.message,
            cloudMessageSeverity = local.second?.severity,
        )
    }.combine(combine(cloudRefilingBusy, cloudRefilingMessage) { busy, message -> busy to message }) { state, local ->
        state.copy(
            cloudPresenceRefilingBusy = local.first,
            cloudPresenceRefilingMessage = local.second?.message,
            cloudPresenceRefilingMessageSeverity = local.second?.severity,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun syncNow() {
        manualSyncFeedback.onManualSyncStarted()
        profileRepository.syncNow()
    }

    fun verifyCloudPresence(endpoint: String, token: String) {
        if (cloudBusy.value || cloudRefilingBusy.value) return
        viewModelScope.launch {
            cloudBusy.value = true
            cloudMessage.value = null
            try {
                cloudMessage.value = settingsCloudActionFeedback(
                    failureMessage = SettingsText(R.string.settings_cloud_feedback_verify_failed),
                ) {
                    cloudPresence.verifyAndSave(
                        endpoint,
                        token,
                        consume = cloudPresenceIngestor::ingest,
                    ).toSettingsActionFeedback()
                }
            } finally {
                cloudBusy.value = false
            }
        }
    }

    fun readCloudPresence() {
        if (cloudBusy.value || cloudRefilingBusy.value) return
        viewModelScope.launch {
            cloudBusy.value = true
            cloudMessage.value = null
            try {
                cloudMessage.value = settingsCloudActionFeedback(
                    failureMessage = SettingsText(R.string.settings_cloud_feedback_read_failed),
                ) {
                    cloudPresence.read(consume = cloudPresenceIngestor::ingest)
                        .toSettingsActionFeedback()
                }
            } finally {
                cloudBusy.value = false
            }
        }
    }

    fun setCloudRoutinePolicy(policy: CloudRoutinePolicy) {
        viewModelScope.launch {
            if (cloudPresence.configuration.first() != null) settings.setCloudRoutinePolicy(policy)
        }
    }

    fun removeCloudPresence() {
        if (cloudBusy.value || cloudRefilingBusy.value) return
        viewModelScope.launch {
            cloudBusy.value = true
            cloudMessage.value = null
            try {
                cloudMessage.value = settingsCloudActionFeedback(
                    failureMessage = SettingsText(R.string.settings_cloud_feedback_remove_failed),
                ) {
                    cloudPresence.removeConfiguration()
                    SettingsActionFeedback.success(SettingsText(R.string.settings_cloud_feedback_removed))
                }
            } finally {
                cloudBusy.value = false
            }
        }
    }

    fun refileCloudPresence() {
        if (cloudBusy.value || cloudRefilingBusy.value) return
        viewModelScope.launch {
            cloudBusy.value = true
            cloudRefilingBusy.value = true
            cloudMessage.value = null
            cloudRefilingMessage.value = null
            try {
                cloudRefilingMessage.value = settingsCloudActionFeedback(
                    failureMessage = SettingsText(R.string.settings_cloud_feedback_refile_failed),
                ) {
                    when (val read = cloudPresence.readCompleteHistory(
                        trigger = CloudReadTrigger.SETTINGS_MANUAL,
                        consume = cloudPresenceIngestor::ingest,
                    )) {
                        CloudReadResult.Unconfigured ->
                            SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_configure_first))
                        CloudReadResult.NoSteamAccount ->
                            SettingsActionFeedback.error(SettingsText(R.string.settings_cloud_feedback_read_account_required))
                        is CloudReadResult.Failed ->
                            SettingsActionFeedback.error(read.failure.settingsMessage())
                        is CloudReadResult.Success -> {
                            val result = cloudPresenceRefiling.apply(read.snapshot.intervals)
                            if (result.operation == CloudPresenceRefilingOperation.APPLIED) {
                                _hapticIntents.tryEmit(HapticIntent.Confirm)
                            }
                            result.toSettingsActionFeedback()
                        }
                    }
                }
            } finally {
                cloudBusy.value = false
                cloudRefilingBusy.value = false
            }
        }
    }

    fun reverseCloudPresenceRefiling() {
        if (cloudBusy.value || cloudRefilingBusy.value) return
        viewModelScope.launch {
            cloudRefilingBusy.value = true
            cloudRefilingMessage.value = null
            try {
                cloudRefilingMessage.value = settingsCloudActionFeedback(
                    failureMessage = SettingsText(R.string.settings_cloud_feedback_restore_failed),
                ) {
                    val result = cloudPresenceRefiling.reverse()
                    if (result.operation == CloudPresenceRefilingOperation.REVERSED) {
                        _hapticIntents.tryEmit(HapticIntent.Confirm)
                    }
                    result.toSettingsActionFeedback()
                }
            } finally {
                cloudRefilingBusy.value = false
            }
        }
    }

    fun onManualSharedGameInputChanged(value: String) {
        manualSharedGameInput.value = value
        manualSharedGameFeedback.value = null
    }

    fun importManualSharedGame() {
        if (manualSharedGameBusy.value) return
        viewModelScope.launch {
            manualSharedGameBusy.value = true
            manualSharedGameFeedback.value = null
            try {
                val configured = credentials.currentCredentials()
                val feedback = if (configured == null) {
                    ManualImportFeedback(
                        ManualImportFeedbackTone.ERROR,
                        SettingsText(R.string.settings_shared_game_title_account_required),
                        SettingsText(R.string.settings_shared_game_message_account_required),
                        SettingsText(R.string.settings_shared_game_toast_not_added),
                    )
                } else {
                    manualImportFeedback(
                        sharedGames.importManually(
                            manualSharedGameInput.value,
                            configured.apiKey,
                            configured.steamId,
                        ),
                    )
                }
                manualSharedGameFeedback.value = feedback
                _toastMessages.tryEmit(manualImportToast(feedback))
                when (feedback.tone) {
                    ManualImportFeedbackTone.SUCCESS -> _hapticIntents.tryEmit(HapticIntent.Confirm)
                    ManualImportFeedbackTone.ERROR -> _hapticIntents.tryEmit(HapticIntent.Reject)
                    ManualImportFeedbackTone.INFO -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val feedback = ManualImportFeedback(
                    ManualImportFeedbackTone.ERROR,
                    SettingsText(R.string.settings_shared_game_title_import_failed),
                    SettingsText(R.string.settings_shared_game_message_import_failed),
                    SettingsText(R.string.settings_shared_game_toast_not_added),
                )
                manualSharedGameFeedback.value = feedback
                _toastMessages.tryEmit(manualImportToast(feedback))
                _hapticIntents.tryEmit(HapticIntent.Reject)
            } finally {
                manualSharedGameBusy.value = false
            }
        }
    }

    fun downloadSteamAssets(mode: SteamAssetDownloadMode) = syncScheduler.downloadSteamAssets(mode)

    fun cancelSteamAssetDownload() = syncScheduler.cancelSteamAssetDownload()

    /** Manual checks bypass the worker cadence but persist the same last-attempt timestamp. */
    fun checkForUpdates() {
        if (updateCheckInProgress.value) return
        viewModelScope.launch {
            updateCheckInProgress.value = true
            updateCheckMessage.value = null
            updateCheckSeverity.value = null
            try {
                when (appUpdates.check(force = true)) {
                    is UpdateCheckResult.Available -> updateCheckSeverity.value = SettingsResultSeverity.SUCCESS
                    is UpdateCheckResult.NoUpdate,
                    is UpdateCheckResult.SkippedRecent,
                    -> {
                        updateCheckMessage.value = SettingsText(R.string.settings_up_to_date)
                        updateCheckSeverity.value = SettingsResultSeverity.SUCCESS
                    }
                    is UpdateCheckResult.Failed -> {
                        updateCheckMessage.value = SettingsText(R.string.settings_update_check_failed)
                        updateCheckSeverity.value = SettingsResultSeverity.ERROR
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                updateCheckMessage.value = SettingsText(R.string.settings_update_check_failed)
                updateCheckSeverity.value = SettingsResultSeverity.ERROR
            } finally {
                updateCheckInProgress.value = false
            }
        }
    }

    /** Checks the release service for a newer completion-times dataset and applies it if found. */
    fun checkHltbDataset() {
        if (hltbDatasetCheckInProgress.value) return
        viewModelScope.launch {
            hltbDatasetCheckInProgress.value = true
            hltbDatasetCheckMessage.value = null
            hltbDatasetCheckSeverity.value = null
            try {
                val result = hltbDatasetRepository.checkAndApply { progress ->
                    hltbDatasetProgress.value = progress
                }
                when (result) {
                    is HltbDatasetCheckResult.Applied -> {
                        hltbDatasetCheckMessage.value = SettingsText(
                            R.plurals.settings_completion_dataset_updated,
                            listOf(result.gamesGainingLengths),
                            quantity = result.gamesGainingLengths,
                        )
                        hltbDatasetCheckSeverity.value = SettingsResultSeverity.SUCCESS
                    }
                    is HltbDatasetCheckResult.UpToDate -> {
                        hltbDatasetCheckMessage.value = SettingsText(R.string.settings_completion_already_up_to_date)
                        hltbDatasetCheckSeverity.value = SettingsResultSeverity.SUCCESS
                    }
                    is HltbDatasetCheckResult.Failed -> {
                        hltbDatasetCheckMessage.value = SettingsText(R.string.settings_update_check_failed)
                        hltbDatasetCheckSeverity.value = SettingsResultSeverity.ERROR
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                hltbDatasetCheckMessage.value = SettingsText(R.string.settings_update_check_failed)
                hltbDatasetCheckSeverity.value = SettingsResultSeverity.ERROR
            } finally {
                hltbDatasetCheckInProgress.value = false
                hltbDatasetProgress.value = null
            }
        }
    }

    private fun HltbDatasetProgress.describe(): SettingsText = when (this) {
        HltbDatasetProgress.Checking -> SettingsText(R.string.settings_completion_checking_newer)
        is HltbDatasetProgress.Downloading -> SettingsText(R.string.settings_completion_downloading)
        HltbDatasetProgress.Verifying -> SettingsText(R.string.settings_completion_verifying)
        HltbDatasetProgress.Applying -> SettingsText(R.string.settings_completion_applying)
    }

    /** Step 1 of the contribution export: show what the file reveals before anything is written. */
    fun onRequestContributionExport() {
        hltbContributionDisclosurePending.value = true
    }

    fun onDismissContributionDisclosure() {
        hltbContributionDisclosurePending.value = false
    }

    /**
     * The user proceeded past the disclosure. Prepares the contribution now, before any SAF
     * destination is chosen, so "nothing to contribute" never opens a file picker for nothing.
     */
    fun onConfirmContributionDisclosure() {
        hltbContributionDisclosurePending.value = false
        if (hltbContributionBusy.value) return
        viewModelScope.launch {
            hltbContributionBusy.value = true
            hltbContributionMessage.value = null
            hltbContributionSeverity.value = null
            try {
                when (val prepared = hltbContributionExporter.prepare()) {
                    HltbContributionPreparation.NothingToContribute -> {
                        hltbContributionMessage.value = SettingsText(R.string.settings_completion_no_resolved_games)
                        hltbContributionSeverity.value = SettingsResultSeverity.INFO
                        hltbContributionBusy.value = false
                    }
                    is HltbContributionPreparation.Ready -> {
                        preparedContribution = prepared
                        _contributionExportRequests.tryEmit(HltbContributionExporter.DEFAULT_FILE_NAME)
                        // hltbContributionBusy stays true until the destination is picked or cancelled.
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                hltbContributionMessage.value = SettingsText(R.string.settings_completion_prepare_failed)
                hltbContributionSeverity.value = SettingsResultSeverity.ERROR
                hltbContributionBusy.value = false
            }
        }
    }

    /** The SAF picker returned without a destination — nothing was written. */
    fun onContributionExportCancelled() {
        preparedContribution = null
        hltbContributionBusy.value = false
    }

    fun onContributionDestinationPicked(destination: Uri, contentResolver: ContentResolver) {
        val prepared = preparedContribution
        preparedContribution = null
        if (prepared == null) {
            hltbContributionBusy.value = false
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    hltbContributionExporter.writeTo(prepared, destination, contentResolver)
                }
                hltbContributionMessage.value = SettingsText(
                    R.plurals.settings_completion_contribution_saved,
                    listOf(prepared.mappingCount),
                    quantity = prepared.mappingCount,
                )
                hltbContributionSeverity.value = SettingsResultSeverity.SUCCESS
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                hltbContributionMessage.value = SettingsText(R.string.settings_completion_contribution_save_failed)
                hltbContributionSeverity.value = SettingsResultSeverity.ERROR
            } finally {
                hltbContributionBusy.value = false
            }
        }
    }

    /** Enqueue a one-time full achievement refresh, regardless of charging/wifi conditions. */
    fun reconcileNow() {
        viewModelScope.launch { profileRepository.reconcileNow() }
    }

    /** Start only from this visible Settings interaction; disabling is observed by the service. */
    fun onLiveMonitorEnabledChanged(enabled: Boolean) = viewModelScope.launch {
        settings.setLiveMonitorEnabled(enabled)
        _hapticIntents.tryEmit(HapticIntent.Toggle(enabled))
        if (enabled) presenceServiceStarter.startFromForeground(trigger = "settings")
    }

    fun setAdvancedExpanded(expanded: Boolean) = advancedExpanded.update { expanded }

    fun onFieldChanged(field: RuleField, text: String) {
        draftEdit.update { (it ?: RuleDraft.from(uiState.value.savedConfig)).with(field, text) }
    }

    fun onQuestModeChanged(mode: QuestMode) {
        draftEdit.update { (it ?: RuleDraft.from(uiState.value.savedConfig)).copy(questMode = mode) }
    }

    /** Abandon the pending edit and fall back to what is persisted. */
    fun discardChanges() {
        draftEdit.value = null
        confirmation.value = null
    }

    /**
     * Run the candidate config through a real (non-persisting) recompute and raise the
     * confirmation describing its concrete effect. Nothing is written by this.
     */
    fun requestSave() {
        val state = uiState.value
        val candidate = state.candidate ?: return
        val kind = state.savedConfig.changeKind(candidate)
        if (!kind.any || previewing.value) return

        viewModelScope.launch {
            previewing.update { true }
            try {
                val before = profileRepository.currentStats()
                val after = updateRuleConfig.preview(candidate)
                confirmation.value = RuleChangeConfirmation(
                    config = candidate,
                    kind = kind,
                    currentStreakBefore = before?.currentStreak ?: 0,
                    currentStreakAfter = after.currentStreak,
                    longestStreakBefore = before?.longestStreak ?: 0,
                    longestStreakAfter = after.longestStreak,
                    totalXpBefore = before?.totalXp ?: 0L,
                    totalXpAfter = after.xpState.totalXp,
                    levelBefore = before?.level ?: 1,
                    levelAfter = after.xpState.level,
                )
            } finally {
                previewing.update { false }
            }
        }
    }

    /** Decline: persist nothing, run no recompute, and leave the edit on screen. */
    fun dismissConfirmation() {
        confirmation.value = null
    }

    /** Confirm: persist the config and recompute under it as one operation. */
    fun confirmSave() {
        val pending = confirmation.value ?: return
        confirmation.value = null
        viewModelScope.launch {
            updateRuleConfig.apply(pending.config)
            // Re-seed the draft from what was persisted, so the screen reflects storage again.
            draftEdit.value = null
            _hapticIntents.tryEmit(HapticIntent.Confirm)
        }
    }

    /** Run the one-time historical-playtime import. Idempotent in the use case. */
    fun importSteamHistory() = runHistoryOp { profileRepository.importSteamHistory() }

    /** Undo a prior import so it can be run again (recovery / opt-out). */
    fun resetHistoryImport() = runHistoryOp { profileRepository.resetSteamHistoryImport() }

    // Serialize import/reset behind one in-flight flag so the buttons show progress and
    // concurrent taps can't overlap.
    private fun runHistoryOp(op: suspend () -> Unit) {
        if (isImportingHistory.value) return
        viewModelScope.launch {
            isImportingHistory.update { true }
            try {
                op()
            } finally {
                isImportingHistory.update { false }
            }
        }
    }

    fun onAutoSnapshotEnabledChanged(enabled: Boolean) = viewModelScope.launch {
        settings.setAutoSnapshotEnabled(enabled)
    }

    fun onSnapshotRetentionCountChanged(count: Int) = viewModelScope.launch {
        settings.setSnapshotRetentionCount(count)
    }

    fun onSnapshotIntervalHoursChanged(hours: Int) = viewModelScope.launch {
        settings.setSnapshotIntervalHours(hours)
    }

    /** Export a backup to a user-chosen SAF destination. Always available. */
    fun onExportBackup(destination: Uri) = runBackupOp {
        backupRepository.exportTo(destination)
        backupMessage.value = SettingsText(R.string.settings_backup_exported)
    }

    /** A file was picked via SAF's OpenDocument — validate, then import or warn on mismatch. */
    fun onImportBackupPicked(source: Uri) = runBackupOp {
        when (val parsed = backupRepository.parseFrom(source)) {
            ParsedBackup.InvalidFormat ->
                backupMessage.value = SettingsText(R.string.settings_backup_invalid_file)
            is ParsedBackup.Invalid -> backupMessage.value = parsed.problems.describeRejection()
            is ParsedBackup.TooLarge -> backupMessage.value = parsed.describeTooLarge()
            is ParsedBackup.Valid -> proceedOrWarn(parsed.file)
        }
    }

    /** Restore a listed automatic snapshot through the same merge path as a manual import. */
    fun onRestoreSnapshot(snapshot: SnapshotMeta) = runBackupOp {
        when (val parsed = backupRepository.parseSnapshot(snapshot.fileName)) {
            ParsedBackup.InvalidFormat -> backupMessage.value = SettingsText(R.string.settings_backup_snapshot_unreadable)
            is ParsedBackup.Invalid -> backupMessage.value = parsed.problems.describeRejection()
            is ParsedBackup.TooLarge -> backupMessage.value = parsed.describeTooLarge()
            is ParsedBackup.Valid -> proceedOrWarn(parsed.file)
        }
    }

    private suspend fun proceedOrWarn(file: BackupFile) {
        if (backupRepository.isMismatched(file)) {
            pendingMismatchImport.value = file
        } else {
            backupRepository.importBackup(file)
            backupMessage.value = SettingsText(R.string.settings_backup_imported)
            refreshSnapshots()
            _hapticIntents.tryEmit(HapticIntent.Confirm)
        }
    }

    /** The user confirmed the import despite the cross-account warning. */
    fun onConfirmMismatchImport() {
        val file = pendingMismatchImport.value ?: return
        pendingMismatchImport.value = null
        runBackupOp {
            backupRepository.importBackup(file)
            backupMessage.value = SettingsText(R.string.settings_backup_imported)
            refreshSnapshots()
            _hapticIntents.tryEmit(HapticIntent.Confirm)
        }
    }

    /** Delete one retained automatic snapshot after the user confirms the action in the UI. */
    fun onDeleteSnapshot(snapshot: SnapshotMeta) = runBackupOp {
        if (backupRepository.deleteSnapshot(snapshot.fileName)) {
            backupMessage.value = SettingsText(R.string.settings_backup_snapshot_deleted)
            _hapticIntents.tryEmit(HapticIntent.Confirm)
        } else {
            backupMessage.value = SettingsText(R.string.settings_backup_snapshot_unavailable)
        }
        refreshSnapshots()
    }

    fun onDismissMismatchImport() {
        pendingMismatchImport.value = null
    }

    fun onDismissBackupMessage() {
        backupMessage.value = null
    }

    /** Restore a removed Family Shared game and report the outcome through a toast. */
    fun restoreSharedGame(appId: Long) {
        viewModelScope.launch {
            try {
                if (sharedGames.reverseRemoval(appId)) {
                    _toastMessages.tryEmit(SettingsText(R.string.settings_shared_game_toast_restored))
                    _hapticIntents.tryEmit(HapticIntent.Confirm)
                } else {
                    _toastMessages.tryEmit(SettingsText(R.string.settings_shared_game_toast_restore_failed))
                    _hapticIntents.tryEmit(HapticIntent.Reject)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _toastMessages.tryEmit(SettingsText(R.string.settings_shared_game_toast_restore_failed))
                _hapticIntents.tryEmit(HapticIntent.Reject)
            }
        }
    }

    private fun refreshSnapshots() {
        snapshots.value = backupRepository.listSnapshots()
    }

    // Serialize export/import/restore behind one in-flight flag, mirroring runHistoryOp.
    private fun runBackupOp(op: suspend () -> Unit) {
        if (backupBusy.value) return
        viewModelScope.launch {
            backupBusy.update { true }
            try {
                op()
            } catch (e: Exception) {
                backupMessage.value = SettingsText(R.string.settings_backup_operation_failed, listOf(e.message))
            } finally {
                backupBusy.update { false }
            }
        }
    }

    private data class RuleLocal(
        val draft: RuleDraft?,
        val advancedExpanded: Boolean,
        val previewing: Boolean,
        val confirmation: RuleChangeConfirmation?,
        val importing: Boolean,
    )

    private data class BackupLocal(
        val busy: Boolean,
        val message: SettingsText?,
        val pendingMismatch: BackupFile?,
        val snapshots: List<SnapshotMeta>,
    )

    private data class Local(val rule: RuleLocal, val backup: BackupLocal)

    private data class HltbDatasetLocal(
        val gatheredAt: Long?,
        val coveredCount: Int,
        val checking: Boolean,
        val checkMessage: SettingsText?,
        val checkSeverity: SettingsResultSeverity?,
    )

    private data class DatasetCheckLocal(
        val message: SettingsText?,
        val severity: SettingsResultSeverity?,
    )

    private data class HltbContributionLocal(
        val disclosurePending: Boolean,
        val busy: Boolean,
        val message: SettingsText?,
        val severity: SettingsResultSeverity?,
    )

    private data class HltbLocal(val dataset: HltbDatasetLocal, val contribution: HltbContributionLocal)

    private data class UpdateCheckLocal(
        val inProgress: Boolean,
        val message: SettingsText?,
        val severity: SettingsResultSeverity?,
    )

    private data class CloudLocal(
        val endpoint: String,
        val tokenMasked: String,
        val lastSuccessAt: Long?,
        val lastFailureAt: Long?,
        val lastFailure: CloudReadFailure?,
        val healthy: Boolean?,
    )
}


/** Names what failed and where, rather than reporting only that the import failed (tasks.md 2.5). */
private fun List<BackupValidationProblem>.describeRejection(): SettingsText = SettingsText(
    R.string.settings_backup_rejected,
    listOf(
        SettingsTextList(
            items = map { problem ->
                SettingsText(
                    R.string.settings_backup_validation_problem,
                    listOf(problem.recordType, problem.index, problem.detail),
                )
            },
            separatorResId = R.string.settings_backup_validation_separator,
        ),
    ),
)

private fun ParsedBackup.TooLarge.describeTooLarge(): SettingsText {
    fun Long.toMb() = this / (1024 * 1024)
    return SettingsText(
        R.string.settings_backup_too_large,
        listOf(actualBytes.toMb(), limitBytes.toMb()),
    )
}

enum class ManualImportFeedbackTone { SUCCESS, INFO, ERROR }

data class ManualImportFeedback(
    val tone: ManualImportFeedbackTone,
    val title: SettingsText,
    val message: SettingsText,
    val toastMessage: SettingsText,
)

internal fun manualImportFeedback(result: ManualSharedGameImportResult): ManualImportFeedback = when (result) {
    ManualSharedGameImportResult.InvalidInput -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        SettingsText(R.string.settings_shared_game_feedback_title_invalid_input),
        SettingsText(R.string.settings_shared_game_feedback_invalid_input),
        SettingsText(R.string.settings_shared_game_toast_not_added),
    )
    is ManualSharedGameImportResult.Owned -> ManualImportFeedback(
        ManualImportFeedbackTone.INFO,
        SettingsText(R.string.settings_shared_game_feedback_title_owned),
        SettingsText(R.string.settings_shared_game_feedback_owned, listOf(result.name)),
        SettingsText(R.string.settings_shared_game_toast_not_added),
    )
    is ManualSharedGameImportResult.Excluded -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        SettingsText(R.string.settings_shared_game_feedback_title_excluded),
        SettingsText(R.string.settings_shared_game_feedback_excluded, listOf(result.appId)),
        SettingsText(R.string.settings_shared_game_toast_not_added),
    )
    is ManualSharedGameImportResult.NotAGame -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        SettingsText(R.string.settings_shared_game_feedback_title_not_game),
        SettingsText(R.string.settings_shared_game_feedback_not_game, listOf(result.appId)),
        SettingsText(R.string.settings_shared_game_toast_not_added),
    )
    is ManualSharedGameImportResult.Unavailable -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        SettingsText(R.string.settings_shared_game_feedback_title_unavailable),
        SettingsText(
            when (result.at) {
                ManualImportUnavailableAt.OWNED_LIBRARY -> R.string.settings_shared_game_feedback_owned_unavailable
                ManualImportUnavailableAt.STORE -> R.string.settings_shared_game_feedback_store_unavailable
            },
        ),
        SettingsText(R.string.settings_shared_game_toast_not_added),
    )
    is ManualSharedGameImportResult.Imported -> {
        val probe = when (val data = result.playerData) {
            is PlayerDataProbe.Returned -> if (data.total == 0) {
                SettingsText(R.string.settings_shared_game_feedback_no_achievements)
            } else {
                SettingsText(
                    R.plurals.settings_shared_game_feedback_achievements_returned,
                    listOf(data.total, data.unlocked),
                    quantity = data.total,
                )
            }
            PlayerDataProbe.NoData -> SettingsText(R.string.settings_shared_game_feedback_no_player_data)
            PlayerDataProbe.Unavailable ->
                SettingsText(R.string.settings_shared_game_feedback_achievement_check_unavailable)
        }
        ManualImportFeedback(
            ManualImportFeedbackTone.SUCCESS,
            SettingsText(
                if (result.alreadyTracked) R.string.settings_shared_game_feedback_title_already_tracked
                else R.string.settings_shared_game_feedback_title_imported,
            ),
            SettingsText(
                R.string.settings_shared_game_feedback_imported,
                listOf(
                    SettingsText(
                        if (result.alreadyTracked) R.string.settings_shared_game_feedback_already_tracked
                        else R.string.settings_shared_game_feedback_tracked_now,
                        listOf(result.name),
                    ),
                    probe,
                ),
            ),
            SettingsText(
                if (result.alreadyTracked) R.string.settings_shared_game_toast_already_tracked
                else R.string.settings_shared_game_toast_added,
            ),
        )
    }
}

internal fun manualImportMessage(result: ManualSharedGameImportResult): SettingsText =
    manualImportFeedback(result).message

internal fun manualImportToast(feedback: ManualImportFeedback): SettingsText = feedback.toastMessage
