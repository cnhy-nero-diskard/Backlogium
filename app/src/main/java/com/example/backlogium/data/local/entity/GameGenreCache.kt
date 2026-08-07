package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * The last definitive Steam Store genre result for one owned game. An empty [genresJson] is a
 * checked negative result; no row means the game has not been checked yet.
 */
@Entity(
    tableName = "game_genre_cache",
    primaryKeys = ["appId"],
    foreignKeys = [
        ForeignKey(
            entity = Game::class,
            parentColumns = ["appId"],
            childColumns = ["appId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class GameGenreCache(
    val appId: Long,
    val genresJson: String,
    val checkedAt: Long,
)
