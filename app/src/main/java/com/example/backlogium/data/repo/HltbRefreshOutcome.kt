package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbFailureClass

/** The result of one HLTB lookup, with no ambiguity between no-match and failure. */
sealed interface HltbRefreshOutcome {
    /** The lookup produced and stored a resolved or reviewable match. */
    data class Refreshed(val state: HltbMatchState) : HltbRefreshOutcome

    /** The search completed successfully but returned no candidates. */
    data object NoMatch : HltbRefreshOutcome

    /** The lookup did not establish whether the game has a match. */
    data class Failed(val failureClass: HltbFailureClass) : HltbRefreshOutcome
}

/** Counts and failure evidence from one completed HLTB batch. */
data class HltbBatchResult(
    val attempted: Int,
    val refreshed: Int,
    val noMatch: Int,
    val failed: Int,
    val failureClasses: Set<HltbFailureClass>,
) {
    /** Conservative first threshold: retry only when no game was refreshed. */
    val shouldRetry: Boolean
        get() = refreshed == 0 && failureClasses.any { it.isTransientForRetry }
}

private val HltbFailureClass.isTransientForRetry: Boolean
    get() = this != HltbFailureClass.PARSE
