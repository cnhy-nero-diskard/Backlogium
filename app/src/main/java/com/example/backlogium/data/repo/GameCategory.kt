package com.example.backlogium.data.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One Steam Store *participation category* — "Multi-player", "Online Co-op", "MMO", …
 *
 * Deliberately a separate type from [GameGenre] even though both arrive in the same `appdetails`
 * response. A category describes how a game is played; a genre describes what kind of game it is.
 * Substituting one for the other would put "Multi-player" into genre affinity and filtering, where
 * it is not a taste signal at all (add-gap-plan-suggestions).
 *
 * [id] is an `Int` rather than [GameGenre]'s `String` because that is the shape Steam actually
 * sends: the `genres` array's `id` arrives as a JSON string, the `categories` array's as a number.
 */
@Serializable
data class GameCategory(
    val id: Int,
    val label: String,
)

/**
 * The participation categories the app recognises, pinned by numeric id from captured
 * `appdetails` responses (see `src/test/resources/.../store/README.md`).
 *
 * **Ids, never labels.** The Store request already sends `l=english`, and every label in the
 * response is translated with it. A label-matched classification would therefore classify nothing
 * at all the moment that parameter changed — silently, because "no category matched" is
 * indistinguishable from "this game is single-player". Ids are stable across languages.
 */
object ParticipationCategories {
    /** Steam category `1`, "Multi-player". Pinned from Dota 2 (570) and Left 4 Dead 2 (550). */
    const val MULTI_PLAYER = 1

    /** Steam category `20`, "MMO". Pinned from Path of Exile (238960). */
    const val MASSIVELY_MULTIPLAYER = 20

    /** Steam category `38`, "Online Co-op". Pinned from Deep Rock Galactic (548430). */
    const val ONLINE_CO_OP = 38

    /**
     * The exact set that makes a game eligible for optional live current-player decoration.
     *
     * Local-only "Co-op" (`9`) and "Online PvP" (`36`) are deliberately absent: the spec names
     * multiplayer, online co-op, and massively multiplayer, and a concurrent-player count is only
     * a meaningful fact for a game whose players share a server population.
     */
    val LIVE_ENRICHMENT_ELIGIBLE: Set<Int> = setOf(
        MULTI_PLAYER,
        MASSIVELY_MULTIPLAYER,
        ONLINE_CO_OP,
    )
}

/**
 * True when these categories advertise a participation mode worth a live player-count lookup.
 *
 * Called only on a *known* category list. A null list — never retrieved — is not single-player and
 * must not reach this function; callers branch on the null first.
 */
fun List<GameCategory>.advertisesMultiplayer(): Boolean =
    any { it.id in ParticipationCategories.LIVE_ENRICHMENT_ELIGIBLE }

/** Isolates cache serialization so malformed persisted data affects only its own game. */
object GameCategoryCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(categories: List<GameCategory>): String =
        json.encodeToString(ListSerializer(GameCategory.serializer()), categories)

    /**
     * Null in, null out: an absent payload means *unknown*, and must never decode to the empty
     * list that means "checked, advertises none". A payload that is present but unreadable is
     * treated as unknown for the same reason — a decoding failure is not an answer about the game.
     */
    fun decodeOrNull(payload: String?): List<GameCategory>? {
        if (payload == null) return null
        return runCatching {
            json.decodeFromString(ListSerializer(GameCategory.serializer()), payload)
                .mapNotNull { category ->
                    category.label.trim().takeIf(String::isNotEmpty)?.let { label ->
                        GameCategory(category.id, label)
                    }
                }
        }.getOrNull()
    }
}
