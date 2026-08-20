package com.example.backlogium.work.setup

import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * A [SetupStageRunner] over one existing WorkManager job: it triggers the job through the control
 * the app already has for it, then watches that job's unique work name until it settles.
 *
 * **Concurrency is deferred entirely to the wrapped work's own unique name and policy.** Every job
 * setup wraps already enqueues under its own name with its own `ExistingWorkPolicy`, so starting a
 * stage while that work is already running behaves exactly as pressing the corresponding button in
 * Settings would — the existing run continues and nothing is stacked. A second layer of concurrency
 * control here would duplicate three existing mechanisms and be wrong about at least one of them.
 *
 * @param uniqueWorkName the wrapped job's unique work name.
 * @param trigger the app's existing control for that job. Called once per [run].
 * @param progressOf reads a progress snapshot out of the worker's published `Data`, or null when it
 *   has not published a usable one yet.
 * @param failureReason what to tell the user when the work does not complete.
 */
class WorkStageRunner(
    private val workManager: WorkManager,
    private val uniqueWorkName: String,
    private val trigger: suspend () -> Unit,
    private val progressOf: (Data) -> SetupStageProgress?,
    private val failureReason: String,
) : SetupStageRunner {

    override suspend fun run(onProgress: (SetupStageProgress) -> Unit): SetupOutcome {
        // WorkManager keeps a finished work item's record under its unique name until the name is
        // reused. Without this, a stage re-run would read the *previous* run's terminal state as
        // its own and return before the new work had even started.
        val alreadyFinished = workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .first()
            .filter { it.state.isFinished }
            .map { it.id }
            .toSet()

        trigger()

        return workManager.getWorkInfosForUniqueWorkFlow(uniqueWorkName)
            .mapNotNull { infos -> settle(infos, alreadyFinished, onProgress) }
            .first()
    }

    private fun settle(
        infos: List<WorkInfo>,
        alreadyFinished: Set<java.util.UUID>,
        onProgress: (SetupStageProgress) -> Unit,
    ): SetupOutcome? {
        val live = infos.filterNot { it.id in alreadyFinished }
        // Nothing under this name yet: the enqueue has not been observed. Keep waiting rather than
        // reporting an outcome the work never reached.
        val inFlight = live.firstOrNull { !it.state.isFinished }
        if (inFlight != null) {
            progressOf(inFlight.progress)?.let(onProgress)
            return terminalWhileBackingOff(inFlight)
        }
        return live.firstOrNull()?.let(::outcomeOf)
    }

    /**
     * Treat a work item that has dropped back to `ENQUEUED` with attempts behind it as terminal for
     * *setup's* purposes.
     *
     * Every job setup wraps answers a transient failure with `Result.retry()`, so none of them ever
     * reaches `FAILED` on a network error — WorkManager just backs off and tries again, potentially
     * for a long time. Waiting for a state that will not arrive would pin a new user on the setup
     * screen indefinitely, which is a worse first impression than the empty library setup exists to
     * prevent.
     *
     * Nothing is cancelled and nothing is discarded: the retry stays queued and will run on
     * WorkManager's own schedule. Setup simply stops waiting on it, records the stage as failed with
     * a reason, and moves to the next one — which is what failure isolation requires anyway.
     */
    private fun terminalWhileBackingOff(info: WorkInfo): SetupOutcome? =
        if (info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0) {
            SetupOutcome.Failed(failureReason)
        } else {
            null
        }

    private fun outcomeOf(info: WorkInfo): SetupOutcome = when (info.state) {
        WorkInfo.State.SUCCEEDED -> SetupOutcome.Succeeded
        // Cancellation is reported rather than swallowed: a user who cancelled an asset download
        // from Settings should see that stage as unfinished, not as having succeeded.
        WorkInfo.State.CANCELLED -> SetupOutcome.Failed("Cancelled before it finished")
        else -> SetupOutcome.Failed(failureReason)
    }
}
