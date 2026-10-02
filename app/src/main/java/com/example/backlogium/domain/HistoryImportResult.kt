package com.example.backlogium.domain

/**
 * The shared domain outcome of one explicit Steam-history import request, replacing the old
 * Boolean result so onboarding and Settings present one truthful state (stabilize-first-run-setup,
 * task 5.1).
 *
 * The raw-commit/pending-recompute distinction is why this is a sealed hierarchy rather than a
 * Boolean: history is counted only when the raw transaction committed, and an import whose derived
 * recomputation is unfinished is neither an untouched account nor a completed one.
 */
sealed interface HistoryImportResult {

    /**
     * The imported offsets and the one-time flag committed, and the administrative recomputation
     * finalized. [creditedGames] is the number of games that received a nonzero frozen offset;
     * [creditedMinutes] is the sum of frozen offsets in that snapshot.
     *
     * A confirmed empty library reports `(0, 0)` — a successful zero-change import, which must not
     * be confused with a failed initial sync.
     */
    data class Imported(
        val creditedGames: Int,
        val creditedMinutes: Long,
    ) : HistoryImportResult

    /** History was already imported for this account; the request is a deliberate no-op. */
    data object AlreadyImported : HistoryImportResult

    /**
     * The active account has no confirmed library baseline, so the one-time import must not
     * consume its flag. Nothing was written. The user is told library sync is needed first.
     */
    data object NeedsBaseline : HistoryImportResult

    /**
     * The raw import committed but its administrative recomputation did not finalize. The recovery
     * signal is durable; a retry resumes the recomputation and never recaptures offsets.
     */
    data object PendingRecompute : HistoryImportResult

    /** The requested operation failed before committing anything; it can be retried. */
    data class Failed(
        val reason: String,
    ) : HistoryImportResult

    /**
     * Consent was recorded for one account but the active account (or an in-flight account
     * change) no longer matches, so the request imported nothing for the replacement account.
     */
    data object Superseded : HistoryImportResult

    /** Whether this outcome means the one-time import is fully done for the active account. */
    val isFullyImported: Boolean
        get() = this is Imported || this is AlreadyImported
}