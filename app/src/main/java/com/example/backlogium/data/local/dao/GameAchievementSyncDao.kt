package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.GameAchievementSync
import kotlinx.coroutines.flow.Flow

@Dao
interface GameAchievementSyncDao {
    @Query("SELECT * FROM game_achievement_sync WHERE appId = :appId")
    suspend fun get(appId: Long): GameAchievementSync?

    @Query("SELECT * FROM game_achievement_sync WHERE appId IN (:appIds)")
    suspend fun getAll(appIds: Set<Long>): List<GameAchievementSync>

    @Query("SELECT * FROM game_achievement_sync")
    fun observeAll(): Flow<List<GameAchievementSync>>

    @Upsert
    suspend fun upsert(row: GameAchievementSync)

    @Upsert
    suspend fun upsertAll(rows: List<GameAchievementSync>)

    @Query("DELETE FROM game_achievement_sync")
    suspend fun deleteAll()

    @Query("DELETE FROM game_achievement_sync WHERE appId = :appId")
    suspend fun delete(appId: Long)
}
