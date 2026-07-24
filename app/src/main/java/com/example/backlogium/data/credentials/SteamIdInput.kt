package com.example.backlogium.data.credentials

/**
 * Pure parsing/validation for the two SteamID entry paths. No network, no Android dependencies —
 * unit-testable in isolation. Vanity resolution (the network step) is layered on top by
 * [CredentialsRepository][com.example.backlogium.data.repo.CredentialsRepository].
 */
object SteamIdInput {

    /** A validated SteamID64 is exactly 17 digits and begins with the `7656119` community prefix. */
    private const val STEAM_ID64_PREFIX = "7656119"
    private const val STEAM_ID64_LENGTH = 17

    /** The outcome of parsing a raw SteamID entry into something resolvable. */
    sealed interface Parsed {
        /** A concrete SteamID64 ready for validation (bare ID or `/profiles/<id64>`). */
        data class SteamId64(val value: String) : Parsed

        /** A vanity token from a `/id/<vanity>` URL that must be resolved via the Steam API. */
        data class Vanity(val token: String) : Parsed

        /** The input is neither a SteamID64 nor a recognizable Steam profile URL. */
        data object Unrecognized : Parsed
    }

    /** True for a 17-digit SteamID64 beginning `7656119`. */
    fun isValidSteamId64(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length == STEAM_ID64_LENGTH &&
            trimmed.startsWith(STEAM_ID64_PREFIX) &&
            trimmed.all { it.isDigit() }
    }

    /**
     * Normalize a raw SteamID entry into a [Parsed] shape:
     * - a bare 17-digit SteamID64 → [Parsed.SteamId64]
     * - `steamcommunity.com/profiles/<id64>` → extract the digits locally → [Parsed.SteamId64]
     * - `steamcommunity.com/id/<vanity>` → [Parsed.Vanity] for API resolution
     * - anything else → [Parsed.Unrecognized]
     *
     * Note: this only *classifies* the input; the SteamID64 value it returns is still subject to
     * [isValidSteamId64] before it is accepted.
     */
    fun parse(raw: String): Parsed {
        val input = raw.trim()
        if (input.isEmpty()) return Parsed.Unrecognized

        // Bare numeric SteamID64.
        if (input.all { it.isDigit() }) return Parsed.SteamId64(input)

        val profileMatch = PROFILES_REGEX.find(input)
        if (profileMatch != null) {
            return Parsed.SteamId64(profileMatch.groupValues[1])
        }

        val vanityMatch = VANITY_REGEX.find(input)
        if (vanityMatch != null) {
            return Parsed.Vanity(vanityMatch.groupValues[1])
        }

        return Parsed.Unrecognized
    }

    // Tolerate optional scheme, www., and a trailing slash; capture the id/token segment.
    private val PROFILES_REGEX =
        Regex("""steamcommunity\.com/profiles/(\d+)/?""", RegexOption.IGNORE_CASE)
    private val VANITY_REGEX =
        Regex("""steamcommunity\.com/id/([^/?#]+)/?""", RegexOption.IGNORE_CASE)
}
