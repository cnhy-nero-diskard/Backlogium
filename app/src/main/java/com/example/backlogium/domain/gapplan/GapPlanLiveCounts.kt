package com.example.backlogium.domain.gapplan

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one thing enrichment needs from the outside world: a live count for an app id, or null.
 *
 * A narrow seam rather than a dependency on `GameRepository`, for two reasons. It keeps this
 * bounded-request policy from being able to reach anything else in the library, and it lets the
 * bounds — concurrency, the window, the ceiling — be tested against a stub that stalls or fails on
 * demand, which a concrete repository cannot be made to do.
 *
 * Null means *unavailable*, never zero players: network error, a non-success result for a delisted
 * app id, or a missing count in an otherwise successful response all resolve here.
 */
fun interface CurrentPlayerCounts {
    suspend fun countFor(appId: Long): Int?
}

/**
 * Bounded, post-finalization current-player lookups for a generation's multiplayer members.
 *
 * Every bound here exists because the alternative is an unthrottled burst against Steam from a
 * screen the player just opened:
 *
 * - **At most fifteen app ids.** Three variants of at most five games cannot contain more, and
 *   usually contain fewer once members shared between variants are deduplicated. The ceiling is
 *   enforced anyway rather than assumed from the plan's shape.
 * - **Concurrency four.** Enough to finish quickly, far short of fifteen simultaneous requests.
 * - **One eight-second window for the whole pass.** Not per request: the plan is already complete
 *   and usable without any of this, so the enrichment gets one bounded chance and is then done.
 *
 * Nothing here is persisted, and nothing here decides membership. A failed or late lookup leaves a
 * plan that was already finished.
 */
@Singleton
class GapPlanLiveCounts @Inject constructor(
    private val playerCounts: CurrentPlayerCounts,
) {
    /**
     * The distinct multiplayer app ids worth a lookup for this generation, capped and ordered
     * deterministically so the cap never silently depends on variant iteration order.
     *
     * Single-player members are absent entirely — no lookup is issued for them at all, rather than
     * issued and discarded.
     */
    fun lookupTargets(snapshot: GapPlanSnapshot): List<Long> = snapshot.variants
        .asSequence()
        .flatMap { it.members.asSequence() }
        .filter { it.multiplayer }
        .map { it.appId }
        .distinct()
        .sorted()
        .take(MAX_LOOKUPS)
        .toList()

    /**
     * Fetches what it can inside the window and returns only the counts that arrived.
     *
     * An app id missing from the result is *unavailable*, never zero. [CurrentPlayerCounts] already
     * resolves a failure to null rather than a placeholder, and the window drops whatever has not
     * answered — so the two failure modes converge on the same honest absence.
     */
    suspend fun fetch(appIds: List<Long>): Map<Long, Int> {
        if (appIds.isEmpty()) return emptyMap()
        val gate = Semaphore(MAX_CONCURRENCY)
        // Counts are recorded as they arrive rather than collected at the end, so a window that
        // expires mid-pass still returns the answers it already had. Returning nothing would
        // discard real facts to no benefit — the plan is complete either way, and the difference
        // is only whether the rows that *did* answer get to say so.
        val lock = Mutex()
        val answered = mutableMapOf<Long, Int>()
        withTimeoutOrNull(WINDOW_MILLIS) {
            coroutineScope {
                appIds.map { appId ->
                    async {
                        gate.withPermit {
                            val count = playerCounts.countFor(appId)
                            if (count != null) lock.withLock { answered[appId] = count }
                        }
                    }
                }.awaitAll()
            }
        }
        return lock.withLock { answered.toMap() }
    }

    companion object {
        /** Three variants of at most five games; the ceiling is enforced, not inferred. */
        const val MAX_LOOKUPS = 15
        const val MAX_CONCURRENCY = 4

        /** One window for the whole pass, not per request. */
        const val WINDOW_MILLIS = 8_000L
    }
}
