package com.example.backlogium.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.util.HapticIntent
import kotlinx.coroutines.flow.MutableSharedFlow
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
    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test
    fun graphEffectsAreCollectedOnceDuringDefaultNavHostTransition() {
        val hapticIntents = MutableSharedFlow<HapticIntent>(extraBufferCapacity = 1)
        val toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val contributionExportRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
        val handledHaptics = mutableListOf<HapticIntent>()
        val handledToasts = mutableListOf<String>()
        val handledExports = mutableListOf<String>()

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            val navController = rememberNavController()
            SettingsGraphEffectCollectors(
                hapticIntents = hapticIntents,
                toastMessages = toastMessages,
                contributionExportRequests = contributionExportRequests,
                onHapticIntent = { handledHaptics += it },
                onToastMessage = { handledToasts += it },
                onContributionExportRequest = { handledExports += it },
            )
            NavHost(
                navController = navController,
                startDestination = SettingsRoutes.GRAPH,
            ) {
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
                            Button(onClick = { navController.popBackStack() }) {
                                Text("Back to overview")
                            }
                        }
                    }
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Open detail").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithText("Settings overview").assertIsDisplayed()
        composeRule.onNodeWithText("Settings detail").assertIsDisplayed()

        composeRule.runOnIdle {
            assertTrue(hapticIntents.tryEmit(HapticIntent.Reject))
            assertTrue(toastMessages.tryEmit("Sync failed"))
            assertTrue(contributionExportRequests.tryEmit("contribution.json"))
        }
        composeRule.waitForIdle()

        assertEquals(listOf(HapticIntent.Reject), handledHaptics)
        assertEquals(listOf("Sync failed"), handledToasts)
        assertEquals(listOf("contribution.json"), handledExports)
    }
}
