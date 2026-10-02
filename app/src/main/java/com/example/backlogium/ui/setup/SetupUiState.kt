package com.example.backlogium.ui.setup

import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupOperationState
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStage
import com.example.backlogium.work.setup.SetupStageExecution
import com.example.backlogium.work.setup.SetupStageProgress

/** One checklist row. Everything here is derived from a registered stage plus the run's state. */
data class SetupStageUi(
    val id: String,
    val title: String,
    val detail: String,
    val execution: SetupStageExecution,
    /** Non-null when the stage cannot run in this build; the row is shown disabled with it. */
    val unavailableReason: String?,
    val selected: Boolean,
    val outcome: SetupOutcome,
    val running: Boolean,
    /** Non-null only while [running] and only once the work has published a usable total. */
    val progress: SetupStageProgress?,
    val operationState: SetupOperationState = legacyOperationState(outcome, running, progress),
) {
    val available: Boolean get() = unavailableReason == null

    /** Selectable only when it could actually run. An unavailable stage is never selectable. */
    val selectable: Boolean get() = available
}

data class SetupUiState(
    val loading: Boolean = true,
    val stages: List<SetupStageUi> = emptyList(),
    val running: Boolean = false,
    val finished: Boolean = false,
    /**
     * True once every selected in-screen stage has settled — the point from which the app can be
     * entered while detached stages keep going. Reported by the coordinator rather than re-derived
     * here, so there is one answer to it.
     */
    val inScreenSettled: Boolean = false,
    /** False only on the Settings entry with no credentials: stages cannot succeed without them. */
    val credentialsConfigured: Boolean = true,
) {
    /** Starting with nothing selected is legitimate — it completes immediately, all skipped. */
    val canStart: Boolean get() = !running && credentialsConfigured

    /** Whether starting this selection will detach work, so the notification request is warranted. */
    val willDetachWork: Boolean
        get() = stages.any { it.selected && it.execution == SetupStageExecution.DETACHED }

    val detachedStillRunning: Boolean
        get() = stages.any { it.execution == SetupStageExecution.DETACHED && it.operationState.isPending }

    val pendingCount: Int get() = stages.count { it.operationState.isPending }
}

internal fun legacyOperationState(
    outcome: SetupOutcome,
    running: Boolean,
    progress: SetupStageProgress?,
): SetupOperationState = if (running) SetupOperationState.Running(progress) else when (outcome) {
    SetupOutcome.NeverRun -> SetupOperationState.NeverRun
    SetupOutcome.Succeeded -> SetupOperationState.Succeeded()
    SetupOutcome.Skipped -> SetupOperationState.Skipped
    is SetupOutcome.Failed -> SetupOperationState.Failed(outcome.reason)
}

/**
 * Project the registered stages and the coordinator's state into checklist rows.
 *
 * Every setup surface goes through here, which is what makes a newly registered stage appear in the
 * checklist, the run order, the progress display, and the summary without any of them being edited.
 */
internal fun setupStagesUi(
    stages: List<SetupStage>,
    selection: Set<String>,
    run: SetupRunState,
): List<SetupStageUi> = stages.map { stage ->
    val operation = run.operations[stage.id] ?: legacyOperationState(
        run.outcomes[stage.id] ?: SetupOutcome.NeverRun,
        run.running && run.currentStageId == stage.id,
        run.progress,
    )
    val running = operation is SetupOperationState.Running
    SetupStageUi(
        id = stage.id,
        title = stage.title,
        detail = stage.detail,
        execution = stage.execution,
        unavailableReason = stage.unavailableReason,
        // While a run is in flight the run's own selection is authoritative: it is what is
        // actually happening, and the checkboxes are not editable then anyway.
        selected = if (run.running) stage.id in run.selected else stage.id in selection,
        outcome = run.outcomes[stage.id] ?: SetupOutcome.NeverRun,
        running = running,
        progress = (operation as? SetupOperationState.Running)?.progress
            ?.takeIf { it.isDeterminate && it.processed >= 0 },
        operationState = operation,
    )
}

/**
 * The completion summary: one line per stage, naming what succeeded, what failed and why, and what
 * was skipped. Deliberately per-stage — "setup failed" is never the right report, because the
 * stages are unrelated and at most one of them is what went wrong.
 */
fun setupSummaryLines(stages: List<SetupStageUi>): List<String> = stages.map { stage ->
    when (val operation = stage.operationState) {
        is SetupOperationState.Succeeded -> "${stage.title} — ${operation.detail ?: "done"}"
        SetupOperationState.Skipped -> "${stage.title} — skipped"
        SetupOperationState.NeverRun ->
            if (stage.available) "${stage.title} — not run" else "${stage.title} — unavailable"
        is SetupOperationState.Failed -> "${stage.title} — ${operation.reason ?: "failed"}"
        SetupOperationState.Cancelled -> "${stage.title} — cancelled"
        is SetupOperationState.RecoveryRequired -> "${stage.title} — recovery required"
        is SetupOperationState.Waiting -> "${stage.title} — waiting: ${operation.reason}"
        is SetupOperationState.RetryScheduled -> "${stage.title} — retry scheduled: ${operation.reason}"
        is SetupOperationState.Running -> "${stage.title} — running"
    }
}
