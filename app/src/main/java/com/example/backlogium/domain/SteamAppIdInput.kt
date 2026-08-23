package com.example.backlogium.domain

/** Parses a numeric app id or a Steam Store app URL. */
object SteamAppIdInput {
    private val numeric = Regex("^[1-9][0-9]{0,18}$")
    private val storeUrl = Regex(
        "^(?:https?://)?(?:www\\.)?store\\.steampowered\\.com/app/([1-9][0-9]*)(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    fun parse(input: String): Long? {
        val value = input.trim()
        val token = if (numeric.matches(value)) value else storeUrl.matchEntire(value)?.groupValues?.get(1)
        return token?.toLongOrNull()?.takeIf { it > 0 }
    }
}
