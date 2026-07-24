package com.example.backlogium.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.SteamIdResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The two ordered onboarding steps: API key first (so vanity resolution has a key), then SteamID. */
enum class OnboardingStep { API_KEY, STEAM_ID }

/** SteamID entry path chosen by the user in Step 2. */
enum class SteamIdEntryMode { RAW_ID, PROFILE_URL }

/** Step-2 resolution state, driving the inline messaging. */
sealed interface ResolveState {
    data object Idle : ResolveState
    data object Resolving : ResolveState
    data class Resolved(val steamId64: String) : ResolveState
    data class Error(val message: String) : ResolveState
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.API_KEY,
    val apiKey: String = "",
    /** True when editing an already-configured account: the key may be left blank to keep it. */
    val hasExistingKey: Boolean = false,
    val steamIdInput: String = "",
    val entryMode: SteamIdEntryMode = SteamIdEntryMode.RAW_ID,
    val resolve: ResolveState = ResolveState.Idle,
    val saving: Boolean = false,
    /** Set once credentials are persisted; the host navigates away / dismisses the takeover. */
    val completed: Boolean = false,
) {
    /** Step 1 can advance when a key is entered, or one already exists (edit, keep current). */
    val canAdvanceFromApiKey: Boolean get() = apiKey.isNotBlank() || hasExistingKey

    val isResolved: Boolean get() = resolve is ResolveState.Resolved
}

/**
 * Bridges the onboarding flow to [CredentialsRepository]. Holds the typed API key in memory only
 * (never logged; masked wherever displayed) and drives SteamID resolution + final save. On open it
 * pre-reflects an existing configuration (prefilled SteamID, "key already set") so the same flow
 * serves both first-run and edit.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val credentials: CredentialsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val current = credentials.currentCredentials()
            if (current is CredentialsState.Configured) {
                _uiState.update {
                    it.copy(
                        hasExistingKey = true,
                        steamIdInput = current.steamId,
                    )
                }
            }
        }
    }

    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKey = value) }

    fun advanceToSteamId() {
        if (!_uiState.value.canAdvanceFromApiKey) return
        _uiState.update { it.copy(step = OnboardingStep.STEAM_ID) }
    }

    fun backToApiKey() = _uiState.update { it.copy(step = OnboardingStep.API_KEY) }

    fun setEntryMode(mode: SteamIdEntryMode) =
        _uiState.update { it.copy(entryMode = mode, resolve = ResolveState.Idle) }

    fun onSteamIdInputChange(value: String) =
        // Any edit invalidates a prior resolution so the user must re-resolve before saving.
        _uiState.update { it.copy(steamIdInput = value, resolve = ResolveState.Idle) }

    /** Resolve the current SteamID input (local for raw/`profiles`, network for vanity). */
    fun resolveSteamId() {
        val state = _uiState.value
        if (state.steamIdInput.isBlank()) return
        _uiState.update { it.copy(resolve = ResolveState.Resolving) }
        viewModelScope.launch {
            val result = credentials.resolveSteamId(
                input = state.steamIdInput,
                apiKeyOverride = state.apiKey.ifBlank { null },
            )
            _uiState.update { it.copy(resolve = result.toResolveState()) }
        }
    }

    /** Persist the entered/kept API key and the resolved SteamID. No-op until resolved. */
    fun finish() {
        val state = _uiState.value
        val resolved = state.resolve as? ResolveState.Resolved ?: return
        if (state.saving) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val apiKey = state.apiKey.ifBlank {
                // Editing with the key field left blank: keep the stored key.
                (credentials.currentCredentials())?.apiKey.orEmpty()
            }
            credentials.save(apiKey = apiKey, steamId = resolved.steamId64)
            _uiState.update { it.copy(saving = false, completed = true) }
        }
    }

    private fun SteamIdResolution.toResolveState(): ResolveState = when (this) {
        is SteamIdResolution.Resolved -> ResolveState.Resolved(steamId64)
        SteamIdResolution.NoMatch ->
            ResolveState.Error("No Steam profile found for that URL.")
        SteamIdResolution.InvalidInput ->
            ResolveState.Error("That isn't a valid SteamID64 or Steam profile URL.")
        SteamIdResolution.NetworkError ->
            ResolveState.Error("Couldn't reach Steam — check your connection and API key, then retry.")
    }
}
