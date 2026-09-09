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
 *
 * Membership of a hidden game is **retained and filtered on read** (add-hidden-games): the row
 * stays, so unhiding restores the game to the collections it was in rather than asking the player
 * to re-add it, while every member read — contents, counts, and the derived banner built from
 * them — behaves as though the collection never contained it.
 */
@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val hiddenGamesRepository: HiddenGamesRepository,
    private val time: TimeProvider,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
) {
    val collections: Flow<List<Collection>> = collectionDao.observeCollections().map { rows ->
        rows.map { it.resolveSortForMode() }
    }

    val allMembers: Flow<List<CollectionMember>> = collectionDao.observeAllMembers().visibleMembers()

    /** Custom collection summaries exposed without leaking Room entities to new UI surfaces. */
    val customOverviews: Flow<List<CustomCollectionOverview>> = combine(
        collections,
        collectionDao.observeAllMembers().visibleMembers(),
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
        collectionDao.observeMembers(collectionId).visibleMembers()

    suspend fun getById(id: Long): Collection? = collectionDao.getById(id)?.resolveSortForMode()

    suspend fun getMembers(collectionId: Long): List<CollectionMember> {
        val hidden = hiddenGamesRepository.hiddenAppIdSet()
        return collectionDao.getMembers(collectionId).filterNot { it.appId in hidden }
    }

    private fun Flow<List<CollectionMember>>.visibleMembers(): Flow<List<CollectionMember>> =
        combine(hiddenGamesRepository.hiddenAppIds) { members, hidden ->
            if (hidden.isEmpty()) members else members.filterNot { it.appId in hidden }
        }

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
        // The draft arrives filtered — a hidden member is absent from every member read, so it is
        // absent from the editing buffer too. Diffing it against the unfiltered stored rows would
        // therefore read as a removal and delete the membership hiding promised to retain.
        val hidden = hiddenGamesRepository.hiddenAppIdSet()
        val desiredSet = desired.toSet()
        val orderedExisting = existing.sortedBy { it.orderIndex }
        val rankByAppId = orderedExisting.mapIndexed { index, member -> member.appId to index }.toMap()
        val hiddenRetained = orderedExisting.filter { it.appId in hidden && it.appId !in desiredSet }
        // Rewriting only the visible draft to 0..N-1 would collide with a retained hidden row kept
        // at its old index. Keep each hidden member in its stored slot and fill the gaps with the
        // visible draft order, so a save while hidden leaves the full sequence unchanged and every
        // retained row gets one unique contiguous index.
        val total = desired.size + hiddenRetained.size
        val merged: MutableList<Long?> = MutableList(total) { null }
        hiddenRetained.forEachIndexed { position, member ->
            val rank = rankByAppId[member.appId] ?: position
            merged[minOf(rank, total - (hiddenRetained.size - position))] = member.appId
        }
        var cursor = 0
        for (slot in 0 until total) {
            if (merged[slot] == null) {
                merged[slot] = desired[cursor++]
            }
        }
        val finalOrder = merged.filterNotNull()

        desired.forEach { appId ->
            if (appId !in existingIds) {
                collectionDao.insertMember(
                    CollectionMember(collectionId = id, appId = appId, orderIndex = 0),
                )
            }
        }
        finalOrder.forEachIndexed { index, appId ->
            collectionDao.setOrderIndex(id, appId, index)
        }
        existing.forEach { member ->
            if (member.appId !in desired && member.appId !in hidden) {
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
