package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.Update
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionSort
import kotlinx.coroutines.flow.Flow

/**
 * Persistence for custom collections and their members (add-custom-collections). Collections
 * are app-owned state: every read/write here is local Room, and the Steam sync worker never
 * touches these tables, so a games-table rebuild cannot reset, drop, or reorder them.
 */
@Dao
interface CollectionDao {

    @Query("SELECT * FROM collections ORDER BY createdAt ASC, id ASC")
    fun observeCollections(): Flow<List<Collection>>

    @Query("SELECT * FROM collections")
    suspend fun getAll(): List<Collection>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): Collection?

    @Insert
    suspend fun insert(collection: Collection): Long

    @Update
    suspend fun update(collection: Collection)

    /** Insert-or-replace by PK — the backup/restore merge engine's row key (add-backup-restore). */
    @Upsert
    suspend fun upsert(collection: Collection)

    @Upsert
    suspend fun upsertMember(member: CollectionMember)

    @Query(
        "UPDATE collections SET name = :name, mode = :mode, sort = :sort, targetDate = :targetDate " +
            "WHERE id = :id",
    )
    suspend fun updateDetails(
        id: Long,
        name: String,
        mode: CollectionMode,
        sort: CollectionSort,
        targetDate: String?,
    )

    /** Deleting a collection cascades to its memberships via the FK. */
    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM collection_members WHERE collectionId = :collectionId ORDER BY orderIndex ASC")
    fun observeMembers(collectionId: Long): Flow<List<CollectionMember>>

    @Query("SELECT * FROM collection_members WHERE collectionId = :collectionId ORDER BY orderIndex ASC")
    suspend fun getMembers(collectionId: Long): List<CollectionMember>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMember(member: CollectionMember)

    @Query("DELETE FROM collection_members WHERE collectionId = :collectionId AND appId = :appId")
    suspend fun removeMember(collectionId: Long, appId: Long)

    @Query(
        "UPDATE collection_members SET orderIndex = :orderIndex " +
            "WHERE collectionId = :collectionId AND appId = :appId",
    )
    suspend fun setOrderIndex(collectionId: Long, appId: Long, orderIndex: Int)

    /**
     * Append a member at the end of the current sequence. Ignored (no-op via
     * [OnConflictStrategy.IGNORE]) when the game is already a member — a game can be added to a
     * collection only once, though it may belong to many collections independently.
     */
    @Transaction
    suspend fun addMember(collectionId: Long, appId: Long) {
        val orderIndex = getMembers(collectionId).size
        insertMember(CollectionMember(collectionId = collectionId, appId = appId, orderIndex = orderIndex))
    }

    /**
     * Persist a new full sequence for a collection atomically: each app id's position in
     * [orderedAppIds] becomes its [CollectionMember.orderIndex]. Ids absent from the list keep
     * their existing index, so a partial reorder never corrupts the rest of the sequence.
     */
    @Transaction
    suspend fun reorderMembers(collectionId: Long, orderedAppIds: List<Long>) {
        orderedAppIds.forEachIndexed { index, appId ->
            setOrderIndex(collectionId, appId, index)
        }
    }
}
