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
 *
 * [categoriesJson] carries the same response's participation categories, which the multiplayer
 * classification reads (add-gap-plan-suggestions). It is deliberately **nullable and distinct from
 * an empty encoded list**: null means the categories were never retrieved — a row written before
 * they were recorded, or a response the Store declined to answer — while an encoded empty list
 * means the Store answered and this app advertises none. Only the second may be read as
 * single-player, the same distinction [appType] already makes for app kind.
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
    val categoriesJson: String? = null,
    /** When non-null, the Store refused the category part of this check and it is cooling down. */
    val categoriesDeclinedAt: Long? = null,
)
