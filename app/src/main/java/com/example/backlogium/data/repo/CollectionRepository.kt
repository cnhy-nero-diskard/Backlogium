package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.domain.CollectionAccent
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.defaultSort
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read/write access to custom collections and their members (add-custom-collections).
 * Collections are app-owned state persisted in Room — never touched by the Steam sync worker —
 * so every flow here is a plain local observer and every mutation is a plain Room write.
 */
@Singleton
class CollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val time: TimeProvider,
) {
    val collections: Flow<List<Collection>> = collectionDao.observeCollections()

    val allMembers: Flow<List<CollectionMember>> = collectionDao.observeAllMembers()

    fun members(collectionId: Long): Flow<List<CollectionMember>> =
        collectionDao.observeMembers(collectionId)

    suspend fun getById(id: Long): Collection? = collectionDao.getById(id)

    suspend fun getMembers(collectionId: Long): List<CollectionMember> =
        collectionDao.getMembers(collectionId)

    /** Create a collection and return its new id; a fresh collection defaults its sort per mode. */
    suspend fun create(
        name: String,
        mode: CollectionMode,
        sort: CollectionSort? = null,
        targetDate: String? = null,
        accent: CollectionAccent? = null,
    ): Long =
        collectionDao.insert(
            Collection(
                name = name,
                mode = mode,
                sort = sort ?: mode.defaultSort(),
                targetDate = targetDate.takeIf { mode == CollectionMode.DEADLINE_GOAL },
                accent = accent,
                createdAt = time.nowMillis(),
            ),
        )

    suspend fun updateDetails(
        id: Long,
        name: String,
        mode: CollectionMode,
        sort: CollectionSort,
        targetDate: String?,
        accent: CollectionAccent?,
    ) = collectionDao.updateDetails(
        id,
        name,
        mode,
        sort,
        targetDate.takeIf { mode == CollectionMode.DEADLINE_GOAL },
        accent,
    )

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
}
