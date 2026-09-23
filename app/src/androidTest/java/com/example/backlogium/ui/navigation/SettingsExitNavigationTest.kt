package com.example.backlogium.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.ui.settings.SettingsRoutes
import com.example.backlogium.ui.settingsGraphBackStackEntryOrNull
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class SettingsExitNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    @Test
    fun libraryScrollPositionIsRestoredAfterVisitingSettings() {
        setContent()

        composeRule.onNodeWithText("Library tab").performClick()
        composeRule.onNodeWithTag("library-list").performScrollToIndex(35)
        composeRule.onNodeWithTag("library-game-35").assertIsDisplayed()

        composeRule.onNodeWithText("Settings tab").performClick()
        composeRule.onNodeWithText("Settings overview").assertIsDisplayed()

        composeRule.onNodeWithText("Library tab").performClick()

        composeRule.onNodeWithTag("library-game-35").assertIsDisplayed()
    }

    @Test
    fun settingsGraphViewModelIsClearedWhenLeavingFromDiagnosticsAndLibraryStateRestores() {
        assertSettingsGraphIsClearedWhenLeavingFrom("Diagnostics")
    }

    @Test
    fun settingsGraphViewModelIsClearedWhenLeavingFromSetupAndLibraryStateRestores() {
        assertSettingsGraphIsClearedWhenLeavingFrom("Setup")
    }

    private fun assertSettingsGraphIsClearedWhenLeavingFrom(destination: String) {
        SettingsGraphViewModel.clearCount.set(0)
        setContent()

        composeRule.onNodeWithText("Library tab").performClick()
        composeRule.onNodeWithTag("library-list").performScrollToIndex(35)
        composeRule.onNodeWithTag("library-game-35").assertIsDisplayed()

        composeRule.onNodeWithText("Settings tab").performClick()
        composeRule.onNodeWithText("Open $destination").performClick()
        composeRule.onNodeWithText(destination).assertIsDisplayed()
        composeRule.runOnIdle {
            val settingsGraphEntry = navController.settingsGraphBackStackEntryOrNull()
            assertNotNull(settingsGraphEntry)
            ViewModelProvider(requireNotNull(settingsGraphEntry))
                .get(SettingsGraphViewModel::class.java)
        }

        composeRule.onNodeWithText("Library tab").performClick()
        composeRule.onNodeWithTag("library-game-35").assertIsDisplayed()
        composeRule.runOnIdle {
            assertNull(navController.settingsGraphBackStackEntryOrNull())
            assertEquals(1, SettingsGraphViewModel.clearCount.get())
        }
    }

    private fun setContent() = composeRule.setContent {
        navController = TestNavHostController(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).apply {
            navigatorProvider.addNavigator(ComposeNavigator())
            setViewModelStore(ViewModelStore())
        }

        Column(Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Destination.HOME.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(Destination.HOME.route) { Text("Home") }
                composable(Destination.LIBRARY.route) {
                    LazyColumn(Modifier.fillMaxSize().testTag("library-list")) {
                        items((0 until 60).toList()) { index ->
                            Text("Game $index", modifier = Modifier.testTag("library-game-$index"))
                        }
                    }
                }
                navigation(
                    startDestination = SettingsRoutes.OVERVIEW,
                    route = SettingsRoutes.GRAPH,
                ) {
                    composable(SettingsRoutes.OVERVIEW) {
                        Column {
                            Text("Settings overview")
                            TextButton(onClick = { navController.navigate("diagnostics") }) {
                                Text("Open Diagnostics")
                            }
                            TextButton(onClick = { navController.navigate("setup") }) {
                                Text("Open Setup")
                            }
                        }
                    }
                }
                composable("diagnostics") { Text("Diagnostics") }
                composable("setup") { Text("Setup") }
            }

            Row {
                TextButton(
                    onClick = {
                        navController.navigateToTopLevelDestination(Destination.LIBRARY.route)
                    },
                ) { Text("Library tab") }
                TextButton(
                    onClick = {
                        navController.navigateToTopLevelDestination(Destination.SETTINGS.route)
                    },
                ) { Text("Settings tab") }
            }
        }
    }
}

class SettingsGraphViewModel : ViewModel() {
    override fun onCleared() {
        super.onCleared()
        clearCount.incrementAndGet()
    }

    companion object {
        val clearCount = AtomicInteger()
    }
}
