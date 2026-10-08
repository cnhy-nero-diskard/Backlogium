package com.example.backlogium.ui.library

import com.example.backlogium.domain.GameListDensity
import org.junit.Assert.*
import org.junit.Test

class LibraryScrollContextTest {
    private fun state(density: GameListDensity, ids: List<Long>) = LibraryUiState(
        loading = false, libraryEmpty = false, density = density,
        backlog = ids.map { BacklogGameUi(appId = it, name = "Game $it", iconUrl = "", playtimeForever = 0) },
    )

    @Test fun gameIdentitySurvivesReorderingAndRegroupingInEveryDensity() {
        for (density in GameListDensity.entries) {
            val items = libraryScrollItems(state(density, (1L..20L).toList().reversed()), WishlistUiState())
            val (index, offset) = resolveLibraryScroll(LibraryScrollAnchor(gameId = 8, index = 4, offset = 23), items)
            assertTrue(8L in items[index].gameIds)
            assertEquals(23, offset)
        }
    }

    @Test fun missingGameClampsToNearestValidRowInEveryDensity() {
        for (density in GameListDensity.entries) {
            val items = libraryScrollItems(state(density, listOf(1, 2, 3)), WishlistUiState())
            assertEquals(items.lastIndex to 9, resolveLibraryScroll(
                LibraryScrollAnchor(gameId = 99, index = 80, offset = 9), items,
            ))
            assertEquals(2 to 9, resolveLibraryScroll(
                LibraryScrollAnchor(gameId = 99, index = 2, offset = 9), items,
            ))
        }
    }

    @Test fun freshVisitAndEmptyContentStartAtTop() {
        assertEquals(0 to 0, resolveLibraryScroll(null, listOf(LibraryScrollItem("controls"))))
        assertEquals(0 to 0, resolveLibraryScroll(LibraryScrollAnchor(index = 9), emptyList()))
    }

    @Test fun densityChangeKeepsGameButDropsIncompatiblePixelOffset() {
        val items = libraryScrollItems(state(GameListDensity.COMPACT_GRID, (1L..80L).toList()), WishlistUiState())
        val (index, offset) = resolveLibraryScroll(
            LibraryScrollAnchor(gameId = 40, offset = 250, density = GameListDensity.LIST),
            items, GameListDensity.COMPACT_GRID,
        )
        assertTrue(40L in items[index].gameIds)
        assertEquals(0, offset)
    }

    @Test fun sectionAnchorAccountsForChangingOptionalRows() {
        val state = state(GameListDensity.LIST, listOf(1, 2)).copy(matchCenterCount = 1)
        val items = libraryScrollItems(state, WishlistUiState())
        assertEquals(2 to 14, resolveLibraryScroll(LibraryScrollAnchor(itemKey = "library-backlog", offset = 14), items))
    }
}
