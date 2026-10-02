package com.example.backlogium.domain

import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.FirstRunJourneyRepository
import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.di.ApplicationScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-scope owner of the first-run journey (stabilize-first-run-setup, tasks 6.1/6.2/6.4/7.2).
 *
 * The journey phase is the durable "does the active account still owe the first-run flow" truth;
 * the shared history-import coordinator is the only import authority. This coordinator keeps the
 * two reconciled:
 *
 * - Consent-before-phase: [HistoryImportState.Running] with a matching account repairs a
 *   HISTORY_CHOICE phase to IMPORT_REQUESTED with the exact consent requestId.
 * - Completion: an [HistoryImportResult.Imported]/[AlreadyImported] attributed to the exact
 *   request completes that request's phase. A DEFERRED or replacement-account journey is never
 *   advanced by a late or foreign completion.
 * - Startup recovery replays the exact phase requestId through the shared import coordinator
 *   (never a fresh UUID), for a request recorded before the coordinator store existed and for a
 *   DEFERRED phase whose admitted consent died mid-flight — [AlreadyImported] is a legitimate
 *   completion and never refreezes.
 *
 * The [firstRunOwed] projection is null until the first resolution completes, so Home keeps a
 * neutral loader instead of flashing Home content or the onboarding takeover (tasks 7.2).
 */
@Singleton
class FirstRunJourneyCoordinator @Inject constructor(
    private val journeyRepo: FirstRunJourneyRepository,
    private val historyImport: HistoryImportCoordinator,
    private val credentials: CredentialsProvider,
    private val setupStore: SetupStateStore,
    private val facts: LibraryBaselineGateway,
    @ApplicationScope private val appScope: CoroutineScope,
) : FirstRunJourneyGateway {

    override val journey: Flow<FirstRunJourney?> = journeyRepo.journey

    private val _firstRunOwed = MutableStateFlow<Boolean?>(null)
    override val firstRunOwed: StateFlow<Boolean?> = _firstRunOwed.asStateFlow()

    /**
     * True once the one-way legacy migration has run at least once (startup recovery or an
     * onboarding resume). [refreshAccountOwed] refuses to resolve the null "unknown" projection
     * before this, so a legacy claim that migration has not yet mapped cannot flash Home.
     */
    private var legacyMigrated = false

    override val historyImportState: Flow<HistoryImportState> = historyImport.state
    override val historyImportPendingRequest: Flow<HistoryImportRequestRecord?> =
        historyImport.pendingRequest
    override suspend fun currentHistoryImportPendingRequest(): HistoryImportRequestRecord? =
        historyImport.currentPendingRequest()

    /** Keep the phase reconciled with the shared import state for the full process lifetime. */
    init {
        appScope.launch {
            historyImport.state.collect(::reconcileImportState)
        }
    }

    override suspend fun activeAccountSteamId(): String? =
        credentials.currentCredentials()?.steamId?.trim()?.takeIf { it.isNotEmpty() }

    override suspend fun baselineConfirmed(): Boolean = facts.baselineConfirmed()

    override suspend fun importBackfilled(): Boolean {
        val active = activeAccountSteamId() ?: return false
        return facts.importBackfilled(active)
    }

    override suspend fun recomputePending(activeAccount: String?): Boolean {
        val active = activeAccount?.takeIf { it.isNotBlank() } ?: activeAccountSteamId() ?: return false
        return facts.recomputePending(active)
    }

    override suspend fun resolvedJourney(): FirstRunJourney? {
        journeyRepo.restoreLegacy(credentials, setupStore)
        legacyMigrated = true
        val active = activeAccountSteamId() ?: return null
        return journeyRepo.current()?.takeIf { it.accountSteamId == active }
    }

    override suspend fun claim(steamId: String) {
        journeyRepo.claim(steamId)
        publishOwed()
    }

    override suspend fun setupDone(steamId: String): Boolean {
        val changed = journeyRepo.transition(
            steamId,
            setOf(FirstRunPhase.SETUP),
            FirstRunPhase.HISTORY_CHOICE,
        )
        publishOwed()
        return changed
    }

    override suspend fun requestImport(steamId: String): HistoryImportResult {
        // The whole admission — phase write, consent persistence, and launch — is claimed on the
        // application scope, so a caller (ViewModel) cancellation after requesting cannot strand an
        // authorized request until the next cold launch. Only an account-matched journey whose
        // phase may request (or already is the exact IMPORT_REQUESTED) is admitted; anything else
        // returns Superseded and never records new consent.
        return appScope.async(start = CoroutineStart.UNDISPATCHED) {
            val current = journeyRepo.current()
            val reusedPhaseId = current?.importRequestId?.takeIf { it.isNotBlank() }
            val allowed = current != null &&
                current.accountSteamId == steamId &&
                current.phase in setOf(FirstRunPhase.HISTORY_CHOICE, FirstRunPhase.IMPORT_REQUESTED) &&
                // A durable reset may have voided a retained phase pointer: never re-submit it.
                (reusedPhaseId == null || !historyImport.isRequestVoided(steamId, reusedPhaseId))
            if (!allowed) return@async HistoryImportResult.Superseded
            val requestId = reusedPhaseId ?: UUID.randomUUID().toString()
            val changed = journeyRepo.transition(
                steamId,
                setOf(FirstRunPhase.HISTORY_CHOICE, FirstRunPhase.IMPORT_REQUESTED),
                FirstRunPhase.IMPORT_REQUESTED,
                requestId,
            )
            if (!changed) return@async HistoryImportResult.Superseded
            publishOwed()
            val result = historyImport.start(expectedSteamId = steamId, requestId = requestId)
            settleAttributableResult(steamId, requestId, result)
            result
        }.await()
    }

    override suspend fun resumeImport(): HistoryImportResult? = historyImport.resume()

    override suspend fun recoverPendingImport() { historyImport.recoverPending() }

    override suspend fun skipHistory(steamId: String): Boolean {
        val changed = journeyRepo.transition(
            steamId,
            setOf(FirstRunPhase.HISTORY_CHOICE, FirstRunPhase.IMPORT_REQUESTED),
            FirstRunPhase.DEFERRED,
        )
        publishOwed()
        return changed
    }

    override suspend fun finishHistory(steamId: String, requestId: String?): Boolean {
        val effective = requestId?.takeIf { it.isNotBlank() }
            ?: journeyRepo.current()?.importRequestId?.takeIf { it.isNotBlank() }
        val changed = if (effective != null) {
            journeyRepo.completeImport(steamId, effective)
        } else {
            journeyRepo.transition(steamId, setOf(FirstRunPhase.HISTORY_CHOICE), FirstRunPhase.COMPLETE)
        }
        publishOwed()
        return changed
    }

    override suspend fun onImportReset(steamId: String) {
        val changed = journeyRepo.transition(
            steamId,
            setOf(FirstRunPhase.IMPORT_REQUESTED),
            FirstRunPhase.HISTORY_CHOICE,
        )
        if (changed) publishOwed()
    }

    override suspend fun refreshAccountOwed() {
        // Re-fence the takeover for the current account after a credential change; never resolve
        // the null "unknown" projection before the legacy migration has run.
        if (legacyMigrated) publishOwed()
    }

    override suspend fun startupRecovery() {
        // One-way legacy migration first: an old true first-run claim maps to SETUP exactly once.
        journeyRepo.restoreLegacy(credentials, setupStore)
        legacyMigrated = true
        val active = activeAccountSteamId()
        val journey = journeyRepo.current()
        if (journey != null && active != null && journey.accountSteamId == active) {
            reconcilePhase(active, journey)
        }
        publishOwed()
    }

    // ------------------------------------------------------------------ phase reconciliation

    /**
     * Repair or settle an existing phase from durable truth only. Never invents an import for a
     * phase that did not authorize one; never leaves an authorized request stuck.
     */
    private suspend fun reconcilePhase(active: String, journey: FirstRunJourney) {
        val pending = historyImport.currentPendingRequest()
        val phaseRequestId = journey.importRequestId?.takeIf { it.isNotBlank() }
        val phaseVoided = phaseRequestId != null && historyImport.isRequestVoided(active, phaseRequestId)
        val pendingVoided = pending != null && historyImport.isRequestVoided(active, pending.requestId)
        when (journey.phase) {
            // SETUP/COMPLETE owe no consent-recovery of their own here.
            FirstRunPhase.SETUP, FirstRunPhase.COMPLETE -> Unit

            FirstRunPhase.HISTORY_CHOICE -> {
                // Consent-before-phase: a live (non-voided) store consent repairs the phase.
                if (pending != null && pending.steamId == active && !pendingVoided) {
                    journeyRepo.transition(
                        active,
                        setOf(FirstRunPhase.HISTORY_CHOICE),
                        FirstRunPhase.IMPORT_REQUESTED,
                        pending.requestId,
                    )
                    publishOwed()
                    val result = historyImport.recoverPending()
                    settleAttributableResult(active, pending.requestId, result)
                }
                // No consent -> never implicitly start from HISTORY_CHOICE.
            }

            FirstRunPhase.IMPORT_REQUESTED -> {
                val voided = phaseVoided ||
                    (pending != null && pending.steamId == active && pendingVoided)
                when {
                    voided -> {
                        // A durable reset invalidated this request: never replay it and never
                        // re-consent. Clear the pointer and restore the owed HISTORY_CHOICE
                        // decision without generating a new UUID.
                        phaseRequestId?.let {
                            journeyRepo.clearImportRequest(
                                active,
                                it,
                                setOf(FirstRunPhase.IMPORT_REQUESTED),
                            )
                        }
                        journeyRepo.transition(
                            active,
                            setOf(FirstRunPhase.IMPORT_REQUESTED),
                            FirstRunPhase.HISTORY_CHOICE,
                        )
                        publishOwed()
                    }

                    pending != null && pending.steamId == active &&
                        (phaseRequestId == null || pending.requestId == phaseRequestId) -> {
                        val result = historyImport.recoverPending()
                        settleAttributableResult(active, pending.requestId ?: phaseRequestId, result)
                    }

                    pending == null && phaseRequestId != null -> {
                        // Killed between the durable phase write and the coordinator's consent
                        // store: replay the EXACT phase requestId as consent and launch once.
                        // AlreadyImported (a settled receipt) is a legitimate completion for the
                        // exact request and never refreezes.
                        val result = historyImport.start(expectedSteamId = active, requestId = phaseRequestId)
                        settleAttributableResult(active, phaseRequestId, result)
                    }

                    else -> Unit // a mismatched-account request can never advance this phase
                }
            }

            FirstRunPhase.DEFERRED -> {
                // DEFERRED retains a requestId only because the import had been explicitly
                // requested, so a missing request store means the consent died between the phase
                // write and the coordinator store — recover the exact phase consent, never a new
                // one. The phase stays DEFERRED (completeImport is phase-guarded); a fully
                // successful settlement drops the pointer via [settleDeferredRequest], so a later
                // Settings reset can never replay it.
                if (phaseRequestId == null) return
                if (phaseVoided || pendingVoided) {
                    // A durable reset invalidated the admitted request: never replay it. Drop the
                    // pointer and stay DEFERRED (never owed, never reopened).
                    journeyRepo.settleDeferredRequest(active, phaseRequestId)
                    return
                }
                if (pending != null && pending.steamId == active && pending.requestId == phaseRequestId) {
                    val result = historyImport.recoverPending()
                    settleAttributableResult(active, phaseRequestId, result)
                } else if (pending == null) {
                    val result = historyImport.start(expectedSteamId = active, requestId = phaseRequestId)
                    settleAttributableResult(active, phaseRequestId, result)
                }
            }
        }
    }

    /**
     * Reconcile the phase synchronously from an attributable import outcome, so a path that awaits
     * the operation (startup recovery, [requestImport]) does not depend on the async state-flow
     * collector to complete the exact request — a fast Running->Completed admission would otherwise
     * be skipped. The app-scope collector remains for out-of-band completions (Settings/other
     * entry points). [FirstRunJourneyRepository.completeImport] keeps every guard: only an
     * account-matched IMPORT_REQUESTED phase with the exact request completes; DEFERRED and
     * replacement-account phases stay untouched.
     */
    private suspend fun settleAttributableResult(
        account: String,
        requestId: String?,
        result: HistoryImportResult?,
    ) {
        if (result is HistoryImportResult.Imported || result is HistoryImportResult.AlreadyImported) {
            val exact = requestId?.takeIf { it.isNotBlank() } ?: return
            // Same active-account fence as the flow observer: never settle an old-account journey
            // while credentials already point at a replacement account.
            if (activeAccountSteamId() != account) return
            val completed = journeyRepo.completeImport(account, exact)
            if (completed) {
                publishOwed()
            } else {
                // Not completed because the phase is DEFERRED (explicit exit): the admitted import
                // is fully successful, so drop the retained pointer — a later Settings reset can
                // never replay it. The phase stays DEFERRED, never owed.
                journeyRepo.settleDeferredRequest(account, exact)
            }
        }
    }

    /** Keep the phase consistent with the in-process attributable import state, fenced by account. */
    private suspend fun reconcileImportState(state: HistoryImportState) {
        when (state) {
            is HistoryImportState.Running -> {
                val active = activeAccountSteamId()
                if (active != null && state.accountSteamId == active) {
                    val changed = journeyRepo.transition(
                        active,
                        setOf(FirstRunPhase.HISTORY_CHOICE),
                        FirstRunPhase.IMPORT_REQUESTED,
                        state.requestId,
                    )
                    if (changed) publishOwed()
                }
            }

            is HistoryImportState.Completed -> {
                if (state.result is HistoryImportResult.Imported ||
                    state.result is HistoryImportResult.AlreadyImported
                ) {
                    // Fence by the ACTIVE account before the phase CAS: a completion arriving while
                    // credentials already point at a replacement account must not advance the old
                    // account's journey on its behalf.
                    val active = activeAccountSteamId()
                    if (active == state.accountSteamId) {
                        val completed = journeyRepo.completeImport(active, state.requestId)
                        if (completed) {
                            publishOwed()
                        } else {
                            // Phase is DEFERRED (explicit exit): drop the retained pointer so a
                            // later Settings reset can never replay the stale consent. Never owed.
                            journeyRepo.settleDeferredRequest(active, state.requestId)
                        }
                    }
                }
            }

            HistoryImportState.Idle -> Unit
        }
    }

    private suspend fun publishOwed() {
        val active = activeAccountSteamId()
        val journey = journeyRepo.current()
        _firstRunOwed.value = journey != null && journey.accountSteamId == active && journey.owed
    }
}
