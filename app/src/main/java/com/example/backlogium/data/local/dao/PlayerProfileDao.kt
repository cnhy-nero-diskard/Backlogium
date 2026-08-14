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

    /** Create the singleton row without replacing fields another writer may already own. */
    @Query(
        "INSERT OR IGNORE INTO player_profile " +
            "(id, steamId, steamLevel, totalXp, level, currentStreak, longestStreak, " +
            "gamificationConfigVersion, lastSyncAt, lastSyncError, playtimeBackfilled, " +
            "personaName, avatarUrl) VALUES " +
            "(0, '', 0, 0, 1, 0, 0, 0, 0, NULL, 0, NULL, NULL)",
    )
    suspend fun insertIfMissing()

    @Query("SELECT * FROM player_profile WHERE id = 0")
    fun observe(): Flow<PlayerProfile?>

    @Query("SELECT * FROM player_profile WHERE id = 0")
    suspend fun get(): PlayerProfile?

    /** Sync status fields only; identity and derived aggregates remain untouched. */
    @Query(
        "UPDATE player_profile SET lastSyncAt = MAX(lastSyncAt, :lastSyncAt), " +
            "lastSyncError = :lastSyncError WHERE id = 0",
    )
    suspend fun updateSyncStatus(lastSyncAt: Long, lastSyncError: String?)

    /** Steam identity fields only; sync status and derived aggregates remain untouched. */
    @Query(
        "UPDATE player_profile SET steamId = :steamId, steamLevel = :steamLevel, " +
            "personaName = :personaName, avatarUrl = :avatarUrl WHERE id = 0",
    )
    suspend fun updateSteamIdentity(
        steamId: String,
        steamLevel: Int,
        personaName: String?,
        avatarUrl: String?,
    )

    /** Header identity only, used by the live presence path. */
    @Query(
        "UPDATE player_profile SET personaName = :personaName, avatarUrl = :avatarUrl WHERE id = 0",
    )
    suspend fun updateHeaderIdentity(personaName: String?, avatarUrl: String?)

    /** Gamification aggregates and the configuration provenance that produced them. */
    @Query(
        "UPDATE player_profile SET totalXp = :totalXp, level = :level, " +
            "currentStreak = :currentStreak, longestStreak = MAX(longestStreak, :longestStreak), " +
            "gamificationConfigVersion = :gamificationConfigVersion WHERE id = 0",
    )
    suspend fun updateGamification(
        totalXp: Int,
        level: Int,
        currentStreak: Int,
        longestStreak: Int,
        gamificationConfigVersion: Long,
    )

    /** Historical-import flag only. */
    @Query("UPDATE player_profile SET playtimeBackfilled = :playtimeBackfilled WHERE id = 0")
    suspend fun updatePlaytimeBackfilled(playtimeBackfilled: Boolean)

    /** Failure reporting must not re-assert a stale copy of any other profile field. */
    @Query("UPDATE player_profile SET lastSyncError = :message WHERE id = 0")
    suspend fun updateLastSyncError(message: String)
}
