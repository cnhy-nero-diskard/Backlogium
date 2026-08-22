package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.HiddenGame
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the hidden set (add-hidden-games). Hiding inserts, unhiding deletes; no other
 * row in the database is touched by either, which is what makes the operation reversible.
 */
@Dao
interface HiddenGameDao {

    @Upsert
    suspend fun upsertAll(hidden: List<HiddenGame>)

    @Query("SELECT * FROM hidden_games ORDER BY hiddenAt DESC")
    fun observeAll(): Flow<List<HiddenGame>>

    @Query("SELECT * FROM hidden_games ORDER BY hiddenAt DESC")
    suspend fun getAll(): List<HiddenGame>

    /** The hidden set as every exclusion path consumes it. */
    @Query("SELECT appId FROM hidden_games")
    suspend fun hiddenAppIds(): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM hidden_games WHERE appId = :appId)")
    suspend fun isHidden(appId: Long): Boolean

    @Query("DELETE FROM hidden_games WHERE appId IN (:appIds)")
    suspend fun delete(appIds: List<Long>)

    @Query("DELETE FROM hidden_games")
    suspend fun deleteAll()
}
