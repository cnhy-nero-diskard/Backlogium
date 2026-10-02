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
            "personaName, avatarUrl, storeRegion, pendingImportRecompute, " +
            "lastSuccessfulWishlistReadAt, pendingXpIntegrityCorrection, " +
            "confirmedLibrarySteamId, confirmedLibraryAt, pendingImportRecomputeSource, " +
            "pendingImportRecomputeSteamId, pendingImportRecomputeRequestId) VALUES " +
            "(0, '', 0, 0, 1, 0, 0, 0, 0, NULL, 0, NULL, NULL, NULL, 0, NULL, 0, NULL, NULL, " +
            "NULL, NULL, NULL)",
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

    @Query("SELECT lastSuccessfulWishlistReadAt FROM player_profile WHERE id = 0")
    suspend fun lastSuccessfulWishlistReadAt(): Long?

    /** Record a successful wishlist read without replacing any other profile-owned fields. */
    @Query(
        "UPDATE player_profile SET lastSuccessfulWishlistReadAt = " +
            "MAX(COALESCE(lastSuccessfulWishlistReadAt, 0), :readAt) WHERE id = 0",
    )
    suspend fun updateLastSuccessfulWishlistReadAt(readAt: Long)

    /** Invalidate wishlist membership freshness after a read that did not succeed. */
    @Query("UPDATE player_profile SET lastSuccessfulWishlistReadAt = NULL WHERE id = 0")
    suspend fun clearLastSuccessfulWishlistReadAt()

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
     * Gamification aggregates and the configuration provenance that produced them. The pending
     * import marker and its provenance are deliberately **not** cleared here: a completed recompute
     * only proves its own protocol finalized once its progress-event marks succeed, so the marker
     * survives the Room write and is cleared by
     * [clearPendingImportRecomputeIfMatches] as the last step of a successful whole-protocol
     * finalization (stabilize-first-run-setup, task 5.6). Clearing it here would let a crash
     * between the Room write and the marks finalize lose the BACKFILL/RESTORE provenance recovery
     * needs.
     */
    @Query(
        "UPDATE player_profile SET totalXp = :totalXp, level = :level, " +
            "currentStreak = :currentStreak, longestStreak = MAX(longestStreak, :longestStreak), " +
            "gamificationConfigVersion = :gamificationConfigVersion, pendingXpIntegrityCorrection = 0 " +
            "WHERE id = 0",
    )
    suspend fun updateGamification(
        totalXp: Long,
        level: Int,
        currentStreak: Int,
        longestStreak: Int,
        gamificationConfigVersion: Long,
    )

    /**
     * Clears the pending recompute marker and its provenance as one atomic, field-scoped unit, only
     * when the marker still carries exactly the given source/account/request. A newer raw
     * transaction that replaced the marker (a re-import, or a backup merge landing while an import's
     * recomputation was in flight) is never cleared by the older protocol's finalizer — the
     * comparison makes the finalizer incapable of erasing a newer pending with a stale write.
     *
     * The `IS NULL` comparisons handle legacy markers whose provenance predates the columns.
     */
    @Query(
        "UPDATE player_profile SET pendingImportRecompute = 0, " +
            "pendingImportRecomputeSource = NULL, pendingImportRecomputeSteamId = NULL, " +
            "pendingImportRecomputeRequestId = NULL " +
            "WHERE id = 0 AND pendingImportRecompute = 1 " +
            "AND pendingImportRecomputeSource IS :source " +
            "AND pendingImportRecomputeSteamId IS :steamId " +
            "AND pendingImportRecomputeRequestId IS :requestId",
    )
    suspend fun clearPendingImportRecomputeIfMatches(
        source: String?,
        steamId: String?,
        requestId: String?,
    )

    /**
     * Marks that a raw-data transaction (a backup merge or an explicit history import) has
     * committed and the follow-up recompute has not yet run — set as the last write inside that
     * same transaction, so it commits atomically with the committed data (auditfix-backup-integrity;
     * stabilize-first-run-setup).
     *
     * [source] records the recompute provenance ("RESTORE" for a backup merge, "BACKFILL" for an
     * explicit history import) and [steamId] + [requestId] attribute it to the account/consent
     * that produced it, so recovery and every marker-clearing derived writer present it as
     * administrative rather than earned progress and never run a previous account's marker over
     * the replacement account.
     */
    @Query(
        "UPDATE player_profile SET pendingImportRecompute = 1, " +
            "pendingImportRecomputeSource = :source, " +
            "pendingImportRecomputeSteamId = :steamId, " +
            "pendingImportRecomputeRequestId = :requestId WHERE id = 0",
    )
    suspend fun markPendingImportRecompute(
        source: String,
        steamId: String?,
        requestId: String?,
    )

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

    /**
     * Record explicit confirmed-baseline evidence for the active account, scoped to the two
     * confirmation fields only — never a whole-profile upsert (stabilize-first-run-setup).
     *
     * Callers write this inside the same Room transaction that commits an accepted owned-library
     * response (including an explicitly confirmed empty library), so a crash before that commit
     * leaves both columns NULL and the baseline unconfirmed. A later failed refresh must not call
     * this, preserving the existing confirmation.
     */
    @Query(
        "UPDATE player_profile SET confirmedLibrarySteamId = :steamId, " +
            "confirmedLibraryAt = :confirmedAt WHERE id = 0",
    )
    suspend fun updateLibraryConfirmation(steamId: String, confirmedAt: Long)

    /** Reset account-derived profile state while retaining the active rule configuration version. */
    @Query(
        "UPDATE player_profile SET steamId = :steamId, steamLevel = 0, totalXp = 0, level = 1, " +
            "currentStreak = 0, longestStreak = 0, lastSyncAt = 0, lastSyncError = NULL, " +
            "playtimeBackfilled = 0, personaName = NULL, avatarUrl = NULL, " +
            "storeRegion = NULL, pendingImportRecompute = 0, " +
            "pendingImportRecomputeSource = NULL, pendingImportRecomputeSteamId = NULL, " +
            "pendingImportRecomputeRequestId = NULL, " +
            "lastSuccessfulWishlistReadAt = NULL, pendingXpIntegrityCorrection = 0, " +
            "confirmedLibrarySteamId = NULL, confirmedLibraryAt = NULL WHERE id = 0",
    )
    suspend fun resetForAccountChange(steamId: String)
}
