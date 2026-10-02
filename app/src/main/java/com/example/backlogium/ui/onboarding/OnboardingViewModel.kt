package com.example.backlogium.ui.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.backlogium.data.backup.BackupExportGateway
import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.data.repo.AccountChangeGateway
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsSaveResult
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.OnboardingCredentialsGateway
import com.example.backlogium.data.repo.SteamIdResolution
import com.example.backlogium.domain.FirstRunJourney
import com.example.backlogium.domain.FirstRunJourneyGateway
import com.example.backlogium.domain.FirstRunPhase
import com.example.backlogium.domain.HistoryImportResult
import com.example.backlogium.domain.HistoryImportState
import com.example.backlogium.work.setup.FirstRunSetupGateway
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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

    /**
     * The optional one-time Steam-history import decision, reached from completed/continued/declined
     * setup. Past the credential flow entirely; not a credential step. Rendered by the parent
     * surface; the ViewModel owns the durable phase transitions behind it.
     */
    HISTORY_CHOICE(null),
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
    /**
     * True until the flow has resolved whether this open resumes an owed first-run journey. The
     * parent surface keeps a loading treatment up while this is true so an unresolved resume can
     * never flash the credential steps.
     */
    val loading: Boolean = true,
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
    private val journey: FirstRunJourneyGateway,
) : ViewModel() {

    /**
     * Legacy test seam: the pre-phase constructor shape used by JVM ViewModel tests. Production
     * Hilt always injects [FirstRunJourneyGateway]; this wraps the old takeover flag so the
     * credential-flow tests keep running against the same surface logic.
     */
    constructor(
        credentials: OnboardingCredentialsGateway,
        backupRepository: BackupExportGateway,
        accountChange: AccountChangeGateway,
        setup: FirstRunSetupGateway,
    ) : this(credentials, backupRepository, accountChange, LegacyFirstRunJourneyGateway(credentials, setup))

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _historyChoice = MutableStateFlow(HistoryChoiceUiState())
    val historyChoice: StateFlow<HistoryChoiceUiState> = _historyChoice.asStateFlow()

    private var pendingAccountChange: PendingAccountChange? = null

    /**
     * Whether this flow ends in setup / the history decision. Fixed from the credential state the
     * flow *opened* with, not the live one: saving credentials makes the account configured, and
     * re-reading it afterwards would conclude that every first run was an edit.
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
        // Keep the history-choice projection honest against the shared import state, fenced to the
        // active account (a stale-account Running/Completed must never render as this account's).
        viewModelScope.launch { journey.historyImportState.collect(::refreshHistoryChoice) }
        viewModelScope.launch { resolveResumePhase() }
    }

    /**
     * Decide the flow's opening step from the durable journey, not the credential store alone.
     * Awaiting [FirstRunJourneyGateway.resolvedJourney] (which runs the one-way legacy migration)
     * means a cold launch on an owed SETUP/HISTORY_CHOICE phase resumes exactly there — a
     * configured account mid-journey never re-sees credential entry.
     */
    private suspend fun resolveResumePhase() {
        val current = credentials.currentCredentials()
        val resolved = journey.resolvedJourney()
        val owed = resolved != null && resolved.owed
        presentsSetup = when (current) {
            is CredentialsState.Configured -> owed
            else -> true
        }
        _uiState.update { state ->
            val resumeStep = when (resolved?.phase) {
                FirstRunPhase.SETUP -> OnboardingStep.SETUP
                FirstRunPhase.HISTORY_CHOICE, FirstRunPhase.IMPORT_REQUESTED ->
                    OnboardingStep.HISTORY_CHOICE
                else -> null
            }
            state.copy(
                hasExistingKey = current != null,
                steamIdInput = current?.steamId.orEmpty(),
                step = resumeStep ?: state.step,
                loading = false,
            )
        }
        refreshHistoryChoice(journey.historyImportState.first())
    }

    private suspend fun refreshHistoryChoice(state: HistoryImportState) {
        val active = journey.activeAccountSteamId()
        val busy = state is HistoryImportState.Running && state.accountSteamId == active
        val result = (state as? HistoryImportState.Completed)
            ?.takeIf { it.accountSteamId == active }
            ?.result
        val pendingRecompute = result == HistoryImportResult.PendingRecompute ||
            (active != null && journey.recomputePending(active))
        val failure = (result as? HistoryImportResult.Failed)?.reason
        _historyChoice.update {
            it.copy(
                baselineReady = journey.baselineConfirmed(),
                imported = journey.importBackfilled(),
                busy = busy,
                recomputePending = pendingRecompute,
                failure = failure,
            )
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
            CredentialsSaveResult.Saved -> moveOnFromCredentials(steamId)

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
     * On a first configuration the journey is claimed durably for [steamId] *before* the setup
     * step is shown. From this point on the install is configured, so `configured == false` no
     * longer holds the onboarding surface up, and a process killed on the setup step would
     * otherwise cold-launch straight into an empty app with the setup it was midway through
     * silently dropped.
     */
    private suspend fun moveOnFromCredentials(steamId: String) {
        if (presentsSetup) {
            journey.claim(steamId)
            _uiState.update { it.copy(saving = false, step = OnboardingStep.SETUP) }
        } else {
            _uiState.update { it.copy(saving = false, completed = true) }
        }
    }

    /**
     * Route completed / continued / declined setup into the history choice. The journey phase is
     * advanced durably (SETUP -> HISTORY_CHOICE) before the step changes, so a cold launch finds
     * the history decision still owed; setup worker completion alone can never clear the journey.
     */
    fun onSetupDone() {
        viewModelScope.launch {
            val steamId = journey.activeAccountSteamId() ?: return@launch
            journey.setupDone(steamId)
            _uiState.update { it.copy(step = OnboardingStep.HISTORY_CHOICE) }
            refreshHistoryChoice(journey.historyImportState.first())
        }
    }

    // ----------------------------------------------------------------- first-run history decision

    /** Explicit Import: durable consent + app-scope launch. Mounting the surface never consents. */
    fun onHistoryImport() {
        if (_historyChoice.value.busy) return
        viewModelScope.launch {
            val steamId = journey.activeAccountSteamId() ?: return@launch
            handleHistoryImportResult(steamId, journey.requestImport(steamId))
        }
    }

    /** Resume a pending/unfinished import without new consent (fallback replays the exact request). */
    fun onHistoryRetry() {
        if (_historyChoice.value.busy) return
        viewModelScope.launch {
            val steamId = journey.activeAccountSteamId() ?: return@launch
            val result = journey.resumeImport() ?: journey.requestImport(steamId)
            handleHistoryImportResult(steamId, result)
        }
    }

    /** Skip / do later: record DEFERRED (an admitted import keeps running) and enter Home. */
    fun onHistorySkip() {
        viewModelScope.launch {
            val steamId = journey.activeAccountSteamId() ?: return@launch
            journey.skipHistory(steamId)
            completeFlow()
        }
    }

    /** Continue from an already-imported state: complete the journey for the exact request. */
    fun onHistoryContinue() {
        viewModelScope.launch {
            val steamId = journey.activeAccountSteamId() ?: return@launch
            journey.finishHistory(steamId)
            completeFlow()
        }
    }

    /** Native Back affordance to review setup; never dismisses the journey. */
    fun onReviewSetup() {
        _uiState.update { it.copy(step = OnboardingStep.SETUP) }
    }

    private suspend fun handleHistoryImportResult(steamId: String, result: HistoryImportResult) {
        when (result) {
            is HistoryImportResult.Imported, HistoryImportResult.AlreadyImported -> {
                // Complete the exact request durably before reporting done, so a cold launch after
                // this cannot reopen the journey.
                journey.finishHistory(steamId)
                completeFlow()
            }
            HistoryImportResult.PendingRecompute,
            HistoryImportResult.NeedsBaseline,
            HistoryImportResult.Superseded,
            is HistoryImportResult.Failed -> refreshHistoryChoice(journey.historyImportState.first())
        }
    }

    private fun completeFlow() {
        _uiState.update { it.copy(completed = true) }
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
                moveOnFromCredentials(pending.steamId)
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

/**
 * Test-only [FirstRunJourneyGateway] backing the legacy 4-argument constructor. Production Hilt
 * injects [FirstRunJourneyCoordinator]; this adapter maps the old durable takeover flag so the
 * credential-flow tests keep exercising the same ViewModel logic. Import/baseline facts report
 * conservative defaults because the credential tests never drive the history surface.
 */
private class LegacyFirstRunJourneyGateway(
    private val credentials: CredentialsProvider,
    private val setup: FirstRunSetupGateway,
) : FirstRunJourneyGateway {
    override val journey: Flow<FirstRunJourney?> = flowOf(null)
    override val firstRunOwed: Flow<Boolean?> = setup.firstRunSetupActive.map { it as Boolean? }
    override val historyImportState: Flow<HistoryImportState> = flowOf(HistoryImportState.Idle)
    override val historyImportPendingRequest: Flow<HistoryImportRequestRecord?> = flowOf(null)

    override suspend fun currentHistoryImportPendingRequest(): HistoryImportRequestRecord? = null

    override suspend fun activeAccountSteamId(): String? =
        credentials.currentCredentials()?.steamId?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun baselineConfirmed(): Boolean = false

    override suspend fun importBackfilled(): Boolean = false

    override suspend fun recomputePending(activeAccount: String?): Boolean = false

    override suspend fun resolvedJourney(): FirstRunJourney? {
        val active = activeAccountSteamId()
        return if (active != null && setup.firstRunSetupActive.first()) {
            FirstRunJourney(active, FirstRunPhase.SETUP)
        } else {
            null
        }
    }

    override suspend fun claim(steamId: String) = setup.claimFirstRunSetup()

    override suspend fun setupDone(steamId: String): Boolean {
        setup.releaseFirstRunSetup()
        return true
    }

    override suspend fun requestImport(steamId: String): HistoryImportResult =
        HistoryImportResult.Superseded

    override suspend fun resumeImport(): HistoryImportResult? = null

    override suspend fun recoverPendingImport() = Unit

    override suspend fun skipHistory(steamId: String): Boolean = true

    override suspend fun finishHistory(steamId: String, requestId: String?): Boolean = true

    override suspend fun onImportReset(steamId: String) = Unit

    override suspend fun refreshAccountOwed() = Unit

    override suspend fun startupRecovery() = Unit
}
