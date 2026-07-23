package com.example.backlogium.data.hltb

import com.example.backlogium.data.hltb.dto.HltbSearchResponse

/**
 * Pure parsing for the HowLongToBeat scrape: enumerate the Next.js JS chunks referenced by the
 * homepage, extract the current POST search endpoint path out of a chunk, and map a search
 * response to candidates. Kept side-effect-free so it is unit-testable against captured
 * fixtures — the fragile, rotation-prone bits live here rather than in the network code.
 */
object HltbBundleParser {

    /**
     * Hard-coded fallback / fast-path endpoint used when dynamic extraction is unnecessary or
     * fails. HLTB currently serves search at `/api/bleed` (with an `/api/bleed/init` handshake).
     */
    const val FALLBACK_ENDPOINT = "/api/bleed"

    // Any Next.js chunk the homepage pulls in (names are opaque hashes, no stable "_app-").
    private val CHUNK_REGEX = Regex("""/_next/static/chunks/[^"']+\.js""")

    // The web client posts the search via fetch("/api/<name>", { ... method:"POST" ... }).
    private val ENDPOINT_REGEX = Regex(
        """fetch\(\s*["'](/api/[a-zA-Z0-9_]+)["']\s*,\s*\{[^}]*method\s*:\s*["']POST["']""",
    )

    /** All `_next/static/chunks` JS paths referenced by the homepage HTML (de-duplicated). */
    fun extractChunkPaths(html: String): List<String> =
        CHUNK_REGEX.findAll(html).map { it.value }.distinct().toList()

    /** The current POST search endpoint path from a chunk's JS, or null if not present. */
    fun extractSearchEndpoint(chunkJs: String): String? =
        ENDPOINT_REGEX.find(chunkJs)?.groupValues?.getOrNull(1)

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
