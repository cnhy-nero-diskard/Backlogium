package com.example.backlogium.data.remote

/**
 * Steam's `GetOwnedGames` returns only the bare `img_icon_url` hash. This maps it to a
 * full CDN URL for image loading. Returns an empty string when the hash is absent so
 * callers can fall back to a placeholder.
 */
object SteamIconMapper {

    private const val CDN_BASE =
        "https://media.steampowered.com/steamcommunity/public/images/apps"

    private const val CDN_ROOT = "https://cdn.cloudflare.steamstatic.com"

    private const val STORE_CDN_BASE = "$CDN_ROOT/steam/apps"

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

    /**
     * Resolve an `asset_url_format` from `IStoreBrowseService/GetItems` — a CDN-relative path of
     * the form `steam/apps/440/${'$'}{FILENAME}?t=1757348372` — against one of the asset names in
     * the same block.
     *
     * Preferred over [headerUrl] for a store item because it is the path the store itself reports,
     * cache-busting timestamp included, rather than the well-known one this object otherwise has
     * to assume. Returns an empty string when either half is missing, so callers fall back the
     * same way they do for a missing icon hash.
     */
    fun storeAssetUrl(assetUrlFormat: String?, fileName: String?): String {
        if (assetUrlFormat.isNullOrBlank() || fileName.isNullOrBlank()) return ""
        if (FILENAME_PLACEHOLDER !in assetUrlFormat) return ""
        return "$CDN_ROOT/" + assetUrlFormat.replace(FILENAME_PLACEHOLDER, fileName)
    }

    private const val FILENAME_PLACEHOLDER = "${'$'}{FILENAME}"
}
