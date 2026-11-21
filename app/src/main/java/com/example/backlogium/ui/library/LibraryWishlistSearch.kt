package com.example.backlogium.ui.library

import com.example.backlogium.ui.search.gameSearchMatchTier
import com.example.backlogium.ui.search.GameSearchMatchTier
import java.util.Locale

/** Search the cache only; changing a query never opens or refreshes the wishlist. */
internal fun wishlistForLibrary(library: LibraryUiState, wishlist: WishlistUiState): WishlistUiState {
    val query = library.filters.query.trim()
    if (query.isEmpty()) return wishlist
    val ownedIds = buildSet {
        library.allGames.forEach { add(it.appId) }
        library.goalGames.forEach { add(it.appId) }
        library.backlog.forEach { add(it.appId) }
    }
    val matches = wishlist.entries
        .distinctBy { it.appId }
        .filterNot { it.appId in ownedIds }
        .mapNotNull { entry ->
            gameSearchMatchTier(query, entry.name, emptyList())?.let { entry to it }
        }
        .sortedWith(compareBy<Pair<WishlistEntryUi, GameSearchMatchTier>> {
            it.second.ordinal
        }.thenBy { it.first.name.lowercase(Locale.ROOT) }.thenBy { it.first.appId })
        .map { it.first }
    return wishlist.copy(entries = matches, expanded = true)
}
