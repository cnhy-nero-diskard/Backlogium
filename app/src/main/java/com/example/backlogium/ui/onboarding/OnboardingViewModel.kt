package com.example.backlogium.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.backup.BackupRepository
import com.example.backlogium.data.repo.AccountChangeCoordinator
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsSaveResult
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.SteamIdResolution
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
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
    /** A changed SteamID is held here until the user confirms or declines its data consequence. */
    val identityChange: IdentityChangeUiState? = null,
    /** Set once credentials are persisted; the host navigates away / dismisses the takeover. */
    val completed: Boolean = false,
) {
    /** Step 1 can advance when a key is entered, or one already exists (edit, keep current). */
    val canAdvanceFromApiKey: Boolean get() = apiKey.isNotBlank() || hasExistingKey

    val isResolved: Boolean get() = resolve is ResolveState.Resolved
}

data class IdentityChangeUiState(
    val storedSteamId: String,
    val incomingSteamId: String,
    val exporting: Boolean = false,
    val exportMessage: String? = null,
)

/**
 * Bridges the onboarding flow to [CredentialsRepository]. Holds the typed API key in memory only
 * (never logged; masked wherever displayed) and drives SteamID resolution + final save. On open it
 * pre-reflects an existing configuration (prefilled SteamID, "key already set") so the same flow
 * serves both first-run and edit.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val credentials: CredentialsRepository,
    private val backupRepository: BackupRepository,
    private val accountChange: AccountChangeCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var pendingAccountChange: PendingAccountChange? = null

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

    /** Persist the entered/kept API key and the resolved SteamID, or request account confirmation. */
    fun finish() {
        val state = _uiState.value
        val resolved = state.resolve as? ResolveState.Resolved ?: return
        if (state.saving || state.identityChange != null) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            val apiKey = state.apiKey.ifBlank {
                // Editing with the key field left blank: keep the stored key.
                (credentials.currentCredentials())?.apiKey.orEmpty()
            }
            when (val result = credentials.save(apiKey = apiKey, steamId = resolved.steamId64)) {
                CredentialsSaveResult.Saved ->
                    _uiState.update { it.copy(saving = false, completed = true) }

                is CredentialsSaveResult.IdentityChanged -> {
                    pendingAccountChange = PendingAccountChange(apiKey, result.incomingSteamId)
                    _uiState.update {
                        it.copy(
                            saving = false,
                            identityChange = IdentityChangeUiState(
                                storedSteamId = result.storedSteamId,
                                incomingSteamId = result.incomingSteamId,
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Declining is a complete no-op: the repository has not written either credential. */
    fun declineIdentityChange() {
        pendingAccountChange = null
        _uiState.update { it.copy(identityChange = null) }
    }

    /** Export the pre-reset state using the same complete backup path exposed in Settings. */
    fun exportIdentityChange(uri: Uri) {
        val pending = pendingAccountChange ?: return
        if (_uiState.value.identityChange?.exporting == true) return
        _uiState.update {
            it.copy(
                identityChange = it.identityChange?.copy(exporting = true, exportMessage = null),
            )
        }
        viewModelScope.launch {
            try {
                backupRepository.exportTo(uri)
                _uiState.update {
                    it.copy(
                        identityChange = it.identityChange?.copy(
                            exporting = false,
                            exportMessage = "Backup exported. You can now switch accounts.",
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        identityChange = it.identityChange?.copy(
                            exporting = false,
                            exportMessage = "Backup export failed: ${error.message ?: "try again"}",
                        ),
                    )
                }
            }
        }
    }

    /** Apply the confirmed, resumable reset and promote the staged credentials. */
    fun confirmIdentityChange() {
        val pending = pendingAccountChange ?: return
        if (_uiState.value.saving) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                accountChange.apply(apiKey = pending.apiKey, steamId = pending.steamId)
                credentials.refresh()
                pendingAccountChange = null
                _uiState.update { it.copy(saving = false, identityChange = null, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        saving = false,
                        identityChange = it.identityChange?.copy(
                            exportMessage = "Account switch is incomplete: ${error.message ?: "try again"}",
                        ),
                    )
                }
            }
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

    private data class PendingAccountChange(
        val apiKey: String,
        val steamId: String,
    )
}
