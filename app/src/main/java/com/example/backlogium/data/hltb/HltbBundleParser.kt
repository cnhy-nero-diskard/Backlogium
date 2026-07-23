package com.example.backlogium.data.hltb

import com.example.backlogium.data.hltb.dto.HltbSearchResponse

/**
 * Pure parsing for the HowLongToBeat scrape: locate the Next.js `_app` JS bundle in the
 * homepage HTML, extract the current search endpoint path out of that bundle, and map a
 * search response to candidates. Kept side-effect-free so it is unit-testable against
 * captured fixtures — the fragile, rotation-prone bits live here rather than in the network
 * code.
 */
object HltbBundleParser {

    /** Hard-coded fallback used only when the endpoint path cannot be extracted. */
    const val FALLBACK_ENDPOINT = "/api/seek"

    // e.g. /_next/static/chunks/pages/_app-1a2b3c4d5e6f7890.js
    private val APP_BUNDLE_REGEX =
        Regex("""/_next/static/chunks/pages/_app-[0-9a-fA-F]+\.js""")

    // The web client builds the search URL with a fetch("/api/<path>", {method:"POST"}) call.
    private val ENDPOINT_REGEX =
        Regex("""fetch\(\s*["'](/api/[a-zA-Z0-9/_-]+)["']""")

    /** The `_app-*.js` bundle path referenced by the homepage, or null if not found. */
    fun extractAppBundlePath(html: String): String? = APP_BUNDLE_REGEX.find(html)?.value

    /** The current POST search endpoint path from the bundle, or null if not found. */
    fun extractEndpoint(appJs: String): String? =
        ENDPOINT_REGEX.find(appJs)?.groupValues?.getOrNull(1)

    /** Map a raw search response to candidates, converting `comp_*` seconds to minutes. */
    fun mapCandidates(response: HltbSearchResponse): List<HltbCandidate> =
        response.data.map { game ->
            HltbCandidate(
                hltbId = game.gameId,
                name = game.gameName,
                mainStoryMinutes = secondsToMinutes(game.compMainSeconds),
                mainExtraMinutes = secondsToMinutes(game.compPlusSeconds),
                completionistMinutes = secondsToMinutes(game.comp100Seconds),
                allStylesMinutes = secondsToMinutes(game.compAllSeconds),
            )
        }

    /** Seconds → whole minutes (rounded), or null when the source length is absent (<= 0). */
    private fun secondsToMinutes(seconds: Int): Int? =
        if (seconds <= 0) null else (seconds + 30) / 60
}
