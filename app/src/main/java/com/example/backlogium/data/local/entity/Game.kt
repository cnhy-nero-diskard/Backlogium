package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.backlogium.domain.GameSource

/**
 * A tracked game. [playtimeForever] is the total tracked by Steam (used for goal
 * progress), while [lastPlaytime] is the value stored at the previous poll (the diff
 * baseline used to synthesize sessions).
 *
 * [backfillMinutes] is the frozen historical playtime captured when the player opts in to
 * importing Steam history (0 = not imported). Recompute adds it to tracked session minutes so
 * pre-install hours count toward XP once, without re-importing Steam's growing lifetime total.
 * This is a whole-library, owned-game mechanism; it is independent of [manualSharedMinutes].
 *
 * [manualSharedMinutes] is the player's own hours-played estimate for a `FAMILY_SHARED` game,
 * additive with its tracked session minutes everywhere playtime is consumed (XP, derived
 * collections, completion progress, display). Freely re-editable, unlike [backfillMinutes] —
 * setting it again replaces the stored value rather than accumulating. Always 0 for a
 * `STEAM_OWNED` game; write paths guard this in SQL as well
 * (`GameDao.setManualSharedMinutes`) so an unrelated write can never populate it for an owned row
 * (fix-shared-game-achievement-visibility follow-up, add-shared-game-playtime-and-filter).
 *
 * [source] states how the app came to track the game. `STEAM_OWNED` is the only value Steam's
 * owned-games sync ever writes; `FAMILY_SHARED` rows are admitted from observed presence and have
 * no Steam-reported playtime at all, so [playtimeForever] and [lastPlaytime] stay at 0 for them
 * and their sessions come from the presence deriver instead of playtime diffing.
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
    val source: GameSource = GameSource.STEAM_OWNED,
    val firstSeenAt: Long? = null,
    val lastPlayedAt: Long? = null,
    val returnedToPlayAt: Long? = null,
    val manualSharedMinutes: Int = 0,
)
