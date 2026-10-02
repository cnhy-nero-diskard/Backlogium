package com.example.backlogium.work.setup

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.Serializable

/**
 * One registered unit of first-run setup.
 *
 * Setup is an ordered list of these rather than a fixed sequence of screens: the checklist, the run
 * order, the progress display, the completion summary, and the Settings re-run entry are all
 * derived from [SetupStageSource.stages], so registering a fourth stage is a registration and not a
 * redesign of any of those surfaces.
 *
 * **[id] is persisted, so it is an API.** The per-stage opt-in and the per-stage outcome are keyed
 * by it in DataStore. Renaming one orphans a user's stored values for that stage — the same hazard
 * the app's other persisted-by-name enums carry (`QuestMode`, `LibrarySortKey`,
 * `SteamAssetDownloadMode`). Add a stage rather than repurposing an existing id.
 *
 * Every stage is optional. Work that must have completed *before* setup can be presented is not a
 * stage: a stage that cannot be declined contradicts "Skip setup", and credential verification —
 * the obvious candidate — is a precondition of persisting credentials instead, handled entirely in
 * the credential flow.
 */
data class SetupStage(
    /** Stable, persisted identifier. See the class note before changing one. */
    val id: String,
    /** What the checklist calls this stage. */
    val title: String,
    /** One line on what it will do and roughly what it costs. */
    val detail: String,
    /** Whether it starts ticked during onboarding. A re-run from Settings ignores this. */
    val defaultOptIn: Boolean,
    val execution: SetupStageExecution,
    /**
     * Non-null when the capability this stage wraps is not present in the build. Such a stage is
     * still registered — it is shown, disabled, with this reason — so that a prerequisite change
     * landing later needs no edit here, and so its absence cannot silently shorten the checklist.
     */
    val unavailableReason: String? = null,
    /** Admits and observes the stage's existing work. See [SetupStageRunner]. */
    val run: SetupStageRunner,
) {
    val isAvailable: Boolean get() = unavailableReason == null
}

/**
 * Where a stage runs relative to the setup surface.
 *
 * The line is drawn at "is the app usable yet". [IN_SCREEN] work is what makes the app non-empty,
 * so entering before it finishes means entering an empty app. [DETACHED] work is the expensive kind
 * — tens of megabytes of artwork, a paced full-library completion-time sweep — and holding a new
 * user on a setup screen for it would be worse than the empty library it prevents.
 */
enum class SetupStageExecution { IN_SCREEN, DETACHED }

/** Progress reported by a running stage. Indeterminate until the work knows its own total. */
@Serializable
data class SetupStageProgress(
    val processed: Int,
    val total: Int,
    val label: String = "",
) {
    /**
     * True only once the underlying work has published a total. A stage whose work never reports
     * one stays indeterminate rather than rendering a `0 / 0` that reads as stalled.
     */
    val isDeterminate: Boolean get() = total > 0
}

/**
 * Starts one stage's underlying work and observes it.
 *
 * A runner only enqueues and observes. It does not fetch, persist, or derive anything: the effects
 * of running a stage must be identical to those of triggering the same work from its own control,
 * which is what keeps setup out of the way of the invariant that the on-device engine is the sole
 * author of derived values.
 *
 * The admission seam ([admit]) persists the request identity *before* enqueue and returns the exact
 * admitted job afterwards ([StageAdmission.Work]), distinguishing a freshly enqueued job from a live
 * one the scheduler's `KEEP` policy retained ([AdmissionKind]). [observe] then reports that exact
 * job's live states — waiting, running, retry scheduled, terminal, or recovery required when the
 * job can no longer be located. Setup never waits on a queued retry as if it were a failure and
 * never fabricates an arbitrary historical finished job as the current attempt.
 */
interface SetupStageRunner {
    /**
     * The durable unique work name the stage admits under. Exact-name lookup is how an interrupted
     * admission is reconciled when the request tag has already been consumed by WorkManager.
     */
    val uniqueWorkName: String get() = "stage_work"

    /**
     * Admit the stage's work under a durable [requestId], returning the exact association (NEW if
     * this request enqueued the job, REUSED if `KEEP` retained live work), or [StageAdmission.NeedsRecovery]
     * when no exact operation could be established — never an arbitrary historical finished job.
     */
    suspend fun admit(requestId: String): StageAdmission = StageAdmission.NeedsRecovery(
        "This runner has no admission path; the stage cannot start",
    )

    /**
     * Observe an exact admitted job as live operation states. A job that no longer exists (pruned
     * or never recorded) emits [SetupOperationState.RecoveryRequired] rather than looping or
     * claiming success.
     */
    fun observe(workId: String): Flow<SetupOperationState> = emptyFlow()

    /**
     * Recover an interrupted admission from its persisted request identity and its recorded
     * pre-enqueue candidate — the exact tagged job first, then the chain matching the candidate —
     * without enqueueing a duplicate. Null means no exact admission can be established.
     */
    suspend fun locate(requestId: String, candidateWorkId: UUID? = null): AdmittedWork? = null

    /**
     * The exact live job on the chain before this request enqueues — the candidate `KEEP` is
     * expected to retain. Recorded durably before enqueue so a `REUSED` admission stays recoverable
     * by exact identity even if WorkManager prunes the request tag later.
     */
    suspend fun currentLiveCandidate(): UUID? = null
}

/** The ordered stages setup is built from. An interface so tests can register their own. */
interface SetupStageSource {
    val stages: List<SetupStage>
}
