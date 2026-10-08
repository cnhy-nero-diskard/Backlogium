package com.example.backlogium.ui.library

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.ui.navigation.AppBottomNavigation
import com.example.backlogium.ui.navigation.Destination
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures production Library content and bar while checking viewport ownership and system Back. */
@RunWith(AndroidJUnit4::class)
class LibraryInsetCaptureTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun onlyTheFiveTopLevelRoutesShowTheirSelectedTab() {
        val route = mutableStateOf<String?>(null)
        composeRule.setContent {
            BacklogiumTheme {
                AppBottomNavigation(route.value, {}, Modifier.testTag("app-bottom-bar"))
            }
        }
        Destination.entries.forEach { destination ->
            composeRule.runOnUiThread { route.value = destination.route }
            composeRule.onNodeWithTag("app-bottom-bar").assertIsDisplayed()
            composeRule.onNodeWithText(destination.label).assertIsSelected()
        }
        listOf(
            null, "settings_graph", "onboarding", "setup", "hltb_review?appId={appId}",
            "diagnostics", "game_detail/{appId}", "collections", "collection/{collectionId}",
            "smart_collection/{collectionId}", "gap_plan", "settings/account-sync",
            "settings/gameplay", "settings/data-privacy", "settings/advanced", "hidden_games",
            "cloud_activity", "a_future_pushed_screen",
        ).forEach { pushedRoute ->
            composeRule.runOnUiThread { route.value = pushedRoute }
            composeRule.onNodeWithTag("app-bottom-bar").assertDoesNotExist()
        }
    }

    @Test fun finalItemsAndSystemBackRespectTheShellInBothThemesAndAtLargeFont() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "library-insets")
            .apply { mkdirs() }
        val density = mutableStateOf(GameListDensity.LIST)
        val dark = mutableStateOf(false)
        val fontScale = mutableStateOf(1f)
        var scaffoldBottomPx = 0
        var systemBottomPx = 0
        composeRule.setContent {
            val physicalDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(physicalDensity.density, fontScale.value),
            ) {
                BacklogiumTheme(darkTheme = dark.value) {
                    val navController = rememberNavController()
                    val entry by navController.currentBackStackEntryAsState()
                    val localDensity = LocalDensity.current
                    val navigationInsets = WindowInsets.navigationBars
                    Scaffold(
                        bottomBar = {
                            AppBottomNavigation(
                                entry?.destination?.route,
                                { navController.navigate(it.route) },
                                Modifier.testTag("app-bottom-bar"),
                            )
                        },
                    ) { innerPadding ->
                        SideEffect {
                            scaffoldBottomPx = with(localDensity) {
                                innerPadding.calculateBottomPadding().roundToPx()
                            }
                            systemBottomPx = navigationInsets.getBottom(localDensity)
                        }
                        NavHost(
                            navController,
                            startDestination = "library",
                            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
                        ) {
                            composable("library") {
                                LibraryContent(
                                    state = LibraryUiState(
                                        loading = false,
                                        libraryEmpty = false,
                                        density = density.value,
                                        backlog = (1..18).map { index ->
                                            BacklogGameUi(
                                                appId = index.toLong(),
                                                name = if (index == 18) "Fixture Final Game" else "Fixture Game $index",
                                                iconUrl = "",
                                                playtimeForever = index * 60,
                                            )
                                        },
                                    ),
                                    actions = LibraryContentActions(
                                        onOpenGameDetail = { navController.navigate("game_detail/$it") },
                                    ),
                                )
                            }
                            composable("game_detail/{appId}") {
                                Box(Modifier.fillMaxSize()) { Text("Pushed game detail") }
                            }
                        }
                    }
                }
            }
        }
        val metrics = mutableListOf<String>()
        listOf(false, true).forEach { darkTheme ->
            listOf(1f, 1.3f).forEach { scale ->
                GameListDensity.entries.forEach { mode ->
                    composeRule.runOnUiThread {
                        dark.value = darkTheme
                        fontScale.value = scale
                        density.value = mode
                    }
                    composeRule.waitForIdle()
                    composeRule.onNode(hasScrollAction())
                        .performScrollToNode(hasText("Fixture Final Game"))
                    val viewport = composeRule.onNode(hasScrollAction()).getBoundsInRoot()
                    val bar = composeRule.onNodeWithTag("app-bottom-bar").getBoundsInRoot()
                    assertEquals("Library viewport must reach the app bar", bar.top.value, viewport.bottom.value, 1f)
                    val finalItem = composeRule.onNode(hasText("Fixture Final Game") and hasClickAction())
                    finalItem.assertIsDisplayed()
                    val finalBounds = finalItem.getBoundsInRoot()
                    assertTrue("Final item must fit above the bar", finalBounds.bottom <= bar.top)
                    composeRule.onNodeWithText("Library").assertIsSelected()
                    val name = "${if (darkTheme) "dark" else "light"}-${if (scale > 1f) "large" else "standard"}-${mode.name.lowercase()}"
                    metrics += "$name: systemBottomPx=$systemBottomPx scaffoldBottomPx=$scaffoldBottomPx " +
                        "viewportBottom=${viewport.bottom} barTop=${bar.top} finalItemBottom=${finalBounds.bottom}"
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    File(output, "$name.png").outputStream().use {
                        screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    screenshot.recycle()
                    finalItem.performClick()
                    composeRule.onNodeWithText("Pushed game detail").assertIsDisplayed()
                    composeRule.onNodeWithTag("app-bottom-bar").assertDoesNotExist()
                    composeRule.runOnIdle {
                        assertEquals("Pushed screen retains only the system inset", systemBottomPx, scaffoldBottomPx)
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(
                        instrumentation.uiAutomation.executeShellCommand("input keyevent 4"),
                    ).use { it.readBytes() }
                    composeRule.waitUntil(timeoutMillis = 5_000) {
                        composeRule.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty()
                    }
                    composeRule.waitForIdle()
                    composeRule.onNodeWithText("Library").assertIsSelected()
                    composeRule.onNode(hasText("Fixture Final Game") and hasClickAction()).assertIsDisplayed()
                    val restoredViewport = composeRule.onNode(hasScrollAction()).getBoundsInRoot()
                    val restoredBar = composeRule.onNodeWithTag("app-bottom-bar").getBoundsInRoot()
                    assertEquals(restoredBar.top.value, restoredViewport.bottom.value, 1f)
                }
            }
        }
        File(output, "measurements.txt").writeText(metrics.joinToString("\n"))
    }
}
