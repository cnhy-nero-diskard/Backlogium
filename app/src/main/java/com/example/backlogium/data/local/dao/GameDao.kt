package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.Game
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Upsert
    suspend fun upsertAll(games: List<Game>)

    @Upsert
    suspend fun upsert(game: Game)

    @Query("SELECT * FROM games ORDER BY playtime2Weeks DESC, name ASC")
    fun observeLibrary(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isGoal = 1 ORDER BY name ASC")
    fun observeGoalGames(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isGoal = 0 ORDER BY playtimeForever DESC, name ASC")
    fun observeBacklog(): Flow<List<Game>>

    @Query("SELECT * FROM games")
    suspend fun getAll(): List<Game>

    @Query("SELECT * FROM games WHERE appId = :appId")
    suspend fun getById(appId: Long): Game?

    @Query("UPDATE games SET isGoal = :isGoal, targetMinutes = :targetMinutes WHERE appId = :appId")
    suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?)

    /** Toggle only the goal flag, leaving the dormant targetMinutes column untouched. */
    @Query("UPDATE games SET isGoal = :isGoal WHERE appId = :appId")
    suspend fun setGoalFlag(appId: Long, isGoal: Boolean)

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int
}
