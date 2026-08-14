package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * A Steam achievement for a game, keyed by ([appId], [apiName]). [unlocked]/[unlockedAt]/
 * [globalPercent] are refreshed on every sync; [snapshotPercent] is the global unlock percent
 * captured the first sync that observed the achievement unlocked, and is never overwritten
 * afterward — it, not the live [globalPercent], drives the engine's rarity/XP (see the
 * add-steam-achievements design's rarity-drift policy).
 *
 * [description] and [hidden] come from the achievement schema (enhance-game-detail). [description]
 * is nullable and not backfilled: rows stored before it was retained keep null until their game's
 * next natural schema fetch, and Steam withholds descriptions for [hidden] achievements the player
 * has not unlocked yet. [retired] is a reversible tombstone used only by full reconciliation when
 * Steam no longer returns a previously stored achievement; the row and rarity snapshot remain.
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
    val description: String? = null,
    val hidden: Boolean = false,
    val retired: Boolean = false,
    val fetchedAt: Long,
)
