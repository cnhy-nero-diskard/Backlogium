package com.example.backlogium.ui.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production Library content with cache fixtures and captured navigation/store callbacks. */
@RunWith(AndroidJUnit4::class)
class LibraryDiscoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    private val library = mutableStateOf(LibraryUiState(
        loading = false, libraryEmpty = false, filters = LibraryFilters("portal"),
        backlog = listOf(BacklogGameUi(1, "Portal owned", "", playtimeForever = 1)),
    ))
    private val wishlist = mutableStateOf(WishlistUiState(
        configured = true, expanded = false, availability = WishlistAvailability.AVAILABLE,
        entries = listOf(entry(1, "Portal owned"), entry(2, "Portal wanted")),
    ))
    private var openedDetail: Long? = null
    private var openedStore: String? = null
    private var expansions = 0
    private var selectionCalls = 0
    private fun entry(id: Long, name: String) = WishlistEntryUi(
        id, name, "", WishlistPriceUi.Retained("$5", null, 0, 1_000), "https://store.steampowered.com/app/$id",
    )
    private fun showLibrary() = compose.setContent {
        BacklogiumTheme {
            LibraryContent(
                state = library.value, wishlistState = wishlist.value,
                actions = LibraryContentActions(
                    onOpenGameDetail = { openedDetail = it }, onOpenStore = { openedStore = it },
                    onSetWishlistExpanded = { expansions++ }, onToggleSelection = { selectionCalls++ },
                ),
            )
        }
    }
    private fun scrollTo(key: String) {
        val rows = libraryScrollItems(library.value, wishlist.value)
        val index = rows.indexOfFirst { it.key == key }
        assertTrue("Missing lazy row $key", index >= 0)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
    }

    @Test fun wishlistSearchUsesStoreAndOwnedOverlapUsesDetailInAllDensities() {
        showLibrary()
        for (density in GameListDensity.entries) {
            compose.runOnIdle { library.value = library.value.copy(density = density) }
            scrollTo("wishlist-header")
            compose.onNodeWithText("Wishlist results").assertIsDisplayed()
            compose.onNodeWithContentDescription("Open Portal wanted on Steam").performClick()
            compose.runOnIdle {
                assertEquals("https://store.steampowered.com/app/2", openedStore)
                assertEquals(0, expansions)
                assertEquals(0, selectionCalls)
                assertNull(openedDetail)
            }
            val rows = libraryScrollItems(library.value, wishlist.value)
            val owned = rows.first { 1L in it.gameIds }.key
            scrollTo(owned)
            compose.onAllNodesWithText("Portal owned").assertCountEquals(1)
            compose.onNodeWithText("Portal owned").performClick()
            compose.runOnIdle { assertEquals(1L, openedDetail); openedDetail = null }
            compose.onNode(hasContentDescription("Open Portal owned on Steam")).assertDoesNotExist()
        }
    }

    @Test fun readFailurePreservesCachedMatchesAndNeverClaimsWishlistNoMatch() {
        showLibrary()
        for (availability in listOf(WishlistAvailability.UNREACHABLE, WishlistAvailability.NOT_READABLE)) {
            for (density in GameListDensity.entries) {
                compose.runOnIdle {
                    library.value = library.value.copy(density = density)
                    wishlist.value = wishlist.value.copy(availability = availability, entries = listOf(entry(2, "Portal wanted")))
                }
                scrollTo("wishlist-notice")
                compose.onNodeWithText("Showing what was last seen.", substring = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Open Portal wanted on Steam").assertIsDisplayed()
                compose.onNodeWithText("Seen", substring = true).assertIsDisplayed()
                for (entries in listOf(emptyList(), listOf(entry(2, "Different title")))) {
                    compose.runOnIdle { wishlist.value = wishlist.value.copy(entries = entries) }
                    scrollTo("wishlist-empty")
                    compose.onNodeWithText("Wishlist unavailable").assertIsDisplayed()
                    compose.onNodeWithText("No cached wishlist matches").assertDoesNotExist()
                    compose.onNodeWithText("Nothing wishlisted").assertDoesNotExist()
                }
            }
        }
    }

    @Test fun ownedOnlyFiltersExcludeWishlistResultsInAllDensities() {
        showLibrary()
        for (density in GameListDensity.entries) {
            for (filters in listOf(
                LibraryFilters("portal", setOf("puzzle")), LibraryFilters("portal", notCoveredOnly = true),
                LibraryFilters("portal", familySharedOnly = true),
            )) {
                compose.runOnIdle { library.value = library.value.copy(density = density, filters = filters) }
                compose.onNodeWithText("Wishlist results").assertDoesNotExist()
                compose.onNodeWithContentDescription("Open Portal wanted on Steam").assertDoesNotExist()
            }
        }
    }
}
