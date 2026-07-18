package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.PlayerProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface PlayerProfileDao {

    @Upsert
    suspend fun upsert(profile: PlayerProfile)

    @Query("SELECT * FROM player_profile WHERE id = 0")
    fun observe(): Flow<PlayerProfile?>

    @Query("SELECT * FROM player_profile WHERE id = 0")
    suspend fun get(): PlayerProfile?
}
