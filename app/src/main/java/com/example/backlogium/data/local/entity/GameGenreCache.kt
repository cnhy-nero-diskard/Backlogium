package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * The last definitive Steam Store result for one owned game. An empty [genresJson] is a checked
 * negative result; no row means the game has not been checked yet.
 *
 * [appType] is the store's own kind for the app, carried by the same response as the genres. It is
 * null both for rows written before it was recorded and for responses that omitted it — either way
 * the type is *unknown*, and the non-game review never offers an app whose type it does not know
 * (add-hidden-games).
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
    val appType: String? = null,
)
