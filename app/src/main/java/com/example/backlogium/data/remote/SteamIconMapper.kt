package com.example.backlogium.data.remote

/**
 * Steam's `GetOwnedGames` returns only the bare `img_icon_url` hash. This maps it to a
 * full CDN URL for image loading. Returns an empty string when the hash is absent so
 * callers can fall back to a placeholder.
 */
object SteamIconMapper {

    private const val CDN_BASE =
        "https://media.steampowered.com/steamcommunity/public/images/apps"

    fun iconUrl(appId: Long, imgIconHash: String): String {
        if (imgIconHash.isBlank()) return ""
        return "$CDN_BASE/$appId/$imgIconHash.jpg"
    }
}
