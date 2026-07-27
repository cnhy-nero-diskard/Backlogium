package com.example.backlogium.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.credentials.maskApiKey
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.ProfileRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.UpdateRuleConfigUseCase
import com.example.backlogium.gamification.QuestMode
import com.example.backlogium.gamification.RuleConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    credentials: CredentialsRepository,
    settings: SettingsRepository,
) : ViewModel() {

    // Null until the user touches something: the draft then tracks the edit rather than being
    // reseeded by every unrelated emission from the profile/credentials flows. Reset to null
    // after a save or a discard so it re-seeds from whatever was persisted.
    private val draftEdit = MutableStateFlow<RuleDraft?>(null)
    private val advancedExpanded = MutableStateFlow(false)
    private val previewing = MutableStateFlow(false)
    private val confirmation = MutableStateFlow<RuleChangeConfirmation?>(null)
    private val isImportingHistory = MutableStateFlow(false)

    private val storedState = combine(
        profileRepository.profile,
        credentials.credentialsStateFlow,
        settings.ruleConfig,
        profileRepository.syncInProgress,
    ) { profile, credState, config, syncing ->
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
        )
    }

    private val localState = combine(
        draftEdit,
        advancedExpanded,
        previewing,
        confirmation,
        isImportingHistory,
    ) { edit, expanded, isPreviewing, pending, importing ->
        Local(edit, expanded, isPreviewing, pending, importing)
    }

    val uiState: StateFlow<SettingsUiState> = combine(storedState, localState) { stored, local ->
        stored.copy(
            draft = local.draft ?: stored.draft,
            advancedExpanded = local.advancedExpanded,
            previewing = local.previewing,
            confirmation = local.confirmation,
            isImportingHistory = local.importing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    fun syncNow() = profileRepository.syncNow()

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

    private data class Local(
        val draft: RuleDraft?,
        val advancedExpanded: Boolean,
        val previewing: Boolean,
        val confirmation: RuleChangeConfirmation?,
        val importing: Boolean,
    )
}
