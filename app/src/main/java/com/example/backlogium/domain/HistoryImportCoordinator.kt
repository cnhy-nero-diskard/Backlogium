package com.example.backlogium.domain

import com.example.backlogium.data.history.DataStoreHistoryImportRequestStore
import com.example.backlogium.data.history.HistoryImportRequestRecord
import com.example.backlogium.di.ApplicationScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared coordinator for the explicit Steam-history import and its reset (stabilize-first-run-setup,
 * tasks 5.1/5.3/5.5/5.7). Onboarding and Settings both call this single seam; the parent milestone's
 * first-run-phase store observes [state] and drives its own phase transitions around it, submitting
 * its own opaque [HistoryImportCoordinator.start] request id before the operation launches.
 *
 * Ownership rules:
 * - **Typed, kind-carrying admission**: an in-flight [ImportAdmission] coalesces only another
 *   import for the **same account** (start/resume/recoverPending join it and return its
 *   [HistoryImportResult]); an import for a *different* account reports [HistoryImportResult.Superseded]
 *   instead of being attributed the running account's completion. A reset never joins — it waits for
 *   the active admission to finish, then runs its own [PlaytimeBackfillResetResult] admission. No
 *   cross-kind value is ever awaited or cast.
 * - **Consent before Running**: [HistoryImportState.Running] is emitted only *after* the explicit
 *   request is durably recorded; a kill between consent and the parent's phase transition is visible
 *   through [pendingRequest] and replayed by [recoverPending] with the exact stored request id.
 * - **Admission runs on the application scope**: the operation lifetime is never a screen's; a
 *   caller that cancels stops awaiting but the admitted import continues and settles [state].
 * - **Owned busy cleanup**: releasing the admission slot and clearing [inFlight] happen **under
 *   the same gate critical section**, so the finishing job can never clear a newer admission's busy
 *   flag. A thrown import failure is surfaced as an attributable [HistoryImportResult.Failed] (state
 *   + return), never as a naked exception, and the recorded consent is retained for retry.
 * - **Pending requests are retained** until fully settled or superseded; only
 *   [HistoryImportResult.Imported] / [HistoryImportResult.AlreadyImported] / [HistoryImportResult.Superseded]
 *   consume it.
 */
@Singleton
class HistoryImportCoordinator @Inject constructor(
    private val requestStore: DataStoreHistoryImportRequestStore,
    private val backfill: PlaytimeBackfillUseCase,
    private val account: HistoryImportAccountGateway,
    private val time: TimeProvider,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val gate = Mutex()
    private val _state = MutableStateFlow<HistoryImportState>(HistoryImportState.Idle)
    private val _inFlight = MutableStateFlow(false)

    /** The shared attributable import state onboarding and Settings render. */
    val state: StateFlow<HistoryImportState> = _state.asStateFlow()

    /** True while an explicit import admission is running (kept for busy-flag parity). */
    val inFlight: StateFlow<Boolean> = _inFlight.asStateFlow()

    /**
     * The durable recorded consent, observable for kill-between-consent-and-phase recovery — the
     * parent reconciles its phase store against this before/around [recoverPending].
     */
    val pendingRequest: Flow<HistoryImportRequestRecord?> = requestStore.requestFlow

    /** One-shot read of the currently recorded explicit request, or null. */
    suspend fun currentPendingRequest(): HistoryImportRequestRecord? = requestStore.request()

    /** The single in-flight admission, typed by kind; guarded by [gate]. */
    private var activeAdmission: ActiveAdmission? = null

    /**
     * Submit an explicit history-import consent for the active account and run it to settlement.
     *
     * @param expectedSteamId when provided, the account the consent is recorded for (the parent
     *   records its IMPORT_REQUESTED phase against this exact account); otherwise the current
     *   active account. The use case revalidates the account at the commit boundary, so a stale
     *   expectation is reported [HistoryImportResult.Superseded], never imported for another account.
     * @param requestId when provided, the opaque consent identity the parent generated for its phase
     *   record (startup replay re-submits the exact same id); otherwise the coordinator generates one.
     */
    suspend fun start(
        expectedSteamId: String? = null,
        requestId: String? = null,
    ): HistoryImportResult {
        val queuedAccount = activeAccount(expectedSteamId)
        return runImportAdmission(queuedAccount) { settler ->
            mutex.withLock {
                val active = queuedAccount ?: return@withLock noConsentNeedsBaseline()
                // A request identity an explicit reset invalidated must never silently re-import a
                // reset library and must never record a new consent for that id — a later
                // deliberately generated UUID is always allowed.
                if (requestId != null && requestStore.isRequestVoided(active, requestId)) {
                    _state.value = HistoryImportState.Completed(
                        result = HistoryImportResult.Superseded,
                        requestId = requestId,
                        accountSteamId = active,
                    )
                    return@withLock HistoryImportResult.Superseded
                }
                val request = requestStore.recordExplicitRequest(
                    steamId = active,
                    requestedAt = time.nowMillis(),
                    requestId = requestId ?: UUID.randomUUID().toString(),
                )
                settler.settleConsent(request)
            }
        } ?: HistoryImportResult.NeedsBaseline
    }

    /**
     * Retry/resume a previously attempted import that did not settle, without creating new consent.
     * Resumes a recorded request (retained on PendingRecompute/Failure/NeedsBaseline) or a
     * raw-committed import whose administrative recomputation is pending (the Room marker).
     * Returns null when there is nothing pending for the active account.
     */
    suspend fun resume(): HistoryImportResult? {
        val queuedAccount = requestStore.request()?.steamId
            ?: account.activeSteamId()?.trim()?.takeIf { it.isNotEmpty() }
        return runImportAdmission(queuedAccount) { settler ->
            mutex.withLock {
                val stored = requestStore.request()
                if (stored != null) {
                    settler.settleConsent(stored)
                } else {
                    resumeFromRoomMarker()
                }
            }
        }
    }

    /**
     * Startup recovery: replay a recorded explicit request that died before launch or before its
     * raw commit, or resume a marker-held recomputation. A satisfied request is consumed exactly
     * once; the replayed consent keeps its exact stored request id and account. Joins an in-flight
     * same-account admission instead of racing it, and runs on the application scope.
     *
     * A durable RESET intent takes precedence over any import replay: it resumes the guarded reset
     * (never re-imports), voiding the exact request identity the reset was about to invalidate.
     */
    suspend fun recoverPending(): HistoryImportResult? {
        val intent = requestStore.resetIntent()
        if (intent != null) {
            runResetAdmission {
                mutex.withLock {
                    resetWithVoid(
                        expectedAccount = intent.steamId,
                        intentAccount = intent.steamId,
                        intentVoidedId = intent.voidedRequestId,
                    )
                }
            }
            return null
        }
        val queuedAccount = requestStore.request()?.steamId
        return runImportAdmission(queuedAccount) { settler ->
            mutex.withLock {
                val stored = requestStore.request()
                if (stored != null) {
                    settler.settleConsent(stored)
                } else {
                    resumeFromRoomMarker()
                }
            }
        }
    }

    /**
     * Undo a prior import so it can be offered again (recovery / opt-out). Not an import admission:
     * it never emits [HistoryImportState.Running] and records no consent. A reset waits for any
     * active admission to finish, then runs its own — it never joins or reuses another kind's
     * outcome. The applied cloud imported-play transfer guard remains enforced.
     *
     * **Durable invalidation**: before the raw commit the coordinator records a RESET intent naming
     * the last settled import request identity; once the raw commit lands, that identity is
     * durably voided so a first-run-phase replay re-submitting it is refused ([isRequestVoided])
     * and can never silently re-import a reset library. A blocked reset (cloud transfer / account
     * change) clears the intent and voids nothing.
     */
    suspend fun resetSteamHistoryImport(): PlaytimeBackfillResetResult {
        // The account the caller represented when the reset was requested — captured before the
        // admission wait so a queued old-account reset that survives an account change is refused
        // at the commit boundary instead of resetting the replacement account's imports.
        val queuedFor = account.activeSteamId()?.trim()?.takeIf { it.isNotEmpty() } ?: ""
        return runResetAdmission {
            mutex.withLock {
                resetWithVoid(expectedAccount = queuedFor, intentAccount = null, intentVoidedId = null)
            }
        }
    }

    /**
     * Drives one reset through the typed admission slot: waits for any active admission, then runs
     * its own [ActiveAdmission.ResetAdmission].
     */
    private suspend fun runResetAdmission(
        block: suspend () -> PlaytimeBackfillResetResult,
    ): PlaytimeBackfillResetResult {
        while (true) {
            val claim = gate.withLock {
                val active = activeAdmission
                if (active == null) {
                    val deferred = CompletableDeferred<PlaytimeBackfillResetResult>()
                    activeAdmission = ActiveAdmission.ResetAdmission(deferred)
                    ResetClaim(deferred)
                } else {
                    ResetBusy(active)
                }
            }
            when (claim) {
                is ResetBusy -> {
                    // Wait for the active import/reset to finish, discarding its typed outcome,
                    // then run this reset's own admission on the next iteration.
                    try {
                        claim.admission.deferred.await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // A failed import settles as Failed (never throws); a failed reset already
                        // propagated to its own caller. Either way, proceed to run this reset.
                    }
                }
                is ResetClaim -> return launchReset(claim.deferred, block)
            }
        }
    }

    /**
     * The guarded reset plus the durable void protocol. [intentAccount]/[intentVoidedId] carry a
     * recovered reset intent (startup replay); otherwise the voided identity is derived from the
     * durable last-settled import, and the reset is bound to [expectedAccount].
     */
    private suspend fun resetWithVoid(
        expectedAccount: String,
        intentAccount: String?,
        intentVoidedId: String?,
    ): PlaytimeBackfillResetResult {
        val activeConsent = requestStore.request()
        val settled = requestStore.lastSettledImport()
        // Void-candidate priority for a NEW reset: a recovered intent's exact id first, then the
        // persisted ACTIVE same-account consent (a retained PendingRecompute/NeedsBaseline/Failed
        // request a phase replay could still re-submit), then the last fully-settled same-account
        // identity. An active consent from a DIFFERENT account is never the void candidate.
        val recovered = intentVoidedId != null && intentAccount != null && intentAccount == expectedAccount
        val sameAccountActive = activeConsent != null && activeConsent.steamId == expectedAccount
        val sameAccountSettled = settled != null && settled.steamId == expectedAccount
        val voidAccount = when {
            recovered -> intentAccount
            sameAccountActive -> activeConsent.steamId
            sameAccountSettled -> settled.steamId
            else -> null
        }
        val voidId = when {
            recovered -> intentVoidedId
            sameAccountActive -> activeConsent.requestId
            sameAccountSettled -> settled.requestId
            else -> null
        }
        val intentWasPersisted = voidId != null && voidAccount != null
        if (intentWasPersisted) {
            // Durable RESET intent recorded BEFORE the raw commit — even when the flag is already
            // false (a retained request with no settled import): a kill after this (even before the
            // Room reset) leaves recoverPending able to resume the guarded reset and void the id.
            requestStore.recordResetIntent(voidAccount, voidId, time.nowMillis())
        }
        val outcome = backfill.reset(expectedSteamId = expectedAccount.takeIf { it.isNotEmpty() })
        val rawLanded = outcome == PlaytimeBackfillResetResult.RESET ||
            outcome == PlaytimeBackfillResetResult.RESET_PENDING_RECOMPUTE
        if (rawLanded) {
            // Once the reset's raw commit lands, the recorded consent is void (recovery must never
            // re-import) — independent of whether a voidable id exists to record.
            requestStore.clear()
            if (voidId != null && voidAccount != null) {
                // The exact request identity the reset invalidated is durably refused; only the
                // retained/settled candidate id is voided — a newer active import is never touched.
                requestStore.recordVoidedImport(voidAccount, voidId)
            }
            requestStore.clearResetIntent()
        } else if (intentWasPersisted && outcome == PlaytimeBackfillResetResult.NO_OP) {
            // A persisted same-account RESET intent (from a recovered intent or a retained consent
            // candidate) is the authorization to void its exact request id even when the replay had
            // nothing left to reset — the old consent can never re-import.
            requestStore.recordVoidedImport(voidAccount, voidId)
            if (activeConsent?.steamId == voidAccount && activeConsent.requestId == voidId) {
                requestStore.clear()
            }
            requestStore.clearResetIntent()
        } else {
            // Blocked (cloud transfer / account change) or nothing authorized to void: the intent is
            // cleared and unrelated consent is preserved.
            requestStore.clearResetIntent()
        }
        // A reset is not an import settlement; Idle keeps the projection truthful.
        _state.value = HistoryImportState.Idle
        return outcome
    }

    /** Whether [requestId] was invalidated by an explicit reset for [accountSteamId]. */
    suspend fun isRequestVoided(accountSteamId: String, requestId: String): Boolean =
        requestStore.isRequestVoided(accountSteamId, requestId)

    // ------------------------------------------------------------------ admission machinery

    private sealed interface ActiveAdmission {
        val deferred: CompletableDeferred<*>

        data class ImportAdmission(
            override val deferred: CompletableDeferred<HistoryImportResult?>,
            val accountSteamId: String,
        ) : ActiveAdmission

        data class ResetAdmission(
            override val deferred: CompletableDeferred<PlaytimeBackfillResetResult>,
        ) : ActiveAdmission
    }

    private sealed interface ImportTry {
        data class Owned(val deferred: CompletableDeferred<HistoryImportResult?>) : ImportTry
        data class Join(val admission: ActiveAdmission.ImportAdmission) : ImportTry
        // A different account's import is admitted: report Superseded instead of reusing it.
        data object SupersededQueue : ImportTry
        // Another kind (reset) or an account-less queue: wait for it, then retry our own admission.
        data class Busy(val admission: ActiveAdmission) : ImportTry
    }

    private data class ResetClaim(val deferred: CompletableDeferred<PlaytimeBackfillResetResult>)
    private data class ResetBusy(val admission: ActiveAdmission)

    /**
     * Admit one import-type operation. Same-account imports join the running admission; a queued
     * different account is reported Superseded without touching consent or state; a reset or an
     * account-less wait defers until it finishes, then runs this import's own admission.
     */
    private suspend fun runImportAdmission(
        queuedAccount: String?,
        block: suspend (Settler) -> HistoryImportResult?,
    ): HistoryImportResult? {
        while (true) {
            val tryee = gate.withLock { tryClaimImport(queuedAccount) }
            when (tryee) {
                is ImportTry.Join ->
                    return tryee.admission.deferred.await()
                ImportTry.SupersededQueue ->
                    return HistoryImportResult.Superseded
                is ImportTry.Busy -> {
                    try {
                        tryee.admission.deferred.await()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // An import settles as Failed, never throws; a failed reset propagated to
                        // its own caller. Either way, retry our own admission.
                    }
                }
                is ImportTry.Owned -> {
                    return launchImport(tryee.deferred) { settler -> block(settler) }
                }
            }
        }
    }

    /** Decide under [gate] what this import attempt should do, claiming the slot when idle. */
    private fun tryClaimImport(queuedAccount: String?): ImportTry {
        val active = activeAdmission
        return when (active) {
            null -> {
                val deferred = CompletableDeferred<HistoryImportResult?>()
                // Register the claim immediately, before the gate is released, so a concurrent
                // same-account caller joins this exact admission instead of racing it.
                activeAdmission = ActiveAdmission.ImportAdmission(
                    deferred = deferred,
                    accountSteamId = queuedAccount ?: "",
                )
                ImportTry.Owned(deferred)
            }
            is ActiveAdmission.ImportAdmission -> {
                if (queuedAccount != null && active.accountSteamId != queuedAccount) {
                    // The running import belongs to another account: joining would attribute the
                    // wrong account's completion to this request.
                    ImportTry.SupersededQueue
                } else if (queuedAccount == null) {
                    // No account to compare (recovery with nothing recorded): wait rather than
                    // assume this import's account.
                    ImportTry.Busy(active)
                } else {
                    ImportTry.Join(active)
                }
            }
            is ActiveAdmission.ResetAdmission -> ImportTry.Busy(active)
        }
    }

    /**
     * Run the owned import admission body on the application scope and await its outcome. The slot
     * was claimed under [gate]; the launched job owns state cleanup **in the same gate critical
     * section**, so a finishing job can never clear a newer admission's busy flag. A thrown import
     * failure is converted to an attributable [HistoryImportResult.Failed] (released busy, consent
     * retained) instead of propagating to the caller.
     */
    private suspend fun launchImport(
        deferred: CompletableDeferred<HistoryImportResult?>,
        block: suspend (Settler) -> HistoryImportResult?,
    ): HistoryImportResult? {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val settler = Settler()
            try {
                deferred.complete(block(settler))
            } catch (cancelled: CancellationException) {
                // The failure travels to the awaiting caller via the deferred; never rethrow into
                // the application scope (an unmanaged SupervisorJob would record it as an uncaught
                // coroutine exception and poison the next test's uncaught handler).
                deferred.completeExceptionally(cancelled)
            } catch (t: Throwable) {
                // Surface as an actionable Failed, attributed to the consent that launched the
                // admission, and release busy — never a naked exception.
                val failure = HistoryImportResult.Failed(t.message ?: "History import failed")
                settler.publishFailure(failure)
                deferred.complete(failure)
            } finally {
                // Gated, owned cleanup: the slot release and the busy clear are one critical section.
                gate.withLock {
                    if (deferredMatches(deferred)) {
                        activeAdmission = null
                        _inFlight.value = false
                    }
                }
            }
        }
        return deferred.await()
    }

    /** Run the owned reset admission on the application scope; failures propagate via the deferred. */
    private suspend fun launchReset(
        deferred: CompletableDeferred<PlaytimeBackfillResetResult>,
        block: suspend () -> PlaytimeBackfillResetResult,
    ): PlaytimeBackfillResetResult {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                deferred.complete(block())
            } catch (cancelled: CancellationException) {
                deferred.completeExceptionally(cancelled)
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            } finally {
                gate.withLock {
                    if (deferredMatches(deferred)) {
                        activeAdmission = null
                        _inFlight.value = false
                    }
                }
            }
        }
        return deferred.await()
    }

    private fun deferredMatches(candidate: CompletableDeferred<*>): Boolean =
        activeAdmission?.deferred === candidate

    /** The only object allowed to publish attributable import state — owned by one admission. */
    private inner class Settler {
        var lastPublishedRequest: HistoryImportRequestRecord? = null
            private set

        fun running(request: HistoryImportRequestRecord) {
            lastPublishedRequest = request
            _inFlight.value = true
            _state.value = HistoryImportState.Running(request.requestId, request.steamId)
        }

        fun publishFailure(failure: HistoryImportResult.Failed) {
            val request = lastPublishedRequest
            // A failure is attributed to the request that launched the admission when one was
            // published; otherwise announce an account-less Failed so the state is never stuck.
            _state.value = HistoryImportState.Completed(
                result = failure,
                requestId = request?.requestId ?: "",
                accountSteamId = request?.steamId ?: "",
            )
            _inFlight.value = false
        }

        suspend fun settleConsent(request: HistoryImportRequestRecord): HistoryImportResult {
            running(request)
            val result = backfill.importSteamHistory(request)
            // A fully settled import records its durable identity first (single slot) so an
            // explicit reset later can void the exact request id a phase replay might re-submit.
            if (result is HistoryImportResult.Imported ||
                result is HistoryImportResult.AlreadyImported
            ) {
                requestStore.recordSettledImport(request.steamId, request.requestId)
            }
            // Retained on every unsettled outcome: PendingRecompute (the parent needs the
            // account-matching consent for recovery), Failed (retryable), NeedsBaseline (waiting
            // on a poll). Only a fully settled import or a provably superseded account consumes it.
            if (result is HistoryImportResult.Imported ||
                result is HistoryImportResult.AlreadyImported ||
                result == HistoryImportResult.Superseded
            ) {
                requestStore.clear()
            }
            _state.value = HistoryImportState.Completed(result, request.requestId, request.steamId)
            _inFlight.value = false
            return result
        }
    }

    private suspend fun activeAccount(expectedSteamId: String?): String? =
        expectedSteamId?.trim()?.takeIf { it.isNotEmpty() }
            ?: account.activeSteamId()?.trim()?.takeIf { it.isNotEmpty() }

    /** No account -> no consent, an honest NeedsBaseline, with the projection returned to Idle. */
    private fun noConsentNeedsBaseline(): HistoryImportResult {
        _state.value = HistoryImportState.Idle
        return HistoryImportResult.NeedsBaseline
    }

    /**
     * Resume from the Room marker alone (no stored consent): the marker attributes the pending
     * recomputation to an account/request, so [HistoryImportResult.Completed] still carries the
     * active account (the marker's own `requestId` stays readable from the profile for the parent).
     */
    private suspend fun resumeFromRoomMarker(): HistoryImportResult? {
        val active = account.activeSteamId()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val result = backfill.resumePendingImport(active)
        if (result != null) {
            _state.value = HistoryImportState.Completed(
                result = result,
                requestId = "resume",
                accountSteamId = active,
            )
        } else if (_state.value is HistoryImportState.Running) {
            _state.value = HistoryImportState.Idle
        }
        return result
    }
}
