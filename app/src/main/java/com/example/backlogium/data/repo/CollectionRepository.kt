package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.CustomCollectionOverview
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.defaultSort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Plain-value end state submitted by the collection editor for one atomic save. */
data class CollectionSaveDraft(
    val id: Long,
    val name: String,
    val mode: CollectionMode,
    val sort: CollectionSort,
    val targetDate: String?,
    val accent: CollectionAccent?,
    val timeBasis: CollectionTimeBasis,
    val description: String?,
    val memberAppIds: List<Long>,
    val doneAppIds: Set<Long>,
)

/**
 * Read/write access to custom collections and their members (add-custom-collections).
 * Collections are app-owned state persisted in Room — never touched by the Steam sync worker —
 * so every flow here is a plain local observer and every mutation is a plain Room write.
 */
@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val time: TimeProvider,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
) {
    val collections: Flow<List<Collection>> = collectionDao.observeCollections().map { rows ->
        rows.map { it.resolveSortForMode() }
    }

    val allMembers: Flow<List<CollectionMember>> = collectionDao.observeAllMembers()

    /** Custom collection summaries exposed without leaking Room entities to new UI surfaces. */
    val customOverviews: Flow<List<CustomCollectionOverview>> = combine(
        collections,
        collectionDao.observeAllMembers(),
    ) { collections, members ->
        val membersByCollection = members.groupBy { it.collectionId }
        collections.map { collection ->
            CustomCollectionOverview(
                id = collection.id,
                name = collection.name,
                mode = collection.mode,
                sort = collection.sort,
                targetDate = collection.targetDate,
                accent = collection.accent,
                timeBasis = collection.timeBasis,
                description = collection.description,
                displayOrder = collection.displayOrder,
                memberAppIds = membersByCollection[collection.id]
                    .orEmpty()
                    .sortedBy { it.orderIndex }
                    .map { it.appId },
            )
        }
    }

    fun members(collectionId: Long): Flow<List<CollectionMember>> =
        collectionDao.observeMembers(collectionId)

    suspend fun getById(id: Long): Collection? = collectionDao.getById(id)?.resolveSortForMode()

    suspend fun getMembers(collectionId: Long): List<CollectionMember> =
        collectionDao.getMembers(collectionId)

    /** Create a collection and return its new id; a fresh collection defaults its sort per mode. */
    suspend fun create(
        name: String,
        mode: CollectionMode,
        sort: CollectionSort? = null,
        targetDate: String? = null,
        accent: CollectionAccent? = null,
        timeBasis: CollectionTimeBasis = CollectionTimeBasis.COMPLETIONIST,
        description: String? = null,
    ): Long =
        collectionDao.insert(
            Collection(
                name = name,
                mode = mode,
                sort = sort?.resolveForMode(mode) ?: mode.defaultSort(),
                targetDate = targetDate.takeIf { mode == CollectionMode.DEADLINE_GOAL },
                accent = accent,
                timeBasis = timeBasis,
                createdAt = time.nowMillis(),
                description = description,
                displayOrder = collectionDao.getAll().maxOfOrNull { it.displayOrder }?.plus(1) ?: 0,
            ),
        )

    suspend fun updateDetails(
        id: Long,
        name: String,
        mode: CollectionMode,
        sort: CollectionSort,
        targetDate: String?,
        accent: CollectionAccent?,
        timeBasis: CollectionTimeBasis,
        description: String?,
    ) = collectionDao.updateDetails(
        id,
        name,
        mode,
        sort.resolveForMode(mode),
        targetDate.takeIf { mode == CollectionMode.DEADLINE_GOAL },
        accent,
        timeBasis,
        description,
    )

    /** Commit the buffered collection fields and membership reconciliation as one unit. */
    suspend fun save(draft: CollectionSaveDraft): Long = transaction.run {
        val id = if (draft.id == 0L) {
            create(
                name = draft.name,
                mode = draft.mode,
                sort = draft.sort,
                targetDate = draft.targetDate,
                accent = draft.accent,
                timeBasis = draft.timeBasis,
                description = draft.description,
            )
        } else {
            updateDetails(
                id = draft.id,
                name = draft.name,
                mode = draft.mode,
                sort = draft.sort,
                targetDate = draft.targetDate,
                accent = draft.accent,
                timeBasis = draft.timeBasis,
                description = draft.description,
            )
            draft.id
        }

        val desired = draft.memberAppIds.distinct()
        val existing = collectionDao.getMembers(id)
        val existingIds = existing.mapTo(mutableSetOf()) { it.appId }

        desired.forEach { appId ->
            if (appId !in existingIds) {
                collectionDao.insertMember(
                    CollectionMember(collectionId = id, appId = appId, orderIndex = 0),
                )
            }
        }
        desired.forEachIndexed { index, appId ->
            collectionDao.setOrderIndex(id, appId, index)
        }
        existing.forEach { member ->
            if (member.appId !in desired) {
                collectionDao.removeMember(id, member.appId)
            }
        }
        desired.forEach { appId ->
            collectionDao.setMemberDone(id, appId, appId in draft.doneAppIds)
        }
        id
    }

    /** Deleting a collection cascades to its memberships via the FK. */
    suspend fun delete(id: Long) = collectionDao.delete(id)

    /** Append a game to a collection; a no-op when the game is already a member. */
    suspend fun addMember(collectionId: Long, appId: Long) = collectionDao.addMember(collectionId, appId)

    suspend fun removeMember(collectionId: Long, appId: Long) =
        collectionDao.removeMember(collectionId, appId)

    /** Persist or clear one member's manual done mark (ordered-queue collections). */
    suspend fun setMemberDone(collectionId: Long, appId: Long, done: Boolean) =
        collectionDao.setMemberDone(collectionId, appId, done)

    /** Persist a new full sequence (ordered-queue reorder), atomically. */
    suspend fun reorderMembers(collectionId: Long, orderedAppIds: List<Long>) =
        collectionDao.reorderMembers(collectionId, orderedAppIds)

    /** Persist a new full collection sequence atomically. */
    suspend fun reorderCollections(orderedIds: List<Long>) =
        collectionDao.reorderCollections(orderedIds)
}

private fun Collection.resolveSortForMode(): Collection =
    if (sort == CollectionSort.UNAVAILABLE) copy(sort = mode.defaultSort()) else this

private fun CollectionSort.resolveForMode(mode: CollectionMode): CollectionSort =
    if (this == CollectionSort.UNAVAILABLE) mode.defaultSort() else this
