package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.Game
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Upsert
    suspend fun upsertAll(games: List<Game>)

    @Upsert
    suspend fun upsert(game: Game)

    /** Insert a new row only; an existing row's app-owned fields are never replaced. */
    @Query(
        "INSERT OR IGNORE INTO games " +
            "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
            "isGoal, targetMinutes, lastSyncedAt, backfillMinutes) " +
            "VALUES (:appId, :name, :iconUrl, :playtimeForever, :playtime2Weeks, " +
            ":lastPlaytime, 0, NULL, :lastSyncedAt, 0)",
    )
    suspend fun insertSteamGameIfMissing(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
    )

    /** Update only fields for which Steam is authoritative. */
    @Query(
        "UPDATE games SET name = :name, iconUrl = :iconUrl, " +
            "playtimeForever = :playtimeForever, playtime2Weeks = :playtime2Weeks, " +
            "lastPlaytime = :lastPlaytime, lastSyncedAt = :lastSyncedAt WHERE appId = :appId",
    )
    suspend fun updateSteamFields(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
    )

    @Query("SELECT * FROM games ORDER BY playtime2Weeks DESC, name ASC")
    fun observeLibrary(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isGoal = 1 ORDER BY name ASC")
    fun observeGoalGames(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isGoal = 0 ORDER BY playtimeForever DESC, name ASC")
    fun observeBacklog(): Flow<List<Game>>

    /** Every owned game's app id — the achievement sync's full-library scope. */
    @Query("SELECT appId FROM games")
    suspend fun allAppIds(): List<Long>

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

    /** Freeze one game's historical playtime offset (opt-in Steam-history import). */
    @Query("UPDATE games SET backfillMinutes = :minutes WHERE appId = :appId")
    suspend fun setBackfillMinutes(appId: Long, minutes: Int)

    /**
     * Persist per-game backfill offsets in a single transaction so the one-time import is
     * applied atomically (either all games get their frozen offset, or none do).
     */
    @Transaction
    suspend fun applyBackfill(minutesByAppId: Map<Long, Int>) {
        minutesByAppId.forEach { (appId, minutes) -> setBackfillMinutes(appId, minutes) }
    }
}
