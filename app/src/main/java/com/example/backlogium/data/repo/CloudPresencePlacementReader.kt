package com.example.backlogium.data.repo

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Best-effort complete cloud snapshots used to place owned-game playtime deltas. */
fun interface CloudPresencePlacementReader {
    suspend fun read(
        trigger: CloudReadTrigger,
        periodStartAt: Long,
        periodEndAt: Long,
    ): CloudPresenceSnapshot?
}

/** Optional placement evidence never turns a Steam sync failure into a failed sync. */
suspend fun CloudPresencePlacementReader.readOrNull(
    trigger: CloudReadTrigger,
    periodStartAt: Long,
    periodEndAt: Long,
): CloudPresenceSnapshot? = try {
    read(trigger, periodStartAt, periodEndAt)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

/** Adapts the optional cloud repository to callers that must continue when it is unavailable. */
@Singleton
class RepositoryCloudPresencePlacementReader @Inject constructor(
    private val repository: CloudPresenceRepository,
    private val ingestor: CloudPresenceSessionIngestor,
) : CloudPresencePlacementReader {
    override suspend fun read(
        trigger: CloudReadTrigger,
        periodStartAt: Long,
        periodEndAt: Long,
    ): CloudPresenceSnapshot? {
        try {
            if (periodStartAt <= 0L || periodEndAt <= periodStartAt) return null
            // Drain the still-unread history rather than treating one shared-cursor page as the
            // placement snapshot: the cursor is also advanced by Settings/manual reads, so a single
            // page may be only the suffix of the Steam diff window, and discarding a `hasMore` page
            // after `read` already persisted its cursor would lose it once the Steam baseline
            // advances past the delta. Refuse unless the drained window starts at or before the
            // diff window, proving the evidence is complete for it; a superset is safe because
            // placement clips intervals to the period. A missing tail needs no gate: draining to
            // the terminal page observes it, and any unobserved span inside arrives as coverage the
            // placement rule already rejects.
            return when (val result = repository.readRemainingHistory(trigger, consume = ingestor::ingest)) {
                is CloudReadResult.Success -> {
                    val snapshot = result.snapshot
                    if (snapshot.hasMore) {
                        null
                    } else if (snapshot.windowStart > periodStartAt) {
                        null
                    } else {
                        snapshot
                    }
                }
                else -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }
}
