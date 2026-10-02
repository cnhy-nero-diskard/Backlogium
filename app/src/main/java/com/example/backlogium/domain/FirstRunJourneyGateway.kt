package com.example.backlogium.domain

import com.example.backlogium.data.history.HistoryImportRequestRecord
import kotlinx.coroutines.flow.Flow

/**
 * The app-scope first-run journey seam used by onboarding, the Home takeover, and startup recovery.
 *
 * Bounded to what those surfaces need: the durable phase, the shared history-import state, the UI
 * facts (baseline readiness / one-time receipt / pending recompute), and the phase transitions.
 * Implemented by [FirstRunJourneyCoordinator] and bound in AppModule so ViewModels stay
 * JVM-testable.
 */
interface FirstRunJourneyGateway {

    /** The active account's durable journey, or null when no journey is recorded for it. */
    val journey: Flow<FirstRunJourney?>

    /**
     * Null until the active account's journey has been resolved; then true exactly when it owes
     * the first-run flow. Home keeps a neutral loader up while this is null so neither Home
     * content nor the onboarding takeover can flash.
     */
    val firstRunOwed: Flow<Boolean?>

    /** The shared attributable import state (fence by active account when projecting). */
    val historyImportState: Flow<HistoryImportState>

    /** The durable recorded explicit import consent, or null. */
    val historyImportPendingRequest: Flow<HistoryImportRequestRecord?>

    /** One-shot read of the recorded explicit consent, or null. */
    suspend fun currentHistoryImportPendingRequest(): HistoryImportRequestRecord?

    /** The currently configured Steam account, or null. */
    suspend fun activeAccountSteamId(): String?

    /** A confirmed same-account library baseline exists (Import may be offered). */
    suspend fun baselineConfirmed(): Boolean

    /** The one-time import has committed for the active account ("Already imported"). */
    suspend fun importBackfilled(): Boolean

    /** A raw import committed but its recompute is unfinished for [activeAccount]. */
    suspend fun recomputePending(activeAccount: String? = null): Boolean

    /**
     * Await the one-way legacy-claim migration and return the active account's journey (null when
     * that account does not owe one). Awaited by onboarding so its step is decided before any
     * credential surface renders.
     */
    suspend fun resolvedJourney(): FirstRunJourney?

    /** Claim the journey after verified first-time credential persistence (any phase -> SETUP). */
    suspend fun claim(steamId: String)

    /** Route completed/continued/declined setup into the history choice (SETUP -> HISTORY_CHOICE). */
    suspend fun setupDone(steamId: String): Boolean

    /**
     * Record durable consent and launch the shared import for [steamId]: the phase becomes
     * IMPORT_REQUESTED with an exact requestId, the consent is persisted, and the operation runs
     * on the application scope. Recovery replays the exact phase requestId if this is interrupted.
     */
    suspend fun requestImport(steamId: String): HistoryImportResult

    /** Retry/resume a previously admitted import without creating new consent; null when none. */
    suspend fun resumeImport(): HistoryImportResult?

    /** Startup replay of a recorded/phase consent that did not settle. */
    suspend fun recoverPendingImport()

    /** Skip/do later: record DEFERRED (an admitted import keeps running) and make Home available. */
    suspend fun skipHistory(steamId: String): Boolean

    /**
     * Complete the journey after an explicit continue. Completes only the exact recorded request
     * when the phase is IMPORT_REQUESTED, or marks an already-imported HISTORY_CHOICE complete.
     */
    suspend fun finishHistory(steamId: String, requestId: String? = null): Boolean

    /** Settings import reset: return an IMPORT_REQUESTED journey to an owed HISTORY_CHOICE. */
    suspend fun onImportReset(steamId: String)

    /**
     * Re-publish the [firstRunOwed] projection against the current account, e.g. after an account
     * edit from Settings. No-ops until the legacy migration has resolved so the neutral Home loader
     * never flashes; never opens a new poll.
     */
    suspend fun refreshAccountOwed()

    /** Startup recovery after account change and pending raw recompute (idempotent). */
    suspend fun startupRecovery()
}