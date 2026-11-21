package com.example.backlogium.domain

/** Bounded app-owned cover choice. Persist tokens, never arbitrary image URLs. */
enum class GameArtworkVariant {
    HEADER, LIBRARY_HERO, WIDE_CAPSULE, HERO_CAPSULE, LIBRARY_CAPSULE;

    companion object {
        fun fromToken(token: String?): GameArtworkVariant? = entries.firstOrNull { it.name == token }
    }
}

data class GameArtworkPreference(
    val appId: Long,
    val variant: GameArtworkVariant?,
    val steamId: String,
)
