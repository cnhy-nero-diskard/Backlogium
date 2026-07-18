package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Steam-owned game. [playtimeForever] is the total tracked by Steam (used for goal
 * progress), while [lastPlaytime] is the value stored at the previous poll (the diff
 * baseline used to synthesize sessions).
 */
@Entity(tableName = "games")
data class Game(
    @PrimaryKey val appId: Long,
    val name: String,
    val iconUrl: String,
    val playtimeForever: Int,
    val playtime2Weeks: Int,
    val lastPlaytime: Int,
    val isGoal: Boolean = false,
    val targetMinutes: Int? = null,
    val lastSyncedAt: Long = 0L,
)
