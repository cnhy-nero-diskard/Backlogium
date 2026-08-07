package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.domain.LibrarySortKey

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
    val xpContributed: Int
}

/**
 * Order rows by [key].
 *
 * Sorting happens here rather than in Room: [LibrarySortKey.XP_CONTRIBUTED] is a read-side
 * derivation SQL cannot express, and the lists are already fully in memory — so all four keys live
 * in one place instead of two of them in DAO queries and two in Kotlin.
 *
 * Every key tie-breaks by name ascending, so equal rows never fall back to whatever order Room
 * returned. Games with no value for a key (no recent playtime, no XP) are zero, and every
 * descending key therefore places them last without needing a special case.
 */
fun <T : LibraryRow> List<T>.sortedFor(key: LibrarySortKey): List<T> = sortedWith(comparatorFor(key))

internal fun comparatorFor(key: LibrarySortKey): Comparator<LibraryRow> = when (key) {
    LibrarySortKey.NAME -> byNameAscending
    LibrarySortKey.PLAYTIME ->
        compareByDescending<LibraryRow> { it.playtimeForever }.then(byNameAscending)

    LibrarySortKey.RECENT_ACTIVITY ->
        compareByDescending<LibraryRow> { it.playtime2Weeks }.then(byNameAscending)

    LibrarySortKey.XP_CONTRIBUTED ->
        compareByDescending<LibraryRow> { it.xpContributed }.then(byNameAscending)
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

/** Label for a sort option, naming what it orders by rather than what a row displays. */
fun librarySortLabel(key: LibrarySortKey): String = when (key) {
    LibrarySortKey.PLAYTIME -> "Playtime"
    LibrarySortKey.NAME -> "Name"
    LibrarySortKey.RECENT_ACTIVITY -> "Recently played"
    LibrarySortKey.XP_CONTRIBUTED -> "XP contributed"
}
