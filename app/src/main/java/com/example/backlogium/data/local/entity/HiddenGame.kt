package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One hidden library item, keyed by app id (add-hidden-games). A row's presence *is* the hide:
 * hiding writes one, unhiding deletes it, and nothing else is stored or removed anywhere.
 *
 * **Why a table rather than a `Game.hidden` column.** `SteamSyncWorker` does not update `games`
 * rows in place — it rebuilds each one from the Steam DTO and copies the app-owned fields
 * (`isGoal`, `targetMinutes`, `backfillMinutes`) back by hand. A flag on `Game` therefore
 * survives only as long as someone remembers that line, and forgetting it silently reverts the
 * player's hide on the next sync: exactly the failure [Game]'s own `backfillMinutes` comment
 * records having already happened once. A separate table cannot be clobbered by a rebuild it is
 * not part of.
 *
 * There is deliberately **no foreign key to `games`**. A game that leaves the library — refunded,
 * delisted, or dropped from a family share — should still be hidden if it comes back, and a hide
 * must not be cascade-deleted by an ownership event that says nothing about the player's intent.
 *
 * [fromBulkAction] records that this row came from the non-game bulk review rather than from an
 * individual hide, so the hidden list can say where a hide came from; it never changes how the
 * hide behaves, and each bulk-hidden item is unhidden individually like any other.
 */
@Entity(tableName = "hidden_games")
data class HiddenGame(
    @PrimaryKey val appId: Long,
    val hiddenAt: Long,
    val fromBulkAction: Boolean = false,
)
