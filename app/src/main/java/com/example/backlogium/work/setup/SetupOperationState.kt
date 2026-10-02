package com.example.backlogium.work.setup

/**
 * One stage's real underlying operation, independent of the foreground journey that admitted it.
 *
 * This deliberately carries more than a terminal verdict: the same row must be able to report
 * "waiting for connectivity", "scheduled for retry", and "reached a terminal outcome" without any
 * of those forcing the setup surface to read as finished, failed, or completed. Scheduler state
 * (queued, running, retry backoff) stays distinct from operation outcome (succeeded, failed,
 * cancelled, skipped), so busy or elapsed work can never masquerade as a terminal failure.
 */
sealed interface SetupOperationState {

    /** Never selected, never admitted — the state of a stage registered after a completed setup. */
    data object NeverRun : SetupOperationState

    /** Admitted work that cannot start yet (connectivity, storage constraints…). [reason] is user-facing. */
    data class Waiting(val reason: String) : SetupOperationState

    /**
     * Admitted work currently executing. [progress] is the operation's own real progress: null
     * while it has not published a usable total, determinate once it has.
     */
    data class Running(val progress: SetupStageProgress? = null) : SetupOperationState

    /** A transient failure scheduled another attempt. Pending work, never a terminal failure. */
    data class RetryScheduled(val attempt: Int, val reason: String) : SetupOperationState

    /** The domain effect the stage exists for happened. [detail] is optional per-stage detail. */
    data class Succeeded(val detail: String? = null) : SetupOperationState

    /** Terminally failed. [reason] names which stage failed and why. */
    data class Failed(val reason: String? = null) : SetupOperationState

    /** This stage's underlying work was cancelled; sibling work is untouched. */
    data object Cancelled : SetupOperationState

    /** Declined in the initial setup choice; a later subset run preserves unrelated outcomes. */
    data object Skipped : SetupOperationState

    /**
     * The admitted operation can no longer be located (pruned WorkManager record, or the exact
     * association was never established). It needs an explicit new request and an explanation —
     * never an inferred success or an infinite wait.
     */
    data class RecoveryRequired(val reason: String? = null) : SetupOperationState

    /**
     * Admitted work still progressing without needing an explicit user decision. Waiting, running,
     * and retry-scheduled work are pending here, so no surface can mistake them for terminal
     * outcomes or for each other.
     */
    val isPending: Boolean
        get() = when (this) {
            is Waiting, is Running, is RetryScheduled -> true
            else -> false
        }

    /** The stage's work has reached its own terminal outcome. */
    val isTerminal: Boolean
        get() = when (this) {
            is Succeeded, is Failed, is Cancelled, is Skipped -> true
            else -> false
        }
}
