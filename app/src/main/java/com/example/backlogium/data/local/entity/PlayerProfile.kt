package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row profile aggregate. [id] is always [SINGLETON_ID] so the table holds exactly
 * one row. Stores the gamification engine's persisted outputs plus sync status.
 */
@Entity(tableName = "player_profile")
data class PlayerProfile(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val steamId: String = "",
    val steamLevel: Int = 0,
    val totalXp: Int = 0,
    val level: Int = 1,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    /** Rule-config version that produced the stored derived values above. */
    val gamificationConfigVersion: Long = 0L,
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
    /** True once the player has opted in to importing historical Steam playtime (one-time). */
    val playtimeBackfilled: Boolean = false,
    /**
     * Steam persona name, persisted so the profile header renders on a cold offline launch.
     * Null until the first sync (or live poll) observes it.
     */
    val personaName: String? = null,
    /** Full-size Steam avatar URL, persisted for the same reason as [personaName]. */
    val avatarUrl: String? = null,
    /** Optional explicit Steam Store Country override; public profile location is not stored here. */
    val storeRegion: String? = null,
    /**
     * True from the moment a backup merge's raw-data transaction commits until the following
     * gamification recompute finalizes (auditfix-backup-integrity). Room and the recompute's own
     * write-ahead protocol are two different storage engines with no shared transaction, so a
     * crash in between would otherwise leave aggregates silently describing the pre-import state
     * with no record that anything is wrong. Set inside the merge's transaction so it commits
     * atomically with the raw data; cleared by the next completed recompute regardless of source.
     */
    val pendingImportRecompute: Boolean = false,
    /** The last successful wishlist membership read; null until Steam has answered successfully. */
    val lastSuccessfulWishlistReadAt: Long? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
