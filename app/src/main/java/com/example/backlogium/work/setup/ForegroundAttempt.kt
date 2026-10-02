package com.example.backlogium.work.setup

/**
 * Why one stage's foreground observation ended. A settlement reason is never a work outcome:
 * settling only stops watching a stage in the foreground — the stage's real
 * [SetupOperationState] is untouched and keeps running where one exists.
 */
enum class ForegroundSettledReason {
    /** Admitted work is queued behind constraints: the wait settles, the work stays [SetupOperationState.Waiting]. */
    QUEUED,

    /** The operation is in retry backoff: the wait settles, the work stays [SetupOperationState.RetryScheduled]. */
    BACKOFF,

    /** A detached stage's admission association is durable and its own notification reports it. */
    DETACHED,

    /** Observed work reached a terminal outcome. */
    TERMINAL,

    /** Observation/admission cannot be recovered; offer an explicit new request without trapping. */
    RECOVERY_REQUIRED,

    /** The user chose to continue/do later: the wait ends, the admission intent does not. */
    CONTINUE,

    /** The bounded monotonic observation budget elapsed. Never a failure, never a cancellation. */
    BUDGET_EXPIRED,
}

/** Initial foreground observation budget, measured monotonically. Injectable in tests. */
const val DEFAULT_FOREGROUND_OBSERVATION_BUDGET_MS = 120_000L

/**
 * The immutable foreground part of one setup run.
 *
 * It owns the run's selection and its registered admission order; it does not own the lifetime of
 * any background operation. Each stage row projects its own latest [SetupOperationState]
 * independently, so settling here never relabels queued/running/retry-scheduled work as completed
 * or failed, and only this attempt's monotonic budget decides when the foreground stops watching a
 * running in-screen stage. Budget expiry settles the foreground; it never mutates an operation and
 * never cancels one.
 */
data class ForegroundAttempt(
    /** The run's selection. Immutable and complete: leaving the surface does not deselect stages. */
    val selected: Set<String>,
    /** The selected stages in registered order. Unknown ids are dropped at construction. */
    val admissionOrder: List<String>,
    /** Why the foreground observation ended, or null while it is still active. */
    val settledReason: ForegroundSettledReason? = null,
    /** Monotonic budget for observing a single running in-screen stage. Never mutates work. */
    val observationBudgetMs: Long = DEFAULT_FOREGROUND_OBSERVATION_BUDGET_MS,
) {
    val isSettled: Boolean get() = settledReason != null

    val isActive: Boolean get() = settledReason == null

    /** True once [elapsedMonotonicMs] of monotonic time has consumed the observation budget. */
    fun observationBudgetElapsed(elapsedMonotonicMs: Long): Boolean =
        elapsedMonotonicMs >= observationBudgetMs

    /**
     * Explicit Continue/do-later. Ends the foreground wait without touching the selection or the
     * remaining admission intent; stages not yet admitted stay selected and admitted in order.
     */
    fun continueLater(): ForegroundAttempt = settle(ForegroundSettledReason.CONTINUE)

    /** The stages still owed admission, in registered order, given what [admitted] so far. */
    fun remainingAdmissions(admitted: Set<String>): List<String> =
        admissionOrder.filter { it !in admitted }

    /** Settle once. The first reason wins; a later observation cannot overwrite how it ended. */
    fun settle(reason: ForegroundSettledReason): ForegroundAttempt =
        if (isSettled) this else copy(settledReason = reason)
}

/**
 * Build an attempt from the whole registered order, keeping only [selected] and dropping any
 * identifier the registry no longer knows, like every other stored setup value.
 */
fun foregroundAttempt(
    selected: Set<String>,
    registeredOrder: List<String>,
    observationBudgetMs: Long = DEFAULT_FOREGROUND_OBSERVATION_BUDGET_MS,
): ForegroundAttempt = ForegroundAttempt(
    selected = selected.intersect(registeredOrder.toSet()),
    admissionOrder = registeredOrder.filter { it in selected },
    observationBudgetMs = observationBudgetMs,
)

/**
 * The automatic settlement decision for one stage's foreground observation, derived purely from its
 * real operation state:
 *
 * - a detached pending stage settles [ForegroundSettledReason.DETACHED] only once its exact
 *   admission association is durable;
 * - queued/blocked work settles [ForegroundSettledReason.QUEUED] immediately, preserving its
 *   [SetupOperationState.Waiting];
 * - a scheduled retry settles [ForegroundSettledReason.BACKOFF], preserving
 *   [SetupOperationState.RetryScheduled];
 * - a terminal state settles [ForegroundSettledReason.TERMINAL];
 * - once the monotonic budget elapses, the foreground settles
 *   [ForegroundSettledReason.BUDGET_EXPIRED] — busy or slow work is never turned into a failure
 *   and nothing is cancelled;
 * - a running in-screen stage within budget stays observed: no settlement yet.
 *
 * [detached] is the stage's execution kind; [admissionDurable] proves its exact work association
 * was saved. [elapsedMonotonicMs] is the monotonic time the attempt
 * has been observing that stage. This never mutates [state] and never cancels anything.
 */
fun ForegroundAttempt.settledFor(
    state: SetupOperationState,
    detached: Boolean,
    elapsedMonotonicMs: Long,
    admissionDurable: Boolean = false,
): ForegroundAttempt {
    if (isSettled) return this
    return when {
        state.isTerminal -> settle(ForegroundSettledReason.TERMINAL)
        state is SetupOperationState.RecoveryRequired -> settle(ForegroundSettledReason.RECOVERY_REQUIRED)
        detached && admissionDurable && state.isPending ->
            settle(ForegroundSettledReason.DETACHED)
        state is SetupOperationState.Waiting -> settle(ForegroundSettledReason.QUEUED)
        state is SetupOperationState.RetryScheduled -> settle(ForegroundSettledReason.BACKOFF)
        observationBudgetElapsed(elapsedMonotonicMs) ->
            settle(ForegroundSettledReason.BUDGET_EXPIRED)
        else -> this
    }
}
