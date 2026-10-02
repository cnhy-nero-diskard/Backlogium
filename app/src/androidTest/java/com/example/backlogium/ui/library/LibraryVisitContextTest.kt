package com.example.backlogium.ui.library

import android.os.SystemClock
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.ui.navigation.AppBottomNavigation
import com.example.backlogium.ui.navigation.navigateToTopLevelDestination
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class VisitHostViewModel : ViewModel() {
    val visit = LibraryVisitState()
}

/** Production visit effects and Library presentation, with fixture content and controlled elapsed time. */
@RunWith(AndroidJUnit4::class)
class LibraryVisitContextTest {
    private val elapsed = AtomicLong(1_000)
    private val density = mutableStateOf(GameListDensity.LIST)
    private val selection = mutableStateOf(false)
    private lateinit var visit: LibraryVisitState
    private lateinit var navigation: NavHostController
    private val games = (1L..80L).map {
        BacklogGameUi(
            appId = it, name = "Game %03d".format(it), iconUrl = "", playtimeForever = 0,
            genres = listOf(GameGenre("puzzle", "Puzzle")), isFamilyShared = true,
        )
    }
    private fun state(filters: LibraryFilters) = LibraryUiState(
        loading = false, libraryEmpty = false, filters = filters,
        backlog = games.filterByLibraryFilters(filters), density = density.value,
        availableGenres = listOf(GameGenre("puzzle", "Puzzle")), matchCenterCount = 1,
        librarySort = LibrarySortKey.NAME, selectionMode = selection.value,
    )

    @Composable private fun Host() {
        val holder: VisitHostViewModel = viewModel()
        val controller = rememberNavController()
        SideEffect { visit = holder.visit; navigation = controller }
        LibraryVisitEffects(controller, holder.visit, elapsed::get)
        val entry by controller.currentBackStackEntryAsState()
        BacklogiumTheme {
            Scaffold(bottomBar = {
                AppBottomNavigation(entry?.destination?.route, { controller.navigateToTopLevelDestination(it.route) })
            }) { padding ->
                NavHost(controller, startDestination = "home", modifier = Modifier.padding(padding)) {
                    composable("home") {
                        Button(onClick = { controller.navigate("game_detail") }) { Text("Home detail") }
                    }
                    composable("library") {
                        val filters by holder.visit.filters.collectAsStateWithLifecycle()
                        LibraryContent(
                            state = state(filters), visit = holder.visit,
                            actions = LibraryContentActions(
                                onOpenGameDetail = { controller.navigate("game_detail") },
                                onOpenReview = { controller.navigate("hltb_review") },
                                onSetQuery = { holder.visit.filters.value = filters.copy(query = it) },
                                onClearQuery = { holder.visit.filters.value = filters.copy(query = "") },
                                onClearFilters = { holder.visit.filters.value = LibraryFilters() },
                                onClearSelection = { selection.value = false },
                            ),
                        )
                    }
                    composable("game_detail") { Text("Detail child") }
                    composable("hltb_review") { Text("Review child") }
                    composable("history") { Text("History tab") }
                    composable("analytics") { Text("Analytics tab") }
                    composable("settings") { Text("Settings tab") }
                }
            }
        }
    }

    init { ScreenshotTestActivity.recreationContent = { Host() } }
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    @After fun cleanup() { ScreenshotTestActivity.recreationContent = null }

    private fun openFilteredLibrary(mode: GameListDensity) {
        compose.runOnIdle {
            density.value = mode
            navigation.navigateToTopLevelDestination("home")
            visit.filters.value = LibraryFilters("Game", setOf("puzzle"), true, true)
            navigation.navigateToTopLevelDestination("library")
        }
        compose.waitForIdle()
        val rows = libraryScrollItems(state(visit.filters.value), WishlistUiState())
        val index = rows.indexOfFirst { 40L in it.gameIds }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(index)
        compose.onNodeWithText("Game 040").assertIsDisplayed()
        compose.runOnIdle { assertTrue(visit.scrollAnchor?.gameId != null) }
    }

    private fun verifyRetained() {
        compose.onNodeWithText("Game 040").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(LibraryFilters("Game", setOf("puzzle"), true, true), visit.filters.value)
            assertEquals(LibrarySortKey.NAME, state(visit.filters.value).librarySort)
            assertEquals(density.value, state(visit.filters.value).density)
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNode(hasSetTextAction()).assertTextEquals("Game")
        for (label in listOf("Puzzle", "Not covered", "Family Shared")) {
            compose.onNode(hasText(label) and isSelected()).performScrollTo().assertIsDisplayed()
        }
    }

    private fun verifyExpired() {
        compose.runOnIdle { assertEquals(LibraryFilters(), visit.filters.value) }
        compose.onNodeWithText("Search games or genres").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, visit.scrollAnchor?.index) }
    }

    @Test fun detailAndReviewBackRetainEveryDensityRegardlessOfForegroundTime() {
        for (mode in GameListDensity.entries) {
            for (route in listOf("game_detail", "hltb_review")) {
                openFilteredLibrary(mode)
                compose.runOnIdle { navigation.navigate(route) }
                compose.waitForIdle()
                elapsed.addAndGet(600_000)
                Espresso.pressBack()
                capture("${mode.name}-$route-return")
                verifyRetained()
            }
        }
    }

    @Test fun tabReturnOnBothSidesOfBoundaryAndHomeDetailOrigin() {
        for (mode in GameListDensity.entries) {
            for (away in listOf(299_999L, 300_000L)) {
                openFilteredLibrary(mode)
                compose.onNodeWithText("Home").performClick()
                compose.onNodeWithText("Home detail").performClick()
                compose.runOnIdle { assertNotNull(visit.absenceStartedAt) }
                elapsed.addAndGet(away)
                Espresso.pressBack()
                compose.onNodeWithText("Library").performClick()
                capture("${mode.name}-tab-$away")
                if (away < 300_000) verifyRetained() else verifyExpired()
            }
        }
    }

    @Test fun realProcessBackgroundFromLibraryAndChildrenOnBothSidesOfBoundary() {
        for (mode in GameListDensity.entries) {
            for (route in listOf("library", "game_detail", "hltb_review")) {
                for (away in listOf(299_999L, 300_000L)) {
                    openFilteredLibrary(mode)
                    if (route != "library") compose.runOnIdle { navigation.navigate(route) }
                    compose.waitForIdle()
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                    // ProcessLifecycleOwner delays ON_STOP to ignore configuration recreation.
                    SystemClock.sleep(1_000)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        assertEquals(elapsed.get(), visit.absenceStartedAt)
                    }
                    elapsed.addAndGet(away)
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    compose.waitForIdle()
                    if (route != "library") Espresso.pressBack()
                    if (away < 300_000) verifyRetained() else verifyExpired()
                }
            }
        }
    }

    @Test fun earlierTabDepartureSurvivesRecreationAndLaterBackground() {
        openFilteredLibrary(GameListDensity.COMPACT_GRID)
        compose.onNodeWithText("Home").performClick()
        val departure = elapsed.get()
        elapsed.addAndGet(200_000)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertEquals(departure, visit.absenceStartedAt) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(1_000)
        elapsed.addAndGet(100_000)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Library").performClick()
        verifyExpired()
    }

    @Test fun recreationRetainsContextAndClearAndSelectionKeepTheirContracts() {
        openFilteredLibrary(GameListDensity.GRID)
        val original = visit
        compose.activityRule.scenario.recreate()
        verifyRetained()
        compose.runOnIdle { assertSame(original, visit) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
        compose.onNodeWithText("Clear all filters").performClick()
        compose.runOnIdle {
            assertEquals(LibraryFilters(), visit.filters.value)
            selection.value = true
        }
        compose.onNodeWithText("Home").performClick()
        compose.runOnIdle { assertFalse(selection.value) }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "library-visits").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
