package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An app id the player removed after it had been admitted as family-shared. Its presence here is
 * what makes removal sticky: admission consults this table before creating a game row, so a
 * removed title is not re-admitted the next time it is observed being played.
 *
 * Deliberately not a foreign key to `games` — the row exists precisely because the game row does
 * not. [name] is carried so Settings can list what was removed without a game row to read it from.
 */
@Entity(tableName = "excluded_shared_games")
data class ExcludedSharedGame(
    @PrimaryKey val appId: Long,
    val name: String,
    val excludedAt: Long,
)
