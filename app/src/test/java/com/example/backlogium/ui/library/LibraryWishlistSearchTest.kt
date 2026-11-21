package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.HltbMatchState
import com.example.backlogium.data.repo.WishlistAvailability
import org.junit.Assert.*
import org.junit.Test

class LibraryWishlistSearchTest {
    private fun entry(id: Long, name: String) = WishlistEntryUi(
        id, name, "", WishlistPriceUi.Retained("$5", null, 0, 1_000), "https://store.steampowered.com/app/$id",
    )
    private val wishlist = WishlistUiState(
        configured = true, expanded = false,
        entries = listOf(entry(1, "Portal"), entry(2, "Portal 2"), entry(3, "A Portal Story"), entry(4, "Hades")),
        availability = WishlistAvailability.AVAILABLE,
    )

    @Test fun titleMatchesUseTrimmedCaseInsensitiveRelevanceAndOpenCollapsedCache() {
        val library = LibraryUiState(filters = LibraryFilters(query = "  pOrTaL  "))
        val result = wishlistForLibrary(library, wishlist)
        assertEquals(listOf(1L, 2L, 3L), result.entries.map { it.appId })
        assertTrue(result.expanded)
        assertFalse(wishlist.expanded)
        assertTrue(shouldShowWishlistSection(library, wishlist, emptySet()))
    }

    @Test fun ownedAndSharedIdsWinAndCacheDuplicatesAreRemoved() {
        val library = LibraryUiState(
            filters = LibraryFilters(query = "portal"),
            allGames = listOf(LibraryBatchGame(1, "Portal", HltbMatchState.NOT_COVERED)),
            backlog = listOf(BacklogGameUi(2, "Portal 2", "", playtimeForever = 0, isFamilyShared = true)),
        )
        val result = wishlistForLibrary(library, wishlist.copy(entries = wishlist.entries + entry(3, "A Portal Story")))
        assertEquals(listOf(3L), result.entries.map { it.appId })
        assertEquals(listOf(2L), library.backlog.map { it.appId })
    }

    @Test fun ownedOnlyPredicatesSuppressWishlistEvenWithNoOwnedGames() {
        for (filters in listOf(
            LibraryFilters("portal", setOf("puzzle")),
            LibraryFilters("portal", notCoveredOnly = true),
            LibraryFilters("portal", familySharedOnly = true),
        )) {
            for (empty in listOf(false, true)) {
                val library = LibraryUiState(filters = filters, libraryEmpty = empty)
                assertFalse(shouldShowWishlistSection(library, wishlist, filters.selectedGenreIds))
            }
        }
    }

    @Test fun failedReadsKeepMatchingEntriesAvailabilityAndRetainedPrices() {
        for (availability in listOf(WishlistAvailability.NOT_READABLE, WishlistAvailability.UNREACHABLE)) {
            val result = wishlistForLibrary(
                LibraryUiState(filters = LibraryFilters("portal")), wishlist.copy(availability = availability),
            )
            assertEquals(3, result.entries.size)
            assertEquals(availability, result.availability)
            assertTrue(result.staleNotice)
            assertSame(wishlist.entries.first().price, result.entries.first().price)
        }
    }

    @Test fun missingOrNonmatchingCacheAfterFailureNeverClaimsConfirmedEmpty() {
        for (availability in listOf(WishlistAvailability.NOT_READABLE, WishlistAvailability.UNREACHABLE, WishlistAvailability.UNKNOWN)) {
            for (entries in listOf(emptyList(), wishlist.entries)) {
                val result = wishlistForLibrary(
                    LibraryUiState(filters = LibraryFilters("missing")),
                    wishlist.copy(entries = entries, availability = availability),
                )
                assertTrue(result.entries.isEmpty())
                assertFalse(result.isEmpty)
                assertEquals(availability, result.availability)
            }
        }
    }

    @Test fun blankQueryRetainsOrdinaryWishlistState() {
        assertSame(wishlist, wishlistForLibrary(LibraryUiState(filters = LibraryFilters("  ")), wishlist))
    }

    @Test fun searchedWishlistRowsParticipateInScrollAnchorsInEveryDensity() {
        for (density in com.example.backlogium.domain.GameListDensity.entries) {
            val library = LibraryUiState(
                loading = false, libraryEmpty = false, filters = LibraryFilters("portal"), density = density,
            )
            val rows = libraryScrollItems(library, wishlist)
            assertTrue(rows.any { it.key == "wishlist-header" })
            assertEquals(listOf(1L, 2L, 3L), rows.filter { it.key.startsWith("wishlist-") }.flatMap { it.gameIds })
        }
    }
}
