package com.example.backlogium.ui.setup

import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStage
import com.example.backlogium.work.setup.SetupStageExecution
import com.example.backlogium.work.setup.SetupStageProgress
import com.example.backlogium.work.setup.SetupStageActivity

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
    val activity: SetupStageActivity? = null,
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
    val editing: Boolean = false,
) {
    /** Starting with nothing selected is legitimate — it completes immediately, all skipped. */
    val canStart: Boolean get() = !loading && !running && (!finished || editing) && credentialsConfigured

    /** Whether starting this selection will detach work, so the notification request is warranted. */
    val willDetachWork: Boolean
        get() = stages.any { it.selected && it.execution == SetupStageExecution.DETACHED }

    val detachedStillRunning: Boolean
        get() = running && stages.any {
            it.selected && it.execution == SetupStageExecution.DETACHED &&
                it.outcome is SetupOutcome.NeverRun
        }
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
    editing: Boolean = false,
): List<SetupStageUi> = stages.map { stage ->
    val running = run.running && run.currentStageId == stage.id
    SetupStageUi(
        id = stage.id,
        title = stage.title,
        detail = stage.detail,
        execution = stage.execution,
        unavailableReason = stage.unavailableReason,
        // While a run is in flight the run's own selection is authoritative: it is what is
        // actually happening, and the checkboxes are not editable then anyway.
        selected = if (run.running || (run.finished && !editing)) stage.id in run.selected else stage.id in selection,
        outcome = run.outcomes[stage.id] ?: SetupOutcome.NeverRun,
        running = running,
        progress = run.progress?.takeIf { running && it.isDeterminate && it.activity == SetupStageActivity.RUNNING },
        activity = run.progress?.activity?.takeIf { running },
    )
}

/**
 * The completion summary: one line per stage, naming what succeeded, what failed and why, and what
 * was skipped. Deliberately per-stage — "setup failed" is never the right report, because the
 * stages are unrelated and at most one of them is what went wrong.
 */
fun setupSummaryLines(stages: List<SetupStageUi>): List<String> = stages.map { stage ->
    if (stage.running) return@map "${stage.title} — ${stage.activityLabel()}"
    when (val outcome = stage.outcome) {
        SetupOutcome.Succeeded -> "${stage.title} — done"
        SetupOutcome.Skipped -> "${stage.title} — skipped"
        SetupOutcome.NeverRun ->
            if (stage.available) "${stage.title} — not run" else "${stage.title} — unavailable"
        is SetupOutcome.Failed -> "${stage.title} — ${outcome.reason}"
    }
}

fun SetupStageUi.activityLabel(): String = when (activity) {
    SetupStageActivity.WAITING -> "Waiting for background work or a connection"
    SetupStageActivity.RETRY_BACKOFF -> "Waiting to retry automatically"
    SetupStageActivity.RUNNING -> "Running"
    null -> "Preparing"
}
