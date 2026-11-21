package com.example.backlogium.ui.home

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.ui.navigation.AppBottomNavigation
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Rule
import org.junit.Test

class HomeFirstRunNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun unconfiguredHomeCanBeReachedWithSystemBackAndSetupCanBeReopened() {
        exerciseFirstRun(HomeUiState(loading = false, configured = false))
    }

    @Test fun interruptedSetupResumesOnItsOwnRouteAndBackLeavesTheTabsReachable() {
        exerciseFirstRun(HomeUiState(loading = false, configured = true, firstRunSetupActive = true))
    }

    private fun exerciseFirstRun(readyState: HomeUiState) {
        val state = mutableStateOf(HomeUiState())
        composeRule.setContent {
            BacklogiumTheme {
                val navController = rememberNavController()
                val entry by navController.currentBackStackEntryAsState()
                Scaffold(bottomBar = {
                    AppBottomNavigation(
                        entry?.destination?.route,
                        { navController.navigate(it.route) },
                        Modifier.testTag("app-bottom-bar"),
                    )
                }) { padding ->
                    NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
                        composable("home") {
                            val openOnboarding = { navController.navigate("onboarding"); Unit }
                            HomeFirstRunNavigation(state.value, openOnboarding)
                            HomeContent(state.value, HomeContentActions(onOpenOnboarding = openOnboarding))
                        }
                        composable("onboarding") { Text("Credential onboarding") }
                        composable("library") { Text("Unconfigured Library guidance") }
                    }
                }
            }
        }
        composeRule.runOnUiThread { state.value = readyState }
        composeRule.onNodeWithText("Credential onboarding").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bottom-bar").assertDoesNotExist()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4"))
            .use { it.readBytes() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Continue setup").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Home").assertIsSelected()
        composeRule.onNodeWithText("Continue setup").performClick()
        composeRule.onNodeWithText("Credential onboarding").assertIsDisplayed()
        composeRule.onNodeWithTag("app-bottom-bar").assertDoesNotExist()
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("input keyevent 4"))
            .use { it.readBytes() }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Continue setup").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Library").performClick()
        composeRule.onNodeWithText("Unconfigured Library guidance").assertIsDisplayed()
        composeRule.onNodeWithText("Library").assertIsSelected()
    }
}
