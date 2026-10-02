package com.example.backlogium.ui.library

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import com.example.backlogium.domain.GameListDensity

internal data class LibraryScrollItem(val key: String, val gameIds: List<Long> = emptyList())

internal fun libraryGameItemKey(ids: List<Long>, density: GameListDensity, row: Int): String =
    if (density.isGrid) "library-grid-row-$row-${ids.first()}" else "library-game-${ids.first()}"

/** Mirrors the lazy sections, including optional rows above the games. */
internal fun libraryScrollItems(state: LibraryUiState, wishlist: WishlistUiState): List<LibraryScrollItem> =
    buildList {
        if (!state.libraryEmpty) {
            add(LibraryScrollItem("library-controls"))
            if (state.matchCenterCount > 0) add(LibraryScrollItem("library-attention"))
            if (state.refreshing) add(LibraryScrollItem("library-lookup"))
        }
        if (shouldShowWishlistSection(state, wishlist, state.filters.selectedGenreIds)) {
            add(LibraryScrollItem("wishlist-header"))
            if (wishlist.expanded) {
                if (wishlist.staleNotice) add(LibraryScrollItem("wishlist-notice"))
                if (wishlist.entries.isEmpty()) add(LibraryScrollItem("wishlist-empty"))
                else wishlist.entries.map { it.appId }.chunked(state.density.columns).forEachIndexed { row, ids ->
                    add(LibraryScrollItem(
                        if (state.density.isGrid) "wishlist-grid-row-$row-${ids.first()}"
                        else "wishlist-${ids.first()}",
                    ))
                }
            }
        }
        if (state.libraryEmpty) add(LibraryScrollItem("library-empty"))
        else {
            fun games(section: String, ids: List<Long>) {
                if (ids.isEmpty()) return
                add(LibraryScrollItem(section))
                ids.chunked(state.density.columns).forEachIndexed { row, group ->
                    add(LibraryScrollItem(libraryGameItemKey(group, state.density, row), group))
                }
            }
            games("library-focus", state.goalGames.map { it.appId })
            games("library-backlog", state.backlog.map { it.appId })
            if (state.noMatches) add(LibraryScrollItem("library-no-matches"))
        }
    }

internal fun resolveLibraryScroll(
    anchor: LibraryScrollAnchor?,
    items: List<LibraryScrollItem>,
    density: GameListDensity = anchor?.density ?: GameListDensity.LIST,
): Pair<Int, Int> {
    if (anchor == null || items.isEmpty()) return 0 to 0
    val matched = if (anchor.gameId != null) items.indexOfFirst { anchor.gameId in it.gameIds }
    else items.indexOfFirst { it.key == anchor.itemKey }
    // A pixel offset from a differently sized row could scroll past the game we just located.
    val offset = if (anchor.density == density) anchor.offset.coerceAtLeast(0) else 0
    return (if (matched >= 0) matched else anchor.index.coerceIn(items.indices)) to offset
}

@Composable
internal fun rememberLibraryScrollState(
    visit: LibraryVisitState,
    generation: Long,
    state: LibraryUiState,
    wishlist: WishlistUiState,
): LazyListState {
    val items = remember(state, wishlist) { libraryScrollItems(state, wishlist) }
    // No rememberSaveable: a newly launched process must never resurrect navigation's saved scroll.
    val listState = remember(visit, generation, state.density) {
        val (index, offset) = resolveLibraryScroll(visit.scrollAnchor, items, state.density)
        LazyListState(index, offset)
    }
    fun capture() {
        if (listState.layoutInfo.totalItemsCount == 0) return
        val index = listState.firstVisibleItemIndex
        val item = items.getOrNull(index) ?: return
        visit.captureScroll(LibraryScrollAnchor(
            gameId = item.gameIds.firstOrNull(), itemKey = item.key,
            index = index, offset = listState.firstVisibleItemScrollOffset,
            density = state.density,
        ), generation)
    }
    LaunchedEffect(listState, items, generation) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { capture() }
    }
    DisposableEffect(listState, items, generation) {
        onDispose { capture() }
    }
    return listState
}
