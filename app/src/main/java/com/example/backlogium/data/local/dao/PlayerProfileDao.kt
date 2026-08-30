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
            "personaName, avatarUrl, storeRegion, pendingImportRecompute) VALUES " +
            "(0, '', 0, 0, 1, 0, 0, 0, 0, NULL, 0, NULL, NULL, NULL, 0)",
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
            "personaName = :personaName, avatarUrl = :avatarUrl, storeRegion = :storeRegion " +
            "WHERE id = 0",
    )
    suspend fun updateSteamIdentity(
        steamId: String,
        steamLevel: Int,
        personaName: String?,
        avatarUrl: String?,
        storeRegion: String?,
    )

    /** The optional explicitly configured store region, when one exists. */
    @Query("SELECT storeRegion FROM player_profile WHERE id = 0")
    suspend fun storeRegion(): String?

    /**
     * The identity fields the live presence path can observe: the header pair plus any explicit
     * store-region setting retained by the profile.
     *
     * The caller skips the write when the merged identity equals the stored one, keeping the
     * update idempotent and cheap.
     */
    @Query(
        "UPDATE player_profile SET personaName = :personaName, avatarUrl = :avatarUrl, " +
            "storeRegion = :storeRegion WHERE id = 0",
    )
    suspend fun updateHeaderIdentity(
        personaName: String?,
        avatarUrl: String?,
        storeRegion: String?,
    )

    /**
     * Gamification aggregates and the configuration provenance that produced them. Also clears
     * [com.example.backlogium.data.local.entity.PlayerProfile.pendingImportRecompute]: any
     * completed recompute, regardless of source, proves aggregates are back in sync with
     * whatever raw data existed when it ran (auditfix-backup-integrity).
     */
    @Query(
        "UPDATE player_profile SET totalXp = :totalXp, level = :level, " +
            "currentStreak = :currentStreak, longestStreak = MAX(longestStreak, :longestStreak), " +
            "gamificationConfigVersion = :gamificationConfigVersion, pendingImportRecompute = 0 " +
            "WHERE id = 0",
    )
    suspend fun updateGamification(
        totalXp: Int,
        level: Int,
        currentStreak: Int,
        longestStreak: Int,
        gamificationConfigVersion: Long,
    )

    /**
     * Marks that a backup merge's raw-data transaction has committed and the follow-up recompute
     * has not yet run — set as the last write inside that same transaction, so it commits
     * atomically with the merged data (auditfix-backup-integrity).
     */
    @Query("UPDATE player_profile SET pendingImportRecompute = 1 WHERE id = 0")
    suspend fun markPendingImportRecompute()

    /**
     * Raise the longest-streak high-water mark, never lower it.
     *
     * Written *inside* the merge transaction rather than left to the post-commit recompute:
     * `longestStreak` is a historical fact an import can legitimately carry beyond anything the
     * current rules could reconstruct from raw data. If it survived only in the merge's stack
     * frame, a process death in the merge-commit-to-recompute window — precisely what
     * `pendingImportRecompute` recovers from — would leave recovery recomputing from Room with no
     * copy of the imported value, permanently losing it.
     */
    @Query("UPDATE player_profile SET longestStreak = MAX(longestStreak, :longestStreak) WHERE id = 0")
    suspend fun raiseLongestStreak(longestStreak: Int)

    /** Historical-import flag only. */
    @Query("UPDATE player_profile SET playtimeBackfilled = :playtimeBackfilled WHERE id = 0")
    suspend fun updatePlaytimeBackfilled(playtimeBackfilled: Boolean)

    /** Failure reporting must not re-assert a stale copy of any other profile field. */
    @Query("UPDATE player_profile SET lastSyncError = :message WHERE id = 0")
    suspend fun updateLastSyncError(message: String)

    /** Reset account-derived profile state while retaining the active rule configuration version. */
    @Query(
        "UPDATE player_profile SET steamId = :steamId, steamLevel = 0, totalXp = 0, level = 1, " +
            "currentStreak = 0, longestStreak = 0, lastSyncAt = 0, lastSyncError = NULL, " +
            "playtimeBackfilled = 0, personaName = NULL, avatarUrl = NULL, " +
            "storeRegion = NULL, pendingImportRecompute = 0 WHERE id = 0",
    )
    suspend fun resetForAccountChange(steamId: String)
}
