package com.example.backlogium.data.repo

import com.example.backlogium.domain.CloudPresencePlaytimePlacement
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
            // advances past the delta. Refuse unless the drained window covers the diff window on
            // both edges, proving the evidence is complete for it; a superset is safe because
            // placement clips intervals to the period. A terminal drain alone proves nothing about
            // the right edge: the service bounds `windowEnd` by the latest current observation or
            // transition, which stays stale when the poller stops, while an unobserved tail carries
            // no rejected interval for the placement rule to refuse — it would proportionally
            // assign the whole Steam delta to the earlier confirmed span. The right edge is
            // therefore cadence-aware rather than exact: `SteamSyncWorker` captures `periodEndAt`
            // before this read while the poller observes once per minute, so a healthy terminal
            // snapshot normally lags `periodEndAt` by up to one polling interval. Accept a tail
            // within the placement tolerance and refuse only a tail beyond it, so ordinary
            // periodic placement is admitted while a stale (e.g. multi-day) tail is still refused.
            return when (val result = repository.readRemainingHistory(trigger, consume = ingestor::ingest)) {
                is CloudReadResult.Success -> {
                    val snapshot = result.snapshot
                    if (snapshot.hasMore) {
                        null
                    } else if (snapshot.windowStart > periodStartAt) {
                        null
                    } else if (periodEndAt - snapshot.windowEnd > CloudPresencePlaytimePlacement.DEFAULT_GAP_TOLERANCE_MILLIS) {
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
