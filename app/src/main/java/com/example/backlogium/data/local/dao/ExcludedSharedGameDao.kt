package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import kotlinx.coroutines.flow.Flow

@Dao
interface ExcludedSharedGameDao {

    @Upsert
    suspend fun upsert(row: ExcludedSharedGame)

    @Query("SELECT * FROM excluded_shared_games ORDER BY excludedAt DESC")
    fun observeAll(): Flow<List<ExcludedSharedGame>>

    @Query("SELECT EXISTS(SELECT 1 FROM excluded_shared_games WHERE appId = :appId)")
    suspend fun isExcluded(appId: Long): Boolean

    @Query("DELETE FROM excluded_shared_games WHERE appId = :appId")
    suspend fun delete(appId: Long)

    /** Cleared with the library on an account reset: exclusions are that account's decisions. */
    @Query("DELETE FROM excluded_shared_games")
    suspend fun deleteAll()
}
