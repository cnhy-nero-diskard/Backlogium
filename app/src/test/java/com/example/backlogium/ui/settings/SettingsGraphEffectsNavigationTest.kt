package com.example.backlogium.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.example.backlogium.R
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.settingsGraphBackStackEntryOrNull
import com.example.backlogium.ui.util.HapticIntent
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsGraphEffectsNavigationTest {
    private val hapticIntents = MutableSharedFlow<HapticIntent>(extraBufferCapacity = 1)
    private val toastMessages = MutableSharedFlow<SettingsText>(extraBufferCapacity = 1)
    private val contributionExportRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val handledHaptics = mutableListOf<HapticIntent>()
    private val handledToasts = mutableListOf<String>()
    private val handledExports = mutableListOf<String>()

    private val content: @Composable () -> Unit = {
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val settingsGraphEntry = remember(navController, backStackEntry) {
            navController.settingsGraphBackStackEntryOrNull()
        }
        if (settingsGraphEntry != null) {
            SettingsGraphEffectCollectors(
                hapticIntents = hapticIntents,
                toastMessages = toastMessages,
                contributionExportRequests = contributionExportRequests,
                onHapticIntent = { handledHaptics += it },
                onToastMessage = { handledToasts += it },
                onContributionExportRequest = { handledExports += it },
            )
        }
        NavHost(navController = navController, startDestination = "home") {
            composable("home") {
                Button(onClick = { navController.navigate(SettingsRoutes.GRAPH) }) {
                    Text("Open Settings")
                }
            }
            navigation(
                startDestination = SettingsRoutes.OVERVIEW,
                route = SettingsRoutes.GRAPH,
            ) {
                composable(SettingsRoutes.OVERVIEW) {
                    Column {
                        Text("Settings overview")
                        Button(onClick = { navController.navigate(SettingsRoutes.ACCOUNT_SYNC) }) {
                            Text("Open detail")
                        }
                    }
                }
                composable(SettingsRoutes.ACCOUNT_SYNC) {
                    Column {
                        Text("Settings detail")
                        Button(onClick = { navController.navigate("diagnostics") }) {
                            Text("Open Diagnostics")
                        }
                        Button(onClick = { navController.popBackStack() }) {
                            Text("Back to overview")
                        }
                    }
                }
            }
            composable("diagnostics") {
                Column {
                    Text("Diagnostics")
                    Button(onClick = { navController.popBackStack("home", inclusive = false) }) {
                        Text("Leave Settings")
                    }
                }
            }
        }
    }

    init {
        ScreenshotTestActivity.recreationContent = content
    }

    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotTestActivity>()

    @After
    fun clearActivityRecreationContent() {
        ScreenshotTestActivity.recreationContent = null
    }

    @Test
    fun graphEffectsAreCollectedOnceDuringDefaultNavHostTransition() {
        composeRule.onNodeWithText("Open Settings").performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithText("Open detail").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("Settings overview").assertIsDisplayed()
        composeRule.onNodeWithText("Settings detail").assertIsDisplayed()

        composeRule.runOnIdle {
            assertTrue(hapticIntents.tryEmit(HapticIntent.Reject))
            assertTrue(toastMessages.tryEmit(SettingsText(R.string.settings_shared_game_toast_added)))
            assertTrue(contributionExportRequests.tryEmit("contribution.json"))
        }
        composeRule.waitForIdle()

        assertEquals(listOf(HapticIntent.Reject), handledHaptics)
        assertEquals(listOf("Family Shared game added."), handledToasts)
        assertEquals(listOf("contribution.json"), handledExports)
    }

    @Test
    fun graphEffectsRemainCollectedAfterActivityRecreationOnSettingsSibling() {
        composeRule.onNodeWithText("Open Settings").performClick()
        composeRule.onNodeWithText("Open detail").performClick()
        composeRule.onNodeWithText("Open Diagnostics").performClick()
        composeRule.onNodeWithText("Diagnostics").assertIsDisplayed()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Diagnostics").assertIsDisplayed()

        composeRule.runOnIdle {
            assertTrue(contributionExportRequests.tryEmit("contribution.json"))
        }
        composeRule.waitForIdle()
        assertEquals(listOf("contribution.json"), handledExports)

        composeRule.onNodeWithText("Leave Settings").performClick()
        composeRule.onNodeWithText("Open Settings").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(contributionExportRequests.tryEmit("after-settings-pop.json"))
        }
        composeRule.waitForIdle()
        assertEquals(listOf("contribution.json"), handledExports)
    }
}
