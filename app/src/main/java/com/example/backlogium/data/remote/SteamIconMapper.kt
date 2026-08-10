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

    /** Steam's portrait grid artwork, served as the well-known `hero_capsule.jpg` asset. */
    fun heroCapsuleUrl(appId: Long): String = "$STORE_CDN_BASE/$appId/hero_capsule.jpg"

    /** Steam's wide library background, used as the first fallback for both card surfaces. */
    fun libraryHeroUrl(appId: Long): String = "$STORE_CDN_BASE/$appId/library_hero.jpg"

    /** Steam's portrait library artwork, useful when the preferred grid asset is unavailable. */
    fun libraryCapsuleUrl(appId: Long): String = "$STORE_CDN_BASE/$appId/library_600x900.jpg"

    /** Steam's wide store capsule, useful when a horizontal background is unavailable. */
    fun wideCapsuleUrl(appId: Long): String = "$STORE_CDN_BASE/$appId/capsule_616x353.jpg"

    /**
     * Fallbacks after the preferred `header.jpg` background for horizontal cards. `library_hero`
     * intentionally comes first so a missing header still gets a full background photo.
     */
    fun listBackgroundFallbackUrls(appId: Long): List<String> = listOf(
        libraryHeroUrl(appId),
        wideCapsuleUrl(appId),
        heroCapsuleUrl(appId),
        libraryCapsuleUrl(appId),
    )

    /**
     * Fallbacks after the preferred portrait `hero_capsule.jpg` for grid tiles. The wide library
     * hero is attempted first as requested, then portrait and wide store assets preserve coverage.
     */
    fun gridArtworkFallbackUrls(appId: Long): List<String> = listOf(
        libraryHeroUrl(appId),
        libraryCapsuleUrl(appId),
        headerUrl(appId),
        wideCapsuleUrl(appId),
    )
}
