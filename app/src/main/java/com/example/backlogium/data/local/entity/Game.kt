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
 *
 * The three recency timestamps ([firstSeenAt], [lastPlayedAt], [returnedToPlayAt]) are the stored
 * *observations* the recency states are derived from (add-library-recency-signals). None of them
 * stores a state: whether a game currently shows a badge is arithmetic against a window, so
 * expiry costs no write.
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
    /**
     * When a non-baseline poll first observed this game — "it arrived while we were watching".
     *
     * Null means the game was already present when tracking began: at the library baseline, or
     * before this column existed. That is **not** the same as unknown — it is a positive statement
     * that the game is not new and can never become new, and it is the whole baselining mechanism.
     * A poll writes this exactly once and never overwrites it; a restore never writes it at all
     * beyond the value the backup carried.
     */
    val firstSeenAt: Long? = null,
    /**
     * Steam's `rtime_last_played`, in epoch millis. Steam-owned: refreshed by every poll that
     * observes the game.
     *
     * Null means Steam reported no value — which it does for never-played games and occasionally
     * for very old ones. Never-played is determined from [playtimeForever] being 0, never from
     * this being null, so a game with 40 hours and no timestamp reads "last played: unknown"
     * rather than "never played".
     */
    val lastPlayedAt: Long? = null,
    /**
     * When play resumed after a dormant period, in event time — the play's own time, not the
     * poll's. Recorded by the poll that observes the return, because the poll's own update to
     * [lastPlayedAt] destroys the only evidence that there was a gap.
     */
    val returnedToPlayAt: Long? = null,
)
