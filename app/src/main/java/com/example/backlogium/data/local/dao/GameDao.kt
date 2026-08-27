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
     * Insert a new row only; an existing row's app-owned fields are never replaced. Reached only
     * from the owned-games sync, so the source is written as owned literally rather than left to
     * the column default — a game Steam reports in the library is owned by definition.
     */
    @Query(
        "INSERT OR IGNORE INTO games " +
            "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
            "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source, " +
            "firstSeenAt, lastPlayedAt, returnedToPlayAt) " +
            "VALUES (:appId, :name, :iconUrl, :playtimeForever, :playtime2Weeks, " +
            ":lastPlaytime, 0, NULL, :lastSyncedAt, 0, 'STEAM_OWNED', " +
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

    /** Update only fields for which Steam is authoritative. */
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

    /** Update periodic-poll recency without reimplementing the shared playtime commit. */
    @Query(
        "UPDATE games SET " +
            "firstSeenAt = COALESCE(firstSeenAt, :firstSeenAt), " +
            "lastPlayedAt = :lastPlayedAt, " +
            "returnedToPlayAt = COALESCE(:returnedToPlayAt, returnedToPlayAt) " +
            "WHERE appId = :appId",
    )
    suspend fun updateRecencyFields(
        appId: Long,
        firstSeenAt: Long?,
        lastPlayedAt: Long?,
        returnedToPlayAt: Long?,
    )

    @Query("SELECT * FROM games ORDER BY playtime2Weeks DESC, name ASC")
    fun observeLibrary(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isGoal = 1 ORDER BY name ASC")
    fun observeGoalGames(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE isGoal = 0 ORDER BY playtimeForever DESC, name ASC")
    fun observeBacklog(): Flow<List<Game>>

    /** Every tracked game's app id — the achievement sync's full-library scope. */
    @Query("SELECT appId FROM games")
    suspend fun allAppIds(): List<Long>

    /**
     * Insert a family-shared game admitted from observed presence. `INSERT OR IGNORE`, so a second
     * observation of an already-admitted game is a no-op rather than a duplicate or an overwrite.
     *
     * Playtime columns stay at 0: Steam reports no `playtime_forever` for a borrowed game, and a
     * zero here is the literal truth rather than a placeholder. The row is deliberately absent from
     * the diffing scope ([ownedGamesForDiffing]) for exactly that reason.
     */
    @Query(
        "INSERT OR IGNORE INTO games " +
            "(appId, name, iconUrl, playtimeForever, playtime2Weeks, lastPlaytime, " +
            "isGoal, targetMinutes, lastSyncedAt, backfillMinutes, source) " +
            "VALUES (:appId, :name, :iconUrl, 0, 0, 0, 0, NULL, :admittedAt, 0, 'FAMILY_SHARED')",
    )
    suspend fun insertSharedGameIfMissing(
        appId: Long,
        name: String,
        iconUrl: String,
        admittedAt: Long,
    )

    /**
     * The playtime-diffing scope: only games for which Steam reports playtime. Feeding
     * [com.example.backlogium.domain.SessionDiffer] from this query rather than from every row is
     * what keeps the two session mechanisms partitioned by wiring instead of by a runtime check
     * somewhere inside the differ.
     */
    @Query("SELECT * FROM games WHERE source = 'STEAM_OWNED'")
    suspend fun ownedGamesForDiffing(): List<Game>

    /** The presence-derivation scope: games with no Steam-reported playtime to diff. */
    @Query("SELECT * FROM games WHERE source = 'FAMILY_SHARED'")
    suspend fun sharedGames(): List<Game>

    /**
     * Convert an admitted shared game to owned, storing the reported lifetime total as the diffing
     * baseline. Both playtime columns and `lastPlaytime` move together so the next poll sees no
     * delta — the hours played while borrowing are already recorded as sessions, and diffing them
     * again would synthesize one enormous phantom session over time already counted. This mirrors
     * first-sync baselining, and the game's existing sessions are deliberately untouched.
     */
    @Query(
        "UPDATE games SET source = 'STEAM_OWNED', playtimeForever = :playtimeForever, " +
            "playtime2Weeks = :playtime2Weeks, lastPlaytime = :playtimeForever, " +
            "lastSyncedAt = :convertedAt WHERE appId = :appId AND source = 'FAMILY_SHARED'",
    )
    suspend fun convertSharedToOwned(
        appId: Long,
        playtimeForever: Int,
        playtime2Weeks: Int,
        convertedAt: Long,
    ): Int

    /** Remove one game row; child rows cascade. Used only for removing a family-shared game. */
    @Query("DELETE FROM games WHERE appId = :appId AND source = 'FAMILY_SHARED'")
    suspend fun deleteSharedGame(appId: Long): Int

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

    /** Restore only recency values carried by a backup; absent values never erase local facts. */
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
