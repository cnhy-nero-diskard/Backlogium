package com.example.backlogium.work.setup

/**
 * A stage's terminal state. Each stage reaches its own independently: one failing stage neither
 * cancels a sibling nor makes setup as a whole fail, because a HowLongToBeat timeout says nothing
 * about whether artwork downloaded.
 */
sealed interface SetupOutcome {
    /** Never selected, never run — the state of a stage registered after a completed setup. */
    data object NeverRun : SetupOutcome

    data object Succeeded : SetupOutcome

    /** [reason] is user-facing: the summary must say *which* stage failed and why. */
    data class Failed(val reason: String) : SetupOutcome

    /** Deselected, or setup was declined. */
    data object Skipped : SetupOutcome
}

private const val ENCODED_NEVER_RUN = "never"
private const val ENCODED_SUCCEEDED = "ok"
private const val ENCODED_SKIPPED = "skipped"
private const val ENCODED_FAILED_PREFIX = "failed:"

/** Wire form for DataStore. Kept next to [decodeSetupOutcome] so the pair stays symmetric. */
fun encodeSetupOutcome(outcome: SetupOutcome): String = when (outcome) {
    SetupOutcome.NeverRun -> ENCODED_NEVER_RUN
    SetupOutcome.Succeeded -> ENCODED_SUCCEEDED
    SetupOutcome.Skipped -> ENCODED_SKIPPED
    is SetupOutcome.Failed -> ENCODED_FAILED_PREFIX + outcome.reason
}

/**
 * Read a stored outcome back. Anything unrecognized decodes to [SetupOutcome.NeverRun] rather than
 * throwing: a value written by a future version of the app must not stop setup from rendering.
 */
fun decodeSetupOutcome(encoded: String?): SetupOutcome = when {
    encoded == null -> SetupOutcome.NeverRun
    encoded == ENCODED_SUCCEEDED -> SetupOutcome.Succeeded
    encoded == ENCODED_SKIPPED -> SetupOutcome.Skipped
    encoded.startsWith(ENCODED_FAILED_PREFIX) ->
        SetupOutcome.Failed(encoded.removePrefix(ENCODED_FAILED_PREFIX))
    else -> SetupOutcome.NeverRun
}

/**
 * Narrow stored outcomes to the stages this build actually registers.
 *
 * Both directions matter. An id that is stored but no longer registered is dropped — a removed or
 * renamed stage must not surface as a nameless row. An id that is registered but not stored reads
 * as [SetupOutcome.NeverRun], which is the honest answer both for a fresh install and for a stage
 * added after the user already completed setup.
 */
fun projectSetupOutcomes(
    stored: Map<String, SetupOutcome>,
    registeredIds: List<String>,
): Map<String, SetupOutcome> =
    registeredIds.associateWith { stored[it] ?: SetupOutcome.NeverRun }
