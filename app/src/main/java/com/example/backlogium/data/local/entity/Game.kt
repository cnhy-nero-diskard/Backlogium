package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Steam-owned game. [playtimeForever] is the total tracked by Steam (used for goal
 * progress), while [lastPlaytime] is the value stored at the previous poll (the diff
 * baseline used to synthesize sessions).
 *
 * [backfillMinutes] is the frozen historical playtime captured when the player opts in to
 * importing Steam history (0 = not imported). Recompute adds it to tracked session minutes so
 * pre-install hours count toward XP once, without re-importing Steam's growing lifetime total.
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
    val backfillMinutes: Int = 0,
)
