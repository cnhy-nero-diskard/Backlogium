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
 * Settlement is **per stage** (design Decision 1): each selected stage's foreground wait concludes
 * independently and is recorded in [stageSettlements], so an earlier stage settling queued/backoff/
 * terminal never stops a later in-screen running stage from being observed for its own bounded
 * budget. The whole attempt is only ever settled by an explicit [continueLater]; until then every
 * selected stage is owed its own observation, in registered order, and the 120-second budget is
 * measured per stage. Budget expiry settles the foreground; it never mutates an operation and
 * never cancels one.
 */
data class ForegroundAttempt(
    /** The run's selection. Immutable and complete: leaving the surface does not deselect stages. */
    val selected: Set<String>,
    /** The selected stages in registered order. Unknown ids are dropped at construction. */
    val admissionOrder: List<String>,
    /** Per-stage foreground settlement reasons, keyed by stage id (empty while being observed). */
    val stageSettlements: Map<String, ForegroundSettledReason> = emptyMap(),
    /** True once the user explicitly chose Continue/do-later. Ends *all* waiting, not admissions. */
    val explicitContinued: Boolean = false,
    /** Monotonic budget for observing a single running in-screen stage. Never mutates work. */
    val observationBudgetMs: Long = DEFAULT_FOREGROUND_OBSERVATION_BUDGET_MS,
) {
    /** The whole foreground is done when every selected stage has settled, or the user continued. */
    val isSettled: Boolean get() = explicitContinued || selected.all { it in stageSettlements }

    /** False once the user continued; otherwise waiting continues stage by stage. */
    val isActive: Boolean get() = !explicitContinued

    /** Why one stage's foreground wait ended, or null while it is still observed. */
    fun settlementOf(stageId: String): ForegroundSettledReason? = stageSettlements[stageId]

    /** True while this stage's own foreground wait is still owed (not settled, not continued). */
    fun stageObserved(stageId: String): Boolean = isActive && stageId !in stageSettlements

    /** Settle one stage. First settlement wins; a later observation cannot change how it ended. */
    fun settleStage(stageId: String, reason: ForegroundSettledReason): ForegroundAttempt =
        if (explicitContinued || stageId in stageSettlements) this
        else copy(stageSettlements = stageSettlements + (stageId to reason))

    /**
     * Explicit Continue/do-later. Ends every foreground wait without touching the selection or the
     * remaining admission intent; stages not yet admitted stay selected and admitted in order.
     */
    fun continueLater(): ForegroundAttempt = copy(explicitContinued = true)

    /** The stages still owed admission, in registered order, given what [admitted] so far. */
    fun remainingAdmissions(admitted: Set<String>): List<String> =
        admissionOrder.filter { it !in admitted }

    /** True once [elapsedMonotonicMs] of monotonic time has consumed one stage's observation budget. */
    fun observationBudgetElapsed(elapsedMonotonicMs: Long): Boolean =
        elapsedMonotonicMs >= observationBudgetMs
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
 * The automatic settlement decision for **one stage**, derived purely from its real operation
 * state. Returns null when the stage's foreground wait should keep observing (an in-screen running
 * stage within budget). Queued/backoff/terminal/recovery/detached-durable states settle that stage;
 * the injected monotonic budget is applied separately per stage as [ForegroundAttempt.BUDGET_EXPIRED].
 * This never mutates [state] and never cancels anything.
 *
 * [detached] is the stage's execution kind; [admissionDurable] proves its exact work association
 * was saved.
 */
fun settlementReasonFor(
    state: SetupOperationState,
    detached: Boolean,
    admissionDurable: Boolean,
): ForegroundSettledReason? = when {
    state.isTerminal -> ForegroundSettledReason.TERMINAL
    state is SetupOperationState.RecoveryRequired -> ForegroundSettledReason.RECOVERY_REQUIRED
    detached && admissionDurable && state.isPending -> ForegroundSettledReason.DETACHED
    state is SetupOperationState.Waiting -> ForegroundSettledReason.QUEUED
    state is SetupOperationState.RetryScheduled -> ForegroundSettledReason.BACKOFF
    else -> null
}