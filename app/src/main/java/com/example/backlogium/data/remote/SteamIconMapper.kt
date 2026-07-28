package com.example.backlogium.data.remote

/**
 * Steam's `GetOwnedGames` returns only the bare `img_icon_url` hash. This maps it to a
 * full CDN URL for image loading. Returns an empty string when the hash is absent so
 * callers can fall back to a placeholder.
 */
object SteamIconMapper {

    private const val CDN_BASE =
        "https://media.steampowered.com/steamcommunity/public/images/apps"

    private const val STORE_CDN_BASE = "https://cdn.cloudflare.steamstatic.com/steam/apps"

    fun iconUrl(appId: Long, imgIconHash: String): String {
        if (imgIconHash.isBlank()) return ""
        return "$CDN_BASE/$appId/$imgIconHash.jpg"
    }

    /**
     * The store header image (460×215), used as a faint card backdrop in the Library.
     *
     * Derived from the appId alone — Steam serves it at a well-known path, so nothing has to be
     * fetched or stored for it. Not every app has one (delisted titles, some tools); the image
     * loader simply renders nothing when it 404s, which is the intended fallback.
     */
    fun headerUrl(appId: Long): String = "$STORE_CDN_BASE/$appId/header.jpg"
}
