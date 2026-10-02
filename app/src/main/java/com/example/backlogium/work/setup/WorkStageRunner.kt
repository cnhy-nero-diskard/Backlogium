package com.example.backlogium.work.setup

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A [SetupStageRunner] over one existing WorkManager job: it admits the job through the app's
 * existing scheduler control, persisting a durable request identity on the request itself, then
 * reports the exact admitted job's live states.
 *
 * **Concurrency is deferred entirely to the wrapped work's own unique name and `KEEP` policy.**
 * Every job setup wraps already enqueues under its own name with its own `ExistingWorkPolicy`, so
 * starting a stage while that work is already running behaves exactly as pressing the corresponding
 * button in Settings would — the existing run continues and nothing is stacked. The admission
 * handle ([AdmittedWork]) merely *names* whichever job that policy admitted (a fresh one, or the
 * live one `KEEP` retained); setup never adds a second layer of concurrency control.
 *
 * Live states replace the old terminal-only wait: a job sitting `ENQUEUED` in retry backoff is
 * reported as [SetupOperationState.RetryScheduled] (never a terminal failure), a queued job as
 * [SetupOperationState.Waiting], a cancelled one as [SetupOperationState.Cancelled], and a job that
 * can no longer be located as [SetupOperationState.RecoveryRequired]. Terminal classification may
 * be overridden per stage ([classifyTerminal]) so a stage whose domain result lives outside the
 * worker's output — the library poll's attributable Room record — can report the truthful outcome
 * instead of WorkManager success alone.
 *
 * @param admitWork the existing scheduler control for the wrapped unique name, returning the exact
 *   admitted job or null when nothing could be admitted.
 * @param classifyTerminal optional suspend terminal-state override keyed by exact [String] work id
 *   and [WorkInfo]; null keeps the default scheduler-state classification (worker-output reasons,
 *   distinct cancellation, retry stays pending).
 */
class WorkStageRunner(
    private val workManager: WorkManager,
    override val uniqueWorkName: String,
    private val admitWork: suspend (requestId: String) -> AdmittedWork?,
    private val progressOf: (Data) -> SetupStageProgress?,
    private val failureReason: String,
    private val classifyTerminal: suspend (String, WorkInfo) -> SetupOperationState? = { _, _ -> null },
) : SetupStageRunner {

    override suspend fun admit(requestId: String): StageAdmission {
        val admitted = admitWork(requestId) ?: return StageAdmission.NeedsRecovery(
            "Couldn't admit this step's work. Try again from Settings.",
        )
        val initial = workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .first()
            .firstOrNull { it.id == admitted.workId }
            ?.let { stateOf(admitted.workId.toString(), it) }
            ?: SetupOperationState.Waiting("Waiting for a moment to run")
        return StageAdmission.Work(
            workId = admitted.workId,
            uniqueWorkName = uniqueWorkName,
            kind = when (admitted) {
                is AdmittedWork.New -> AdmissionKind.NEW
                is AdmittedWork.Reused -> AdmissionKind.REUSED
            },
            initialState = initial,
        )
    }

    override fun observe(workId: String): Flow<SetupOperationState> =
        workManager.getWorkInfoByIdFlow(workId.toUuid()).map { info ->
            if (info == null) {
                // The exact admitted job is gone: pruned by WorkManager or never persisted. That is
                // an explicit recovery request, never a loop and never a claimed success.
                SetupOperationState.RecoveryRequired(
                    "The work this step admitted is no longer available. Request it again from Settings.",
                )
            } else {
                stateOf(workId, info)
            }
        }

    override suspend fun currentLiveCandidate(): UUID? =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .first()
            .firstOrNull { !it.state.isFinished }
            ?.id

    override suspend fun locate(requestId: String, candidateWorkId: UUID?): AdmittedWork? {
        // The request tag is the exact identity of this request's own job: if WorkManager still
        // holds it, that is the admitted operation, whatever state it reached.
        val infos = workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName).first()
        val requestWorkId = requestId.toUuid()
        infos.singleOrNull { it.id == requestWorkId && setupRequestTag(requestId) in it.tags }
            ?.let { return AdmittedWork.New(it.id) }
        // A `KEEP`-retained live job carries no setup tag; only the candidate recorded before
        // enqueue can prove it. Never fall back to an arbitrary live or historical job.
        if (candidateWorkId != null) {
            // A terminal candidate alone cannot prove KEEP retained it: it may have finished
            // before a different control admitted replacement work and this request was dropped.
            // Fast-finished NEW work is still provable above by its exact request id and tag.
            if (infos.any { it.id == candidateWorkId && !it.state.isFinished }) {
                return AdmittedWork.Reused(candidateWorkId)
            }
        }
        return null
    }

    private suspend fun stateOf(workId: String, info: WorkInfo): SetupOperationState = when {
        info.state.isFinished -> classifyTerminal(workId, info) ?: terminalStateOf(info)
        info.state == WorkInfo.State.RUNNING -> SetupOperationState.Running(progressOf(info.progress))
        info.state == WorkInfo.State.ENQUEUED ->
            if (info.runAttemptCount > 0) {
                SetupOperationState.RetryScheduled(
                    attempt = info.runAttemptCount,
                    reason = retryReasonFor(workId),
                )
            } else {
                SetupOperationState.Waiting("Waiting for a moment to run")
            }
        info.state == WorkInfo.State.BLOCKED ->
            SetupOperationState.Waiting("Waiting for network or storage")
        else -> SetupOperationState.Waiting("Waiting for a moment to run")
    }

    /**
     * Default terminal classification: the worker's own attributable failure reason when it
     * publishes one (HLTB), the stage's fallback otherwise; cancellation stays distinct; and there
     * is no success without a scheduler result.
     */
    private fun terminalStateOf(info: WorkInfo): SetupOperationState = when (info.state) {
        WorkInfo.State.SUCCEEDED -> SetupOperationState.Succeeded()
        WorkInfo.State.FAILED -> SetupOperationState.Failed(
            info.outputData.getString(HltbDatasetWorker.KEY_FAILURE_REASON) ?: failureReason,
        )
        WorkInfo.State.CANCELLED -> SetupOperationState.Cancelled
        else -> SetupOperationState.Waiting("Waiting for a moment to run")
    }

    private fun retryReasonFor(workId: String): String =
        "It didn't reach Steam; it queued another attempt on its own."

    private fun String.toUuid(): UUID =
        runCatching { UUID.fromString(this) }
            .getOrElse { error("Admitted work id must be a UUID but was '$this'") }
}
