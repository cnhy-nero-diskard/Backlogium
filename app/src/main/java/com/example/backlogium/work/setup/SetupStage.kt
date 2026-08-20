package com.example.backlogium.work.setup

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
    /** Starts the stage's existing work and observes it to a terminal outcome. */
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
 * Starts one stage's underlying work and suspends until it reaches a terminal outcome, reporting
 * progress through [onProgress] as it goes.
 *
 * A runner only enqueues and observes. It does not fetch, persist, or derive anything: the effects
 * of running a stage must be identical to those of triggering the same work from its own control,
 * which is what keeps setup out of the way of the invariant that the on-device engine is the sole
 * author of derived values.
 */
fun interface SetupStageRunner {
    suspend fun run(onProgress: (SetupStageProgress) -> Unit): SetupOutcome
}

/** The ordered stages setup is built from. An interface so tests can register their own. */
interface SetupStageSource {
    val stages: List<SetupStage>
}
