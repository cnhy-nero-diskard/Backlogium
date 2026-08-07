package com.example.backlogium.data.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A broad Steam Store genre, retaining Store identity and source order. */
@Serializable
data class GameGenre(
    val id: String,
    val label: String,
)

/** Isolates cache serialization so malformed persisted data affects only its own game. */
object GameGenreCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(genres: List<GameGenre>): String =
        json.encodeToString(ListSerializer(GameGenre.serializer()), genres)

    fun decodeOrEmpty(payload: String): List<GameGenre> = runCatching {
        json.decodeFromString(ListSerializer(GameGenre.serializer()), payload)
            .mapNotNull { genre ->
                genre.id.trim().takeIf(String::isNotEmpty)?.let { id ->
                    genre.label.trim().takeIf(String::isNotEmpty)?.let { label ->
                        GameGenre(id, label)
                    }
                }
            }
    }.getOrDefault(emptyList())
}
