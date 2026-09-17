package com.example.backlogium.data.repo

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Best-effort complete cloud snapshots used to place owned-game playtime deltas. */
fun interface CloudPresencePlacementReader {
    suspend fun read(trigger: CloudReadTrigger): CloudPresenceSnapshot?
}

/** Adapts the optional cloud repository to callers that must continue when it is unavailable. */
@Singleton
class RepositoryCloudPresencePlacementReader @Inject constructor(
    private val repository: CloudPresenceRepository,
    private val ingestor: CloudPresenceSessionIngestor,
) : CloudPresencePlacementReader {
    override suspend fun read(trigger: CloudReadTrigger): CloudPresenceSnapshot? = try {
        when (val result = repository.read(trigger, consume = ingestor::ingest)) {
            is CloudReadResult.Success -> result.snapshot.takeUnless { it.hasMore }
            else -> null
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
