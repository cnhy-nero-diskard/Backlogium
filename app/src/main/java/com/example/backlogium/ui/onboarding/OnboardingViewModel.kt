package com.example.backlogium.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.backup.BackupExportGateway
import com.example.backlogium.data.repo.AccountChangeGateway
import com.example.backlogium.data.repo.CredentialsSaveResult
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.OnboardingCredentialsGateway
import com.example.backlogium.data.repo.SteamIdResolution
import com.example.backlogium.work.setup.FirstRunSetupGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The ordered steps of the onboarding flow.
 *
 * The first three are the credential flow: API key first (so vanity resolution has a key), then
 * SteamID, then verifying the pair against Steam. [SETUP] is past the credential flow entirely —
 * credentials are already persisted by the time it is shown — so it carries no credential step
 * number, and the header's step count is derived from [credentialStepCount] rather than hardcoded.
 */
enum class OnboardingStep(val credentialStepNumber: Int?) {
    API_KEY(1),
    STEAM_ID(2),

    /**
     * Verification. Rendered on the SteamID surface rather than a screen of its own: it reuses that
     * step's existing inline pending treatment instead of adding a second progress mechanism.
     */
    VERIFY(3),

    /** The staged setup checklist. Presented only on a first configuration. */
    SETUP(null),
    ;

    companion object {
        /** How many steps the credential flow actually has, for `"Step N of M"`. */
        val credentialStepCount: Int = entries.count { it.credentialStepNumber != null }
    }
}

/** SteamID entry path chosen by the user in Step 2. */
enum class SteamIdEntryMode { RAW_ID, PROFILE_URL }

/** Step-2 resolution state, driving the inline messaging. */
sealed interface ResolveState {
    data object Idle : ResolveState
    data object Resolving : ResolveState
    data class Resolved(val steamId64: String) : ResolveState
    data class Error(val message: String) : ResolveState
}

/**
 * Verification state, shown inline on the step whose value it implicates.
 *
 * A network failure is deliberately *not* an [ResolveState.Error]-shaped validation message: it is
 * [Unreachable], which offers a retry and leaves both entered values in place, because telling
 * someone their correct key is wrong because their train went into a tunnel is the worst answer the
 * flow could give.
 */
sealed interface VerifyState {
    data object Idle : VerifyState
    data object Verifying : VerifyState

    /**
     * Steam objected to one of the two entered values. [step] is the one it objected to, and only
     * that step renders this message: "Steam did not accept this API key" shown under the SteamID
     * field points at the wrong value, which is the opposite of what verifying both at once buys.
     */
    data class Rejected(val step: OnboardingStep, val message: String) : VerifyState

    data object Unreachable : VerifyState
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.API_KEY,
    val apiKey: String = "",
    /** True when editing an already-configured account: the key may be left blank to keep it. */
    val hasExistingKey: Boolean = false,
    val steamIdInput: String = "",
    val entryMode: SteamIdEntryMode = SteamIdEntryMode.RAW_ID,
    val resolve: ResolveState = ResolveState.Idle,
    val verify: VerifyState = VerifyState.Idle,
    val saving: Boolean = false,
    /** A changed SteamID is held here until the user confirms or declines its data consequence. */
    val identityChange: IdentityChangeUiState? = null,
    /** Set once credentials are persisted; the host navigates away / dismisses the takeover. */
    val completed: Boolean = false,
) {
    /** Step 1 can advance when a key is entered, or one already exists (edit, keep current). */
    val canAdvanceFromApiKey: Boolean get() = apiKey.isNotBlank() || hasExistingKey

    val isResolved: Boolean get() = resolve is ResolveState.Resolved

    /** Verification and the final save share one pending treatment; neither is cancellable. */
    val busy: Boolean get() = saving || verify is VerifyState.Verifying
}

data class IdentityChangeUiState(
    val storedSteamId: String,
    val incomingSteamId: String,
    val exporting: Boolean = false,
    val exportMessage: String? = null,
)

/**
 * Whether [requestGeneration] is still the live credential request. A monotonic generation — not
 * the displayed values — is what identifies a request: an abandoned resolution for A must stay
 * discarded after the screen returns to A for its replacement, where a value comparison would
 * match again (the ABA hole). Every edit and every new request advances [OnboardingViewModel]'s
 * generation, so a superseded request can never regain ownership.
 */
internal fun isCurrentCredentialRequest(currentGeneration: Long, requestGeneration: Long): Boolean =
    currentGeneration == requestGeneration

/**
 * Bridges the onboarding flow to [OnboardingCredentialsGateway]. Holds the typed API key in memory
 * only (never logged; masked wherever displayed) and drives SteamID resolution + final save. On
 * open it pre-reflects an existing configuration (prefilled SteamID, "key already set") so the
 * same flow serves both first-run and edit.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val credentials: OnboardingCredentialsGateway,
    private val backupRepository: BackupExportGateway,
    private val accountChange: AccountChangeGateway,
    private val setup: FirstRunSetupGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var pendingAccountChange: PendingAccountChange? = null

    /**
     * Whether this flow ends in setup. Fixed from the credential state the flow *opened* with, not
     * the live one: saving credentials makes the account configured, and re-reading it afterwards
     * would conclude that every first run was an edit.
     *
     * An already-configured user reopening the flow from Settings to change credentials gets the
     * credential steps and nothing more — their new credentials are verified, but setup is not
     * presented again unprompted, and Settings has its own entry for it.
     */
    private var presentsSetup = false

    private var resolutionJob: Job? = null
    private var verificationJob: Job? = null

    /**
     * Monotonic identity for credential requests. Advanced on every edit and every new request;
     * each request captures the value and may publish or persist only while it is still current.
     * Cancellation stops superseded work promptly, but this guard — never the cancellation — is
     * what the correctness rests on.
     */
    private var credentialGeneration = 0L

    init {
        viewModelScope.launch {
            val current = credentials.currentCredentials()
            if (current is CredentialsState.Configured) {
                // Configured *and* still owing setup means this flow is a first run resumed after
                // the process died on the setup step — not an edit. Sending it back to step 1 would
                // ask a user who has already verified their credentials to re-enter them.
                val resumingFirstRun = setup.firstRunSetupActive.first()
                presentsSetup = resumingFirstRun
                _uiState.update {
                    it.copy(
                        hasExistingKey = true,
                        steamIdInput = current.steamId,
                        step = if (resumingFirstRun) OnboardingStep.SETUP else it.step,
                    )
                }
            } else {
                presentsSetup = true
            }
        }
    }

    fun onApiKeyChange(value: String) {
        // The key feeds vanity resolution as well as verification, so editing it invalidates both.
        credentialGeneration++
        cancelCredentialRequests()
        _uiState.update { it.copy(apiKey = value, verify = VerifyState.Idle) }
    }

    fun advanceToSteamId() {
        if (!_uiState.value.canAdvanceFromApiKey) return
        _uiState.update { it.copy(step = OnboardingStep.STEAM_ID) }
    }

    fun backToApiKey() {
        credentialGeneration++
        cancelCredentialRequests()
        _uiState.update {
            it.copy(
                step = OnboardingStep.API_KEY,
                resolve = ResolveState.Idle,
                verify = VerifyState.Idle,
            )
        }
    }

    fun setEntryMode(mode: SteamIdEntryMode) {
        credentialGeneration++
        cancelCredentialRequests()
        _uiState.update {
            it.copy(entryMode = mode, resolve = ResolveState.Idle, verify = VerifyState.Idle)
        }
    }

    fun onSteamIdInputChange(value: String) {
        // Any edit invalidates a prior resolution so the user must re-resolve before saving, and
        // any prior verification with it.
        credentialGeneration++
        cancelCredentialRequests()
        _uiState.update {
            it.copy(steamIdInput = value, resolve = ResolveState.Idle, verify = VerifyState.Idle)
        }
    }

    /** Resolve the current SteamID input (local for raw/`profiles`, network for vanity). */
    fun resolveSteamId() {
        val state = _uiState.value
        if (state.steamIdInput.isBlank()) return
        // A new resolution supersedes any in-flight verification for the previous input as well.
        cancelCredentialRequests()
        val requestGeneration = ++credentialGeneration
        val submittedInput = state.steamIdInput
        val submittedApiKey = state.apiKey
        _uiState.update { it.copy(resolve = ResolveState.Resolving) }
        // Ownership is established before launch so the superseded job can never publish between
        // the cancel above and the assignment below.
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val result = credentials.resolveSteamId(
                input = submittedInput,
                apiKeyOverride = submittedApiKey.ifBlank { null },
            )
            if (!isCurrentCredentialRequest(credentialGeneration, requestGeneration)) return@launch
            // Request identity is checked at publish time: only the current input, job, or date may publish.
            // Keep this in sync across OnboardingViewModel, LibraryViewModel.changeMatch(), and HistoryScreen.
            _uiState.update {
                if (isCurrentCredentialRequest(credentialGeneration, requestGeneration)) {
                    it.copy(resolve = result.toResolveState())
                } else {
                    it
                }
            }
        }
        resolutionJob = job
        job.invokeOnCompletion {
            if (resolutionJob === job) resolutionJob = null
        }
        job.start()
    }
    /**
     * Verify the entered credentials against Steam and, only if that succeeds, persist them.
     *
     * Verification is the last credential step and a precondition of saving — this is the sole path
     * to [OnboardingCredentialsGateway.save] from the flow, so there is no state in which an unverified
     * credential is stored. That is also why verification is not one of setup's stages: a stage can
     * be declined, and this cannot be.
     */
    fun finish() {
        val state = _uiState.value
        val resolved = state.resolve as? ResolveState.Resolved ?: return
        if (state.busy || state.identityChange != null) return
        cancelCredentialRequests()
        val requestGeneration = ++credentialGeneration
        val submittedApiKey = state.apiKey
        _uiState.update {
            it.copy(step = OnboardingStep.VERIFY, verify = VerifyState.Verifying)
        }
        // Ownership is established before launch, as in resolveSteamId(): a verification
        // invalidated by an edit must be discarded permanently, even if the displayed values later
        // happen to match its own again.
        lateinit var job: Job
        job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val apiKey = submittedApiKey.ifBlank {
                // Editing with the key field left blank: keep the stored key.
                (credentials.currentCredentials())?.apiKey.orEmpty()
            }
            val decision = decideVerification(
                credentials.verify(apiKey = apiKey, steamId = resolved.steamId64),
            )
            if (!isCurrentCredentialRequest(credentialGeneration, requestGeneration)) return@launch
            _uiState.update { current ->
                if (isCurrentCredentialRequest(credentialGeneration, requestGeneration)) {
                    current.applying(decision)
                } else {
                    current
                }
            }
            // The sole call into persistence, behind the sole decision that admits it, and only
            // while this verification still owns the request.
            if (decision == VerificationDecision.Persist &&
                isCurrentCredentialRequest(credentialGeneration, requestGeneration)
            ) {
                persist(apiKey, resolved.steamId64)
            }
        }
        verificationJob = job
        job.invokeOnCompletion {
            if (verificationJob === job) verificationJob = null
        }
        job.start()
    }
    /** Try verification again after a network failure, with both entered values still in place. */
    fun retryVerification() = finish()

    private suspend fun persist(apiKey: String, steamId: String) {
        _uiState.update { it.copy(saving = true) }
        when (val result = credentials.save(apiKey = apiKey, steamId = steamId)) {
            CredentialsSaveResult.Saved -> moveOnFromCredentials()

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

    /**
     * Where the flow goes once credentials are stored: into setup on a first configuration, so a
     * newly configured install is populated rather than empty, and straight out on an edit.
     *
     * On a first configuration the takeover is claimed durably *before* the step is shown. From this
     * point on the install is configured, so `configured == false` no longer holds the onboarding
     * surface up, and a process killed on the setup step would otherwise cold-launch straight into
     * an empty app with the setup it was midway through silently dropped.
     */
    private suspend fun moveOnFromCredentials() {
        if (presentsSetup) {
            setup.claimFirstRunSetup()
            _uiState.update { it.copy(saving = false, step = OnboardingStep.SETUP) }
        } else {
            _uiState.update { it.copy(saving = false, completed = true) }
        }
    }

    /**
     * Leave the flow after setup has completed or been declined. Credentials stay verified and
     * stored either way — declining setup must never invalidate them.
     *
     * The durable claim is released before `completed` is reported, not alongside it: the host reads
     * both, and clearing them out of order would flash Home behind a takeover that is still up.
     */
    fun onSetupDone() {
        viewModelScope.launch {
            setup.releaseFirstRunSetup()
            _uiState.update { it.copy(completed = true) }
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
                _uiState.update { it.copy(identityChange = null) }
                moveOnFromCredentials()
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

    private fun cancelCredentialRequests() {
        resolutionJob?.cancel()
        resolutionJob = null
        verificationJob?.cancel()
        verificationJob = null
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
