package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.GamePreference
import kotlinx.coroutines.flow.Flow

@Dao
interface GamePreferenceDao {
    @Query("SELECT * FROM game_preferences ORDER BY appId")
    fun observeAll(): Flow<List<GamePreference>>

    @Query("SELECT * FROM game_preferences ORDER BY appId")
    suspend fun getAll(): List<GamePreference>

    @Query("SELECT * FROM game_preferences WHERE appId = :appId")
    fun observe(appId: Long): Flow<GamePreference?>

    @Query("SELECT * FROM game_preferences WHERE appId = :appId")
    suspend fun get(appId: Long): GamePreference?

    @Upsert
    suspend fun upsert(preference: GamePreference)

    @Query("DELETE FROM game_preferences")
    suspend fun deleteAll()
}
