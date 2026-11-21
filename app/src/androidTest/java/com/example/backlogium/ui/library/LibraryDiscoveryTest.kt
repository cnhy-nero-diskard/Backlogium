package com.example.backlogium.ui.library

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.repo.WishlistAvailability
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
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
                    onClearFilters = { library.value = library.value.copy(filters = LibraryFilters()) },
                    onSetFocusSort = { library.value = library.value.copy(focusSort = it, focusSortDirection = it.defaultDirection); resort() },
                    onSetLibrarySort = { library.value = library.value.copy(librarySort = it, librarySortDirection = it.defaultDirection); resort() },
                    onSetFocusSortDirection = { library.value = library.value.copy(focusSortDirection = it); resort() },
                    onSetLibrarySortDirection = { library.value = library.value.copy(librarySortDirection = it); resort() },
                ),
            )
        }
    }
    private fun resort() {
        val state = library.value
        library.value = state.copy(
            goalGames = state.goalGames.sortedFor(state.focusSort, state.focusSortDirection, state.query),
            backlog = state.backlog.sortedFor(state.librarySort, state.librarySortDirection, state.query),
        )
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
            capture("${density.name}-wishlist-results")
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
                compose.onNodeWithText("Last seen", substring = true).assertIsDisplayed()
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

    @Test fun retainedWishlistAnchorWaitsForCachedWishlistBeforeCapturingFallback() {
        val retainedVisit = LibraryVisitState()
        val retainedAnchor = LibraryScrollAnchor(gameId = 3, index = 2)
        retainedVisit.captureScroll(retainedAnchor, retainedVisit.generation.value)
        library.value = LibraryUiState(
            loading = false,
            configured = true,
            libraryEmpty = false,
            filters = LibraryFilters("Portal"),
            backlog = listOf(BacklogGameUi(1, "Portal owned", "", playtimeForever = 1)),
        )
        wishlist.value = WishlistUiState()

        compose.setContent {
            BacklogiumTheme {
                LibraryContent(
                    state = library.value,
                    wishlistState = wishlist.value,
                    visit = retainedVisit,
                )
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(retainedAnchor, retainedVisit.scrollAnchor) }

        compose.runOnIdle {
            wishlist.value = WishlistUiState(
                configured = true,
                availability = WishlistAvailability.AVAILABLE,
                entries = listOf(entry(2, "Portal early"), entry(3, "Portal wanted")),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Portal wanted").assertIsDisplayed()
        compose.runOnIdle { assertEquals(3L, retainedVisit.scrollAnchor?.gameId) }
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

    @Test fun retainedOwnedFilterRemainsClearableWhenLibraryBecomesEmpty() {
        compose.runOnIdle {
            library.value = LibraryUiState(
                loading = false,
                libraryEmpty = false,
                filters = LibraryFilters(familySharedOnly = true),
                backlog = listOf(
                    BacklogGameUi(1, "Portal shared", "", playtimeForever = 1, isFamilyShared = true),
                ),
            )
        }
        showLibrary()
        compose.onNodeWithText("Portal shared").assertIsDisplayed()

        compose.runOnIdle {
            library.value = library.value.copy(
                backlog = emptyList(),
                allGames = emptyList(),
                libraryEmpty = true,
            )
        }

        compose.onNodeWithText("Search games or genres").assertIsDisplayed()
        compose.onNodeWithText("Family Shared").assertIsDisplayed()
        compose.onNodeWithText("Wishlist").assertDoesNotExist()
        compose.onNodeWithText("Clear all").performClick()

        compose.runOnIdle { assertEquals(LibraryFilters(), library.value.filters) }
        compose.onNodeWithText("Wishlist").assertIsDisplayed()
    }

    @Test fun addedRecentlyControlsExplainObservationAndKeepSectionDirectionsIndependent() {
        showLibrary()
        for (density in GameListDensity.entries) {
            compose.runOnIdle {
                wishlist.value = WishlistUiState()
                library.value = LibraryUiState(
                    loading = false, libraryEmpty = false, density = density,
                    focusSort = LibrarySortKey.NAME, librarySort = LibrarySortKey.NAME,
                    goalGames = listOf(
                        GoalGameUi(10, "Focus old", "", playtimeForever = 0, firstSeenAt = 1_000),
                        GoalGameUi(11, "Focus new", "", playtimeForever = 0, firstSeenAt = 2_000),
                    ),
                    backlog = listOf(
                        BacklogGameUi(20, "Your old", "", playtimeForever = 0, firstSeenAt = 1_000),
                        BacklogGameUi(21, "Your new", "", playtimeForever = 0, firstSeenAt = 2_000),
                        BacklogGameUi(22, "Your undated", "", playtimeForever = 0),
                    ),
                )
            }
            scrollTo("library-focus")
            compose.onAllNodesWithText("Name").onFirst().performClick()
            compose.onNodeWithText("First seen by Backlogium, not the Steam purchase date. Undated games stay last.").assertIsDisplayed()
            capture("${density.name}-added-sort-menu")
            compose.onNode(hasText("Added recently") and hasAnyAncestor(isPopup())).performClick()
            compose.runOnIdle {
                assertEquals(listOf(11L, 10L), library.value.goalGames.map { it.appId })
                assertEquals(LibrarySortKey.NAME, library.value.librarySort)
            }
            compose.onNodeWithContentDescription("Sorted newest first; tap for oldest first").performClick()
            scrollTo("library-backlog")
            compose.onNodeWithText("Name").performClick()
            compose.onNode(hasText("Added recently") and hasAnyAncestor(isPopup())).performClick()
            compose.runOnIdle {
                assertEquals(listOf(10L, 11L), library.value.goalGames.map { it.appId })
                assertEquals(listOf(21L, 20L, 22L), library.value.backlog.map { it.appId })
                assertEquals(LibrarySortDirection.ASCENDING, library.value.focusSortDirection)
                assertEquals(LibrarySortDirection.DESCENDING, library.value.librarySortDirection)
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "library-discovery").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
