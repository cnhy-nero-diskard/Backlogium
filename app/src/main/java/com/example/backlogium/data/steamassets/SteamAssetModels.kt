package com.example.backlogium.data.steamassets

/** Every Steam image family rendered by Backlogium and therefore eligible for offline storage. */
enum class SteamAssetKind { AVATAR, GAME_ICON, HEADER, HERO_CAPSULE, LIBRARY_HERO, LIBRARY_CAPSULE, WIDE_CAPSULE, ACHIEVEMENT }

enum class SteamAssetManifestState { STORED, UNAVAILABLE }

enum class SteamAssetDownloadMode { DOWNLOAD_MISSING, REFRESH_ALL }

enum class SteamAssetOutcome { STORED, ALREADY_PRESENT, UNAVAILABLE, FAILED }

data class SteamAssetInventoryItem(val url: String, val kind: SteamAssetKind)

data class SteamAssetRunCounts(
    val stored: Int = 0,
    val alreadyPresent: Int = 0,
    val unavailable: Int = 0,
    val failed: Int = 0,
) {
    fun plus(outcome: SteamAssetOutcome) = when (outcome) {
        SteamAssetOutcome.STORED -> copy(stored = stored + 1)
        SteamAssetOutcome.ALREADY_PRESENT -> copy(alreadyPresent = alreadyPresent + 1)
        SteamAssetOutcome.UNAVAILABLE -> copy(unavailable = unavailable + 1)
        SteamAssetOutcome.FAILED -> copy(failed = failed + 1)
    }
}
