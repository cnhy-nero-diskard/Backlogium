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
    val lastSyncAt: Long = 0L,
    val lastSyncError: String? = null,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}
