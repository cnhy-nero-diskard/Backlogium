package com.example.backlogium.data.hltb

import java.net.URI

/**
 * Pure canonical HLTB game-link parser.
 * Accepts only absolute HTTPS URLs whose normalized host is `howlongtobeat.com` or
 * `www.howlongtobeat.com`, with no user-info, custom port, query, or fragment.
 * The path must identify one positive numeric game id under `/game/{id}`.
 * Optional trailing slash is normalized away. The system never requests the
 * pasted URL verbatim; it extracts the id and constructs the canonical URL.
 */
object HltbGameLink {

    private val ALLOWED_HOSTS = setOf("howlongtobeat.com", "www.howlongtobeat.com")
    private val GAME_PATH_REGEX = Regex("""^/game/(\d+)/?$""")

    sealed class ParseResult {
        data class Valid(val hltbId: Long, val canonicalUrl: String) : ParseResult()
        data class Invalid(val reason: String) : ParseResult()
    }

    fun parse(rawInput: String): ParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ParseResult.Invalid("empty")
        // Must be absolute HTTPS URL
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return ParseResult.Invalid("malformed URL")
        }
        val scheme = uri.scheme?.lowercase() ?: return ParseResult.Invalid("missing scheme")
        if (scheme != "https") return ParseResult.Invalid("scheme must be https, got $scheme")
        val host = uri.host?.lowercase() ?: return ParseResult.Invalid("missing host")
        if (host !in ALLOWED_HOSTS) return ParseResult.Invalid("host not allowed: $host")
        if (uri.userInfo != null) return ParseResult.Invalid("credentials not allowed")
        if (uri.port != -1) return ParseResult.Invalid("custom port not allowed")
        if (uri.query != null) return ParseResult.Invalid("query not allowed")
        if (uri.fragment != null) return ParseResult.Invalid("fragment not allowed")
        val path = uri.path ?: return ParseResult.Invalid("missing path")
        val match = GAME_PATH_REGEX.matchEntire(path) ?: return ParseResult.Invalid("unsupported path: $path")
        val idStr = match.groupValues[1]
        val id = idStr.toLongOrNull() ?: return ParseResult.Invalid("id not numeric")
        if (id <= 0) return ParseResult.Invalid("id must be positive")
        return ParseResult.Valid(id, HltbRoutes.canonicalGameUrl(id))
    }

    fun canonicalUrlForId(hltbId: Long): String = HltbRoutes.canonicalGameUrl(hltbId)
}
