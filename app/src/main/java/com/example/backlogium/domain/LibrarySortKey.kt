package com.example.backlogium.domain

/**
 * How one Library list is ordered. Persisted per list (the tracked section and the rest of the
 * library independently) as a Preferences DataStore string, so a constant's *name* is the stored
 * value — renaming one silently resets that list to its default order.
 *
 * Each key has one *default* direction — descending for the three "more is more" keys, ascending
 * for [NAME] — but no longer only one direction. The original reasoning held that one direction per
 * key was the sensible one, which is true of the default and false as a law: "which games have I
 * barely touched?" is a real question about a backlog, and playtime ascending is the only way to
 * ask it. Every key tie-breaks by name then appId, so ordering never depends on the order Room
 * happened to return, in either direction.
 */
enum class LibrarySortKey {
    /** Lifetime Steam playtime, longest first by default. */
    PLAYTIME,

    /** Game name, A→Z by default. */
    NAME,

    /** Steam's rolling two-week playtime, most recent activity first by default. */
    RECENT_ACTIVITY,

    /** XP the game contributed to the player's total, highest first by default. */
    XP_CONTRIBUTED,
    ;

    /**
     * The direction this key is ordered in when the user has never reversed the list.
     *
     * These are exactly the fixed directions the Library used before directions existed, restated
     * as defaults rather than as laws. That is what makes the feature migration-free: an upgrade
     * has nothing stored, reads this, and renders as it always did.
     */
    val defaultDirection: LibrarySortDirection
        get() = when (this) {
            NAME -> LibrarySortDirection.ASCENDING
            PLAYTIME, RECENT_ACTIVITY, XP_CONTRIBUTED -> LibrarySortDirection.DESCENDING
        }
}

/**
 * Which end of a [LibrarySortKey]'s ordering comes first. Persisted per list by constant *name*
 * exactly as [LibrarySortKey] is, so renaming one silently resets that list to its key's
 * [LibrarySortKey.defaultDirection].
 *
 * A separate axis rather than twice as many sort keys: doubling the enum would mean a persisted
 * name per direction and a migration path for the four names already in users' DataStore, whereas
 * an absent direction here simply means "the direction this app has always used".
 */
enum class LibrarySortDirection {
    ASCENDING,
    DESCENDING,
}

/**
 * The two independent Library sort selections, key and direction each.
 *
 * Defaults mirror the DAO ordering exactly — `observeGoalGames()` is `ORDER BY name ASC`,
 * `observeBacklog()` is `ORDER BY playtimeForever DESC, name ASC` — so a fresh install and an
 * upgrade both render as they did before the sort controls existed, tie-break included. The
 * directions default to their own key's default, so the pair is consistent however the key changes.
 */
data class LibrarySortPrefs(
    val focus: LibrarySortKey = LibrarySortKey.NAME,
    val library: LibrarySortKey = LibrarySortKey.PLAYTIME,
    val focusDirection: LibrarySortDirection = focus.defaultDirection,
    val libraryDirection: LibrarySortDirection = library.defaultDirection,
)

/** Parse a stored sort-key name, tolerating a value written by a build that no longer matches. */
fun librarySortKeyOrNull(stored: String?): LibrarySortKey? =
    stored?.let { runCatching { LibrarySortKey.valueOf(it) }.getOrNull() }

/** Parse a stored direction name; an unrecognized or absent value is the key's own default. */
fun librarySortDirectionOrNull(stored: String?): LibrarySortDirection? =
    stored?.let { runCatching { LibrarySortDirection.valueOf(it) }.getOrNull() }
