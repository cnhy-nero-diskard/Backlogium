package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Sentinel [Achievement.apiName] recorded when a fetch succeeds but the game defines no
 * achievements at all. Lets the freshness gate treat the game as checked (not stale/missing)
 * without a real achievement row; filtered out of every display/count query.
 */
const val NO_ACHIEVEMENTS_MARKER = "__no_achievements__"

/**
 * A Steam achievement for a game, keyed by ([appId], [apiName]). [unlocked]/[unlockedAt]/
 * [globalPercent] are refreshed on every sync; [snapshotPercent] is the global unlock percent
 * captured the first sync that observed the achievement unlocked, and is never overwritten
 * afterward — it, not the live [globalPercent], drives the engine's rarity/XP (see the
 * add-steam-achievements design's rarity-drift policy).
 */
@Entity(
    tableName = "achievements",
    primaryKeys = ["appId", "apiName"],
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class Achievement(
    val appId: Long,
    val apiName: String,
    val displayName: String? = null,
    val iconUrl: String? = null,
    val unlocked: Boolean = false,
    val unlockedAt: Long? = null,
    val globalPercent: Double? = null,
    val snapshotPercent: Double? = null,
    val fetchedAt: Long,
)
