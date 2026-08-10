package com.example.backlogium.ui.search

/** Match strengths shared by the Library and collection Add-games searches. */
enum class GameSearchMatchTier {
    EXACT_NAME,
    NAME_PREFIX,
    WORD_PREFIX,
    NAME_SUBSTRING,
    GENRE_LABEL,
}

/**
 * Resolve the strongest match for one game, or null when the query does not match it.
 *
 * The query is trimmed and comparisons are case-insensitive. Word starts are detected after
 * non-alphanumeric separators and at ASCII lower-to-upper transitions such as the second word in
 * `RedDead`.
 */
fun gameSearchMatchTier(
    query: String,
    name: String,
    genreLabels: Iterable<String>,
): GameSearchMatchTier? {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return null

    return when {
        name.equals(trimmed, ignoreCase = true) -> GameSearchMatchTier.EXACT_NAME
        name.startsWith(trimmed, ignoreCase = true) -> GameSearchMatchTier.NAME_PREFIX
        name.containsWordPrefix(trimmed) -> GameSearchMatchTier.WORD_PREFIX
        name.contains(trimmed, ignoreCase = true) -> GameSearchMatchTier.NAME_SUBSTRING
        genreLabels.any { it.contains(trimmed, ignoreCase = true) } ->
            GameSearchMatchTier.GENRE_LABEL
        else -> null
    }
}

private fun String.containsWordPrefix(query: String): Boolean {
    var searchFrom = 0
    while (searchFrom < length) {
        val index = indexOf(query, startIndex = searchFrom, ignoreCase = true)
        if (index < 0) return false
        if (isWordStart(index)) return true
        searchFrom = index + 1
    }
    return false
}

private fun String.isWordStart(index: Int): Boolean {
    if (index == 0) return true
    val current = this[index]
    if (!current.isLetterOrDigit()) return false

    val previous = this[index - 1]
    return !previous.isLetterOrDigit() ||
        (previous in 'a'..'z' && current in 'A'..'Z')
}
