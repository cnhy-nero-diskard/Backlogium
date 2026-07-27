package com.example.backlogium.domain

/**
 * How one Library list is ordered. Persisted per list (the tracked section and the rest of the
 * library independently) as a Preferences DataStore string, so a constant's *name* is the stored
 * value — renaming one silently resets that list to its default order.
 *
 * Each key has one sensible direction rather than a togglable one: descending for the three
 * "more is more" keys, ascending for [NAME]. Every key tie-breaks by name ascending, so ordering
 * never depends on the order Room happened to return.
 */
enum class LibrarySortKey {
    /** Lifetime Steam playtime, longest first. */
    PLAYTIME,

    /** Game name, A→Z. */
    NAME,

    /** Steam's rolling two-week playtime, most recent activity first. */
    RECENT_ACTIVITY,

    /** XP the game contributed to the player's total, highest first. */
    XP_CONTRIBUTED,
}

/**
 * The two independent Library sort selections.
 *
 * Defaults mirror the DAO ordering exactly — `observeGoalGames()` is `ORDER BY name ASC`,
 * `observeBacklog()` is `ORDER BY playtimeForever DESC, name ASC` — so a fresh install and an
 * upgrade both render as they did before the sort controls existed, tie-break included.
 */
data class LibrarySortPrefs(
    val focus: LibrarySortKey = LibrarySortKey.NAME,
    val library: LibrarySortKey = LibrarySortKey.PLAYTIME,
)

/** Parse a stored sort-key name, tolerating a value written by a build that no longer matches. */
fun librarySortKeyOrNull(stored: String?): LibrarySortKey? =
    stored?.let { runCatching { LibrarySortKey.valueOf(it) }.getOrNull() }
