package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.CollectionRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Commits an accepted pick as a deadline collection, through the transaction collections
 * already use.
 *
 * The one behaviour worth naming: **nothing here touches the preview**. On failure it returns a
 * failed `Result` and the caller still holds the snapshot it passed in, so the player can retry
 * the same accepted membership rather than being sent back to regenerate a plan they had already
 * decided on.
 */
@Singleton
class GapPlanCollectionCreator @Inject constructor(
    private val collectionRepository: CollectionRepository,
) {
    /**
     * Creates the collection and returns its new id.
     *
     * `CollectionRepository.save` is the existing atomic path: the collection row and every
     * membership row commit as one unit, so an interrupted save leaves neither a collection nor a
     * subset of its members. Adding a second persistence protocol for this one case would mean two
     * places that have to agree about atomicity.
     *
     * The failure is captured rather than thrown so the surface can release its busy state and
     * offer a retry, which is the difference between a recoverable error and a lost plan.
     */
    suspend fun create(snapshot: GapPlanSnapshot, pick: GapPlanPick): Result<Long> = runCatching {
        val draft = GapPlanAdoption.toDraft(snapshot, pick)
            ?: error("a tier with no pick has nothing to adopt")
        collectionRepository.save(draft)
    }
}
