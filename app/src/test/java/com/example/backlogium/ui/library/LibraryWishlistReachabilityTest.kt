package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.WishlistAvailability
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The independent wishlist surface must remain reachable when the owned library has no rows. */
class LibraryWishlistReachabilityTest {

    @Test
    fun emptyOwnedLibrary_withConfiguredWishlist_keepsWishlistSurfaceVisible() {
        val library = LibraryUiState(libraryEmpty = true)
        val wishlist = WishlistUiState(
            configured = true,
            entries = listOf(
                WishlistEntryUi(
                    appId = 292030,
                    name = "The Witcher 3",
                    artworkUrl = "https://cdn/292030.jpg",
                    price = WishlistPriceUi.NeverObserved,
                    storeUrl = "https://store.steampowered.com/app/292030",
                ),
            ),
            availability = WishlistAvailability.AVAILABLE,
        )

        assertTrue(shouldShowWishlistSection(library, wishlist, emptySet()))
        assertFalse(shouldShowFullScreenLibraryEmptyState(library, wishlist))
    }

    @Test
    fun emptyOwnedLibrary_withoutConfiguredWishlist_keepsExistingFullScreenState() {
        val library = LibraryUiState(libraryEmpty = true)
        val wishlist = WishlistUiState(configured = false)

        assertFalse(shouldShowWishlistSection(library, wishlist, emptySet()))
        assertTrue(shouldShowFullScreenLibraryEmptyState(library, wishlist))
    }
}