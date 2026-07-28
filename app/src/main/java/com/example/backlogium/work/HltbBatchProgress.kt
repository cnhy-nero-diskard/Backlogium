package com.example.backlogium.work

import androidx.work.Data
import com.example.backlogium.data.repo.HltbMatchState

/**
 * One snapshot of a running HowLongToBeat sweep, as observers see it: how far it has got, the
 * game just processed, and that game's outcome. A null [outcome] means the lookup failed
 * (transport error), which is distinct from a search that found no match ([HltbMatchState.UNMATCHED]).
 *
 * A snapshot, not a history — WorkManager progress holds one value, so the rolling log is
 * accumulated by whoever observes this.
 */
data class HltbBatchProgress(
    val done: Int,
    val total: Int,
    val gameName: String,
    val outcome: HltbMatchState?,
)

/**
 * Read a progress snapshot out of [WorkInfo.progress] data, or null when there is none.
 *
 * Null is the load-bearing case: WorkManager **clears** a worker's progress the moment it
 * completes, so an empty `Data` means "finished", never `0 / 0`. Reporting a zeroed snapshot
 * instead would leave a determinate bar sitting at the start of a run that already ended.
 *
 * The outcome crosses as a name string because `Data` cannot hold an enum; an absent or
 * unrecognized name maps back to null, i.e. a failed lookup.
 */
internal fun hltbBatchProgressFrom(data: Data): HltbBatchProgress? {
    val total = data.getInt(HltbRefreshWorker.KEY_TOTAL, MISSING)
    if (total == MISSING) return null
    return HltbBatchProgress(
        done = data.getInt(HltbRefreshWorker.KEY_PROGRESS, 0),
        total = total,
        gameName = data.getString(HltbRefreshWorker.KEY_CURRENT_GAME).orEmpty(),
        outcome = data.getString(HltbRefreshWorker.KEY_OUTCOME)
            ?.let { name -> runCatching { HltbMatchState.valueOf(name) }.getOrNull() },
    )
}

private const val MISSING = -1
