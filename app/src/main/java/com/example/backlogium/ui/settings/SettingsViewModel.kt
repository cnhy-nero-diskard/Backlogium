package com.example.backlogium.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.backup.BackupFile
import com.example.backlogium.data.backup.BackupRepository
import com.example.backlogium.data.backup.BackupValidationProblem
import com.example.backlogium.data.backup.ParsedBackup
import com.example.backlogium.data.backup.SnapshotMeta
import com.example.backlogium.data.credentials.maskApiKey
import com.example.backlogium.data.hltb.HltbContributionExporter
import com.example.backlogium.data.hltb.HltbContributionPreparation
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SteamAssetDao
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.local.dao.SteamAssetStoredSummary
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
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
    val totalXpBefore: Int,
    val totalXpAfter: Int,
    val levelBefore: Int,
    val levelAfter: Int,
)

data class SettingsUiState(
    val loading: Boolean = true,
    val configured: Boolean = false,
    /** Active SteamID64, shown in the Account section when configured. */
    val steamId: String = "",
    /** Masked form of the API key; the raw key never reaches the UI. */
    val apiKeyMasked: String = "",
    val lastSyncAt: Long = 0L,
    val isSyncing: Boolean = false,
    val isReconciling: Boolean = false,
    val genreEnrichmentStatus: GenreEnrichmentStatus = GenreEnrichmentStatus.IDLE,
    val steamAssetStatus: SteamAssetDownloadStatus = SteamAssetDownloadStatus.IDLE,
    val steamAssetProgress: SteamAssetDownloadProgress? = null,
    val storedSteamAssetCount: Int = 0,
    val storedSteamAssetBytes: Long = 0L,
    val lastSteamAssetRun: SteamAssetDownloadState? = null,
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
    val backupMessage: String? = null,
    /** True while a cross-account mismatch warning awaits the user's confirm/cancel. */
    val mismatchImportPending: Boolean = false,
    /** The mismatched backup's recorded SteamID64, for the warning dialog's text. */
    val mismatchImportSteamId: String = "",
    val appUpdateState: AppUpdateState = AppUpdateState(),
    val updateCheckInProgress: Boolean = false,
    val updateCheckMessage: String? = null,
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
    val hltbDatasetCheckMessage: String? = null,
    /** True while the contribution-export disclosure awaits the user's confirm/decline. */
    val hltbContributionDisclosurePending: Boolean = false,
    val hltbContributionBusy: Boolean = false,
    val hltbContributionMessage: String? = null,
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
    private val steamAssetDao: SteamAssetDao,
    private val sharedGames: FamilySharedGameRepository,
    private val gameDao: GameDao,
    private val hltbDatasetRepository: HltbDatasetRepository,
    private val hltbContributionExporter: HltbContributionExporter,
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
    private val backupMessage = MutableStateFlow<String?>(null)
    private val pendingMismatchImport = MutableStateFlow<BackupFile?>(null)
    private val snapshots = MutableStateFlow<List<SnapshotMeta>>(emptyList())
    private val updateCheckInProgress = MutableStateFlow(false)
    private val updateCheckMessage = MutableStateFlow<String?>(null)
    private val manualSharedGameInput = MutableStateFlow("")
    private val manualSharedGameBusy = MutableStateFlow(false)
    private val manualSharedGameFeedback = MutableStateFlow<ManualImportFeedback?>(null)
    private val hltbDatasetCheckInProgress = MutableStateFlow(false)
    private val hltbDatasetCheckMessage = MutableStateFlow<String?>(null)
    private val hltbDatasetProgress = MutableStateFlow<HltbDatasetProgress?>(null)
    private val hltbContributionDisclosurePending = MutableStateFlow(false)
    private val hltbContributionBusy = MutableStateFlow(false)
    private val hltbContributionMessage = MutableStateFlow<String?>(null)
    /** Held only between a successful [prepareContributionExport] and the SAF destination pick. */
    private var preparedContribution: HltbContributionPreparation.Ready? = null
    private val _hapticIntents = MutableSharedFlow<HapticIntent>(extraBufferCapacity = 4)
    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a suggested file name once a contribution is prepared and ready to be written. */
    private val _contributionExportRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()
    val hapticIntents: SharedFlow<HapticIntent> = _hapticIntents.asSharedFlow()
    val contributionExportRequests: SharedFlow<String> = _contributionExportRequests.asSharedFlow()

    init {
        refreshSnapshots()
    }

    private val assetStoredState = combine(
        steamAssetDao.observeStoredSummary(),
        steamAssetDao.observeLastRun(),
        steamAssetDao.observeHasInventory(),
    ) { summary, lastRun, hasInventory ->
        AssetStoredState(summary, lastRun, hasInventory)
    }
    private val assetWorkState = combine(syncScheduler.steamAssetDownloadStatus, syncScheduler.steamAssetDownloadProgress) { status, progress ->
        status to progress
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
    }.combine(syncScheduler.genreEnrichmentStatus) { state, genreStatus ->
        state.copy(genreEnrichmentStatus = genreStatus)
    }.combine(profileRepository.reconciliationInProgress) { state, reconciling ->
        state.copy(isReconciling = reconciling)
    }.combine(assetStoredState) { state, asset ->
        state.copy(
            storedSteamAssetCount = asset.summary.count,
            storedSteamAssetBytes = asset.summary.bytes,
            hasSteamAssetInventory = asset.hasInventory,
            lastSteamAssetRun = asset.lastRun,
        )
    }.combine(assetWorkState) { state, asset ->
        state.copy(steamAssetStatus = asset.first, steamAssetProgress = asset.second)
    }.combine(sharedGames.removedGames) { state, removed ->
        state.copy(removedSharedGames = removed)
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
        hltbDatasetRepository.appliedState,
        gameDao.observeAppIds(),
        hltbDatasetCheckInProgress,
        combine(hltbDatasetCheckMessage, hltbDatasetProgress) { message, progress ->
            progress?.describe() ?: message
        },
    ) { applied, ownedAppIds, checking, message ->
        val coveredCount = applied?.coveredAppIds?.let { covered -> ownedAppIds.count { it in covered } } ?: 0
        HltbDatasetLocal(applied?.gatheredAt, coveredCount, checking, message)
    }

    private val hltbContributionLocalState = combine(
        hltbContributionDisclosurePending,
        hltbContributionBusy,
        hltbContributionMessage,
    ) { disclosurePending, busy, message ->
        HltbContributionLocal(disclosurePending, busy, message)
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
        combine(updateCheckInProgress, updateCheckMessage) { inProgress, message ->
            inProgress to message
        },
    ) { state, updates, updateLocal ->
        state.copy(
            appUpdateState = updates,
            updateCheckInProgress = updateLocal.first,
            updateCheckMessage = updateLocal.second,
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
            hltbContributionDisclosurePending = hltb.contribution.disclosurePending,
            hltbContributionBusy = hltb.contribution.busy,
            hltbContributionMessage = hltb.contribution.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun syncNow() = profileRepository.syncNow()

    fun onManualSharedGameInputChanged(value: String) {
        manualSharedGameInput.value = value
        manualSharedGameFeedback.value = null
    }

    fun importManualSharedGame() {
        if (manualSharedGameBusy.value) return
        viewModelScope.launch {
            manualSharedGameBusy.value = true
            try {
                val configured = credentials.currentCredentials()
                val feedback = if (configured == null) {
                    ManualImportFeedback(
                        ManualImportFeedbackTone.ERROR,
                        "Steam account required",
                        "Connect a Steam account before checking a game.",
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
                    "Import failed",
                    "Backlogium couldn't finish the check. Try again.",
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
            try {
                when (appUpdates.check(force = true)) {
                    is UpdateCheckResult.Available -> Unit
                    is UpdateCheckResult.NoUpdate,
                    is UpdateCheckResult.SkippedRecent,
                    -> updateCheckMessage.value = "You're up to date."
                    is UpdateCheckResult.Failed -> {
                        updateCheckMessage.value = "Check did not complete. Try again later."
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                updateCheckMessage.value = "Check did not complete. Try again later."
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
            try {
                val result = hltbDatasetRepository.checkAndApply { progress ->
                    hltbDatasetProgress.value = progress
                }
                when (result) {
                    is HltbDatasetCheckResult.Applied ->
                        hltbDatasetCheckMessage.value =
                            "Updated — ${result.gamesGainingLengths} games gained completion times."
                    is HltbDatasetCheckResult.UpToDate ->
                        hltbDatasetCheckMessage.value = "Already up to date."
                    is HltbDatasetCheckResult.Failed ->
                        hltbDatasetCheckMessage.value = "Check did not complete. Try again later."
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                hltbDatasetCheckMessage.value = "Check did not complete. Try again later."
            } finally {
                hltbDatasetCheckInProgress.value = false
                hltbDatasetProgress.value = null
            }
        }
    }

    private fun HltbDatasetProgress.describe(): String = when (this) {
        HltbDatasetProgress.Checking -> "Checking for a newer dataset…"
        is HltbDatasetProgress.Downloading -> "Downloading…"
        HltbDatasetProgress.Verifying -> "Verifying…"
        HltbDatasetProgress.Applying -> "Applying…"
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
            try {
                when (val prepared = hltbContributionExporter.prepare()) {
                    HltbContributionPreparation.NothingToContribute -> {
                        hltbContributionMessage.value = "No resolved games to contribute yet."
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
                hltbContributionMessage.value = "Couldn't prepare the contribution. Try again."
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
                hltbContributionMessage.value = "Saved ${prepared.mappingCount} games to contribute."
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                hltbContributionMessage.value = "Couldn't save the contribution file."
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
                    totalXpBefore = before?.totalXp ?: 0,
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
        backupMessage.value = "Backup exported."
    }

    /** A file was picked via SAF's OpenDocument — validate, then import or warn on mismatch. */
    fun onImportBackupPicked(source: Uri) = runBackupOp {
        when (val parsed = backupRepository.parseFrom(source)) {
            ParsedBackup.InvalidFormat ->
                backupMessage.value = "That file isn't a valid Backlogium backup."
            is ParsedBackup.Invalid -> backupMessage.value = parsed.problems.describeRejection()
            is ParsedBackup.TooLarge -> backupMessage.value = parsed.describeTooLarge()
            is ParsedBackup.Valid -> proceedOrWarn(parsed.file)
        }
    }

    /** Restore a listed automatic snapshot through the same merge path as a manual import. */
    fun onRestoreSnapshot(snapshot: SnapshotMeta) = runBackupOp {
        when (val parsed = backupRepository.parseSnapshot(snapshot.fileName)) {
            ParsedBackup.InvalidFormat -> backupMessage.value = "That snapshot could not be read."
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
            backupMessage.value = "Backup imported."
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
            backupMessage.value = "Backup imported."
            refreshSnapshots()
            _hapticIntents.tryEmit(HapticIntent.Confirm)
        }
    }

    /** Delete one retained automatic snapshot after the user confirms the action in the UI. */
    fun onDeleteSnapshot(snapshot: SnapshotMeta) = runBackupOp {
        if (backupRepository.deleteSnapshot(snapshot.fileName)) {
            backupMessage.value = "Snapshot deleted."
            _hapticIntents.tryEmit(HapticIntent.Confirm)
        } else {
            backupMessage.value = "That snapshot is no longer available."
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
                    _toastMessages.tryEmit("Family Shared game restored.")
                    _hapticIntents.tryEmit(HapticIntent.Confirm)
                } else {
                    _toastMessages.tryEmit("Family Shared game could not be restored.")
                    _hapticIntents.tryEmit(HapticIntent.Reject)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _toastMessages.tryEmit("Family Shared game could not be restored.")
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
                backupMessage.value = "Backup operation failed: ${e.message}"
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
        val message: String?,
        val pendingMismatch: BackupFile?,
        val snapshots: List<SnapshotMeta>,
    )

    private data class Local(val rule: RuleLocal, val backup: BackupLocal)

    private data class HltbDatasetLocal(
        val gatheredAt: Long?,
        val coveredCount: Int,
        val checking: Boolean,
        val checkMessage: String?,
    )

    private data class HltbContributionLocal(
        val disclosurePending: Boolean,
        val busy: Boolean,
        val message: String?,
    )

    private data class HltbLocal(val dataset: HltbDatasetLocal, val contribution: HltbContributionLocal)
}
    private data class AssetStoredState(
        val summary: SteamAssetStoredSummary,
        val lastRun: SteamAssetDownloadState?,
        val hasInventory: Boolean,
    )


/** Names what failed and where, rather than reporting only that the import failed (tasks.md 2.5). */
private fun List<BackupValidationProblem>.describeRejection(): String {
    val summary = joinToString("; ") { "${it.recordType}[${it.index}]: ${it.detail}" }
    return "Backup rejected — $summary"
}

private fun ParsedBackup.TooLarge.describeTooLarge(): String {
    fun Long.toMb() = this / (1024 * 1024)
    return "Backup too large: ${actualBytes.toMb()} MB exceeds the ${limitBytes.toMb()} MB limit."
}

enum class ManualImportFeedbackTone { SUCCESS, INFO, ERROR }

data class ManualImportFeedback(
    val tone: ManualImportFeedbackTone,
    val title: String,
    val message: String,
)

internal fun manualImportFeedback(result: ManualSharedGameImportResult): ManualImportFeedback = when (result) {
    ManualSharedGameImportResult.InvalidInput -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        "Check the link",
        "Enter a numeric app ID or Steam Store URL.",
    )
    is ManualSharedGameImportResult.Owned -> ManualImportFeedback(
        ManualImportFeedbackTone.INFO,
        "Already in your library",
        "${result.name} is in your owned Steam library; no Family Shared import was made.",
    )
    is ManualSharedGameImportResult.Excluded -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        "Game is removed",
        "App ${result.appId} is in Removed shared games. Restore it first.",
    )
    is ManualSharedGameImportResult.NotAGame -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        "Game not found",
        "Steam Store does not identify app ${result.appId} as a game.",
    )
    is ManualSharedGameImportResult.Unavailable -> ManualImportFeedback(
        ManualImportFeedbackTone.ERROR,
        "Couldn't check Steam",
        when (result.at) {
            ManualImportUnavailableAt.OWNED_LIBRARY -> "Steam ownership check is unavailable. Try again."
            ManualImportUnavailableAt.STORE -> "Steam Store verification is unavailable. Try again."
        },
    )
    is ManualSharedGameImportResult.Imported -> {
        val probe = when (val data = result.playerData) {
            is PlayerDataProbe.Returned -> if (data.total == 0) {
                "Steam returned player data: this game has no achievements."
            } else {
                "Steam returned ${data.total} achievements; ${data.unlocked} unlocked."
            }
            PlayerDataProbe.NoData -> "Steam returned no usable player achievement data."
            PlayerDataProbe.Unavailable -> "The achievement check is temporarily unavailable."
        }
        val tracking = if (result.alreadyTracked) {
            "${result.name} is already tracked as Family Shared."
        } else {
            "${result.name} is now tracked as Family Shared."
        }
        ManualImportFeedback(
            ManualImportFeedbackTone.SUCCESS,
            if (result.alreadyTracked) "Game already found" else "Game found and imported",
            "$tracking $probe Borrowed playtime is observed by Backlogium, not supplied by Steam.",
        )
    }
}

internal fun manualImportMessage(result: ManualSharedGameImportResult): String =
    manualImportFeedback(result).message

internal fun manualImportToast(feedback: ManualImportFeedback): String = when {
    feedback.tone == ManualImportFeedbackTone.SUCCESS && feedback.title == "Game already found" ->
        "Family Shared game is already tracked."
    feedback.tone == ManualImportFeedbackTone.SUCCESS -> "Family Shared game added."
    else -> "Family Shared game was not added."
}
