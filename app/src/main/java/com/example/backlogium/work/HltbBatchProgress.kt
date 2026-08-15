package com.example.backlogium.work

import androidx.work.Data
import com.example.backlogium.data.hltb.HltbFailureClass
import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.HltbRefreshOutcome

/**
 * One snapshot of a running HowLongToBeat sweep, as observers see it: how far it has got, the
 * game just processed, and that game's explicit outcome. Failed lookups carry a failure class,
 * which is distinct from a search that found no match.
 *
 * A snapshot, not a history — WorkManager progress holds one value, so the rolling log is
 * accumulated by whoever observes this.
 */
data class HltbBatchProgress(
    val done: Int,
    val total: Int,
    val gameName: String,
    val outcome: HltbRefreshOutcome,
)

/**
 * Read a progress snapshot out of [WorkInfo.progress] data, or null when there is none.
 *
 * Null is the load-bearing case: WorkManager **clears** a worker's progress the moment it
 * completes, so an empty `Data` means "finished", never `0 / 0`. Reporting a zeroed snapshot
 * instead would leave a determinate bar sitting at the start of a run that already ended.
 *
 * The outcome crosses as a compact string because `Data` cannot hold a sealed hierarchy. Older
 * unprefixed match-state names remain readable while an in-flight old run drains.
 */
internal fun hltbBatchProgressFrom(data: Data): HltbBatchProgress? {
    val total = data.getInt(HltbRefreshWorker.KEY_TOTAL, MISSING)
    if (total == MISSING) return null
    return HltbBatchProgress(
        done = data.getInt(HltbRefreshWorker.KEY_PROGRESS, 0),
        total = total,
        gameName = data.getString(HltbRefreshWorker.KEY_CURRENT_GAME).orEmpty(),
        outcome = decodeHltbOutcome(data.getString(HltbRefreshWorker.KEY_OUTCOME)),
    )
}

private const val MISSING = -1

internal fun encodeHltbOutcome(outcome: HltbRefreshOutcome): String = when (outcome) {
    is HltbRefreshOutcome.Refreshed -> "refreshed:${outcome.state.name}"
    HltbRefreshOutcome.NoMatch -> "no_match"
    is HltbRefreshOutcome.Failed -> "failed:${outcome.failureClass.name}"
}

private fun decodeHltbOutcome(value: String?): HltbRefreshOutcome = when {
    value == "no_match" || value == HltbMatchState.UNMATCHED.name -> HltbRefreshOutcome.NoMatch
    value?.startsWith("refreshed:") == true -> value.removePrefix("refreshed:")
        .let { state -> runCatching { HltbMatchState.valueOf(state) }.getOrNull() }
        ?.let(HltbRefreshOutcome::Refreshed)
        ?: legacyFailureOutcome()
    value?.startsWith("failed:") == true -> value.removePrefix("failed:")
        .let { failure -> runCatching { HltbFailureClass.valueOf(failure) }.getOrNull() }
        ?.let(HltbRefreshOutcome::Failed)
        ?: legacyFailureOutcome()
    value == HltbMatchState.RESOLVED.name || value == HltbMatchState.NEEDS_REVIEW.name ->
        HltbRefreshOutcome.Refreshed(HltbMatchState.valueOf(value))
    else -> legacyFailureOutcome()
}

private fun legacyFailureOutcome() =
    HltbRefreshOutcome.Failed(HltbFailureClass.TRANSPORT)
