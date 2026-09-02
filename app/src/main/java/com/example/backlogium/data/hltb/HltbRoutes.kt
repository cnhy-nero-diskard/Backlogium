package com.example.backlogium.data.hltb

/**
 * Centralized route builders for external HLTB and Steam links.
 * UI surfaces derive URLs only from validated positive ids.
 */
object HltbRoutes {
    private const val CANONICAL_BASE = "https://howlongtobeat.com/game/"

    fun canonicalGameUrl(hltbId: Long): String {
        require(hltbId > 0) { "HLTB id must be positive, got $hltbId" }
        return "$CANONICAL_BASE$hltbId"
    }
}

object SteamRoutes {
    private const val STORE_BASE = "https://store.steampowered.com/app/"

    fun storeUrl(appId: Long): String {
        require(appId > 0) { "Steam appId must be positive, got $appId" }
        return "$STORE_BASE$appId"
    }
}
