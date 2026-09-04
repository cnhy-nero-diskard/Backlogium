package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.ui.search.gameSearchMatchTier

/**
 * The fields a Library sort reads. Both row shapes implement it, so the four comparators exist
 * once rather than once per section.
 */
interface LibraryRow {
    val appId: Long
    val name: String
    val genres: List<GameGenre>
    val playtimeForever: Int
    val playtime2Weeks: Int
    val xpContributed: Long
}

/**
 * Order rows by [key] in [direction].
 *
 * Sorting happens here rather than in Room: [LibrarySortKey.XP_CONTRIBUTED] is a read-side
 * derivation SQL cannot express, and the lists are already fully in memory — so all four keys live
 * in one place instead of two of them in DAO queries and two in Kotlin.
 *
 * Every key tie-breaks by name then appId, so equal rows never fall back to whatever order Room
 * returned. Games with no value for a key (no recent playtime, no XP) are zero, so a key in its
 * default descending direction places them last, and reversing that places them first — both
 * without needing a special case.
 *
 * With a search active, [direction] applies to the sort comparator only. The relevance tier stays
 * ascending whichever way the list is pointed: reversing the whole composition would rank the
 * *weakest* match first, which is a wrong answer rather than an unexpected order.
 */
fun <T : LibraryRow> List<T>.sortedFor(
    key: LibrarySortKey,
    direction: LibrarySortDirection = key.defaultDirection,
    query: String = "",
): List<T> {
    val sort = comparatorFor(key, direction)
    if (query.isBlank()) return sortedWith(sort)

    val relevanceThenSort = compareBy<LibraryRow> {
        gameSearchMatchTier(
            query = query,
            name = it.name,
            genreLabels = it.genres.asSequence().map(GameGenre::label).asIterable(),
        )?.ordinal ?: Int.MAX_VALUE
    }.then(sort)
    return sortedWith(relevanceThenSort)
}

/**
 * The one place direction is composed in. Each key contributes a single comparator expressed in its
 * own [LibrarySortKey.defaultDirection] — byte-for-byte what the Library sorted by before
 * directions existed — and the opposite direction is that comparator reversed, rather than a second
 * hand-written comparator per key that could drift from the first.
 *
 * Reversal is total, tie-break included, so the reversed list is the exact reverse of the default
 * one. The `thenBy { appId }` tail keeps both directions a total order, so equal rows never shuffle
 * when the direction flips.
 */
internal fun comparatorFor(
    key: LibrarySortKey,
    direction: LibrarySortDirection = key.defaultDirection,
): Comparator<LibraryRow> {
    val inDefaultDirection = when (key) {
        LibrarySortKey.NAME -> byNameAscending
        LibrarySortKey.PLAYTIME ->
            compareByDescending<LibraryRow> { it.playtimeForever }.then(byNameAscending)

        LibrarySortKey.RECENT_ACTIVITY ->
            compareByDescending<LibraryRow> { it.playtime2Weeks }.then(byNameAscending)

        LibrarySortKey.XP_CONTRIBUTED ->
            compareByDescending<LibraryRow> { it.xpContributed }.then(byNameAscending)
    }
    return if (direction == key.defaultDirection) {
        inDefaultDirection
    } else {
        inDefaultDirection.reversed()
    }
}

/**
 * Plain lexicographic order, matching SQLite's default collation rather than being
 * case-insensitive — the persisted defaults promise the Library renders exactly as it did under
 * `ORDER BY name ASC` / `ORDER BY playtimeForever DESC, name ASC` until the user picks a sort, and
 * that promise includes the tie-break. appId settles genuinely identical names so the order is
 * total.
 */
private val byNameAscending: Comparator<LibraryRow> =
    compareBy<LibraryRow> { it.name }.thenBy { it.appId }

/** The other direction — what tapping the direction toggle selects. */
fun LibrarySortDirection.flipped(): LibrarySortDirection = when (this) {
    LibrarySortDirection.ASCENDING -> LibrarySortDirection.DESCENDING
    LibrarySortDirection.DESCENDING -> LibrarySortDirection.ASCENDING
}

/**
 * How a direction reads *for this key*, since "ascending" describes the machine and not the shelf.
 * Name ascending is A→Z; playtime ascending is the least-played end first.
 */
fun librarySortDirectionLabel(
    key: LibrarySortKey,
    direction: LibrarySortDirection,
): String = when (key) {
    LibrarySortKey.NAME -> when (direction) {
        LibrarySortDirection.ASCENDING -> "A to Z"
        LibrarySortDirection.DESCENDING -> "Z to A"
    }

    LibrarySortKey.PLAYTIME, LibrarySortKey.RECENT_ACTIVITY, LibrarySortKey.XP_CONTRIBUTED ->
        when (direction) {
            LibrarySortDirection.ASCENDING -> "lowest first"
            LibrarySortDirection.DESCENDING -> "highest first"
        }
}

/** Label for a sort option, naming what it orders by rather than what a row displays. */
fun librarySortLabel(key: LibrarySortKey): String = when (key) {
    LibrarySortKey.PLAYTIME -> "Playtime"
    LibrarySortKey.NAME -> "Name"
    LibrarySortKey.RECENT_ACTIVITY -> "Recently played"
    LibrarySortKey.XP_CONTRIBUTED -> "XP contributed"
}
