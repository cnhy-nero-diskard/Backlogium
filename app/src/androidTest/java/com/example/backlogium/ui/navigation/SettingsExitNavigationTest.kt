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
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.ui.settings.SettingsRoutes
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

    private fun setContent() = composeRule.setContent {
        navController = TestNavHostController(
            InstrumentationRegistry.getInstrumentation().targetContext,
        ).apply { navigatorProvider.addNavigator(ComposeNavigator()) }

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
                    composable(SettingsRoutes.OVERVIEW) { Text("Settings overview") }
                }
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
