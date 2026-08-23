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

    /**
     * Insert a new row only; an existing row's app-owned fields are never replaced.
     *
     * [firstSeenAt] is the arrival stamp, and `INSERT OR IGNORE` is what makes "write it exactly
     * once" structural: the statement cannot reach a row that already has one. A baseline poll
     * passes null, which is the positive statement "this game was already here" rather than a
     * missing value — see [com.example.backlogium.data.local.entity.Game.firstSeenAt].
     */
    @Query(
        "INSERT OR IGNORE INTO games " +
            "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
            "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, " +
            "firstSeenAt, lastPlayedAt, returnedToPlayAt) " +
            "VALUES (:appId, :name, :iconUrl, :playtimeForever, :playtime2Weeks, " +
            ":lastPlaytime, 0, NULL, :lastSyncedAt, 0, " +
            ":firstSeenAt, :lastPlayedAt, NULL)",
    )
    suspend fun insertSteamGameIfMissing(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
        firstSeenAt: Long?,
        lastPlayedAt: Long?,
    )

    /**
     * Update only fields for which Steam is authoritative — now including [lastPlayedAt], which
     * mirrors Steam's `rtime_last_played` and is therefore written on every poll, null included.
     *
     * [returnedToPlayAt] is the one parameter here that is *not* Steam-owned: it is this poll's own
     * dormancy verdict, and `COALESCE` is what makes "record a return, never erase one" a property
     * of the statement rather than of its callers — passing null leaves any stored return exactly
     * as it stood. Carrying it in the same `UPDATE` as the playtime it was derived from is also
     * what makes the two impossible to observe partially applied: a return can never be stored
     * without the playtime increase that justified it.
     */
    @Query(
        "UPDATE games SET name = :name, iconUrl = :iconUrl, " +
            "playtimeForever = :playtimeForever, playtime2Weeks = :playtime2Weeks, " +
            "lastPlaytime = :lastPlaytime, lastSyncedAt = :lastSyncedAt, " +
            "lastPlayedAt = :lastPlayedAt, " +
            "returnedToPlayAt = COALESCE(:returnedToPlayAt, returnedToPlayAt) " +
            "WHERE appId = :appId",
    )
    suspend fun updateSteamFields(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
        lastPlayedAt: Long?,
        returnedToPlayAt: Long?,
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

    /** Remove the account-owned library; child rows are cleared explicitly by the reset caller. */
    @Query("DELETE FROM games")
    suspend fun deleteAll()

    /**
     * Restore one existing game's recency timestamps from a backup (add-library-recency-signals).
     *
     * `COALESCE` in the *argument* position, so a value the backup carries is restored and an
     * absence leaves the stored value alone. Both halves matter: an older backup must not erase an
     * arrival this device observed after it was taken, and an import must write nothing beyond what
     * the backup actually recorded.
     *
     * Separate from [updateSteamFields] deliberately — that path is a poll's, and a poll and a
     * restore have opposite rules about `firstSeenAt`.
     */
    @Query(
        "UPDATE games SET " +
            "firstSeenAt = COALESCE(:firstSeenAt, firstSeenAt), " +
            "lastPlayedAt = COALESCE(:lastPlayedAt, lastPlayedAt), " +
            "returnedToPlayAt = COALESCE(:returnedToPlayAt, returnedToPlayAt) " +
            "WHERE appId = :appId",
    )
    suspend fun setRecencyFromBackup(
        appId: Long,
        firstSeenAt: Long?,
        lastPlayedAt: Long?,
        returnedToPlayAt: Long?,
    )

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
