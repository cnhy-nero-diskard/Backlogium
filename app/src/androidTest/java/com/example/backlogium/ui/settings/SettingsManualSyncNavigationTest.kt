package com.example.backlogium.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.testing.TestNavHostController
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.ui.util.HapticPlayer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsManualSyncNavigationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openingLoadingGroupKeepsTitleAndBackActionAvailable() {
        composeRule.setContent {
            val navController = remember {
                TestNavHostController(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            }

            NavHost(
                navController = navController,
                startDestination = SettingsRoutes.GRAPH,
                modifier = Modifier.fillMaxSize(),
            ) {
                navigation(
                    startDestination = SettingsRoutes.OVERVIEW,
                    route = SettingsRoutes.GRAPH,
                ) {
                    composable(SettingsRoutes.OVERVIEW) {
                        SettingsOverviewScreen(
                            state = SettingsUiState(loading = true),
                            onOpenGroup = { group -> navController.navigate(group.route) },
                        )
                    }
                    composable(SettingsRoutes.GAMEPLAY) {
                        SettingsDetailScreen(
                            group = SettingsGroup.GAMEPLAY,
                            state = SettingsUiState(loading = true),
                            actions = noopActions(),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("settings-group-gameplay").performClick()
        composeRule.onNodeWithTag("settings-detail-title").assertIsDisplayed()
        composeRule.onNodeWithText("Gameplay").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-back").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("settings-overview-title").assertIsDisplayed()
    }

    @Test
    fun manualSyncFailureAfterReturningToOverviewStillDeliversReject() {
        val haptics = RecordingHapticPlayer()

        composeRule.setContent {
            val navController = remember {
                TestNavHostController(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                    navigatorProvider.addNavigator(ComposeNavigator())
                }
            }
            // This holder has the same graph lifetime as SettingsViewModel in the app.
            val manualSyncFeedback = remember { ManualSyncFeedbackTracker() }
            var isSyncing by remember { mutableStateOf(false) }
            var lastSyncError by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(isSyncing, lastSyncError) {
                if (manualSyncFeedback.onSyncStateChanged(isSyncing, lastSyncError)) {
                    haptics.play(HapticIntent.Reject)
                }
            }

            NavHost(
                navController = navController,
                startDestination = SettingsRoutes.GRAPH,
                modifier = Modifier.fillMaxSize(),
            ) {
                navigation(
                    startDestination = SettingsRoutes.OVERVIEW,
                    route = SettingsRoutes.GRAPH,
                ) {
                    composable(SettingsRoutes.OVERVIEW) {
                        Column {
                            Text("Settings overview")
                            Button(onClick = { navController.navigate(SettingsRoutes.ACCOUNT_SYNC) }) {
                                Text("Account & sync")
                            }
                            if (isSyncing) {
                                Button(
                                    onClick = {
                                        isSyncing = false
                                        lastSyncError = "offline"
                                    },
                                ) { Text("Fail sync") }
                            }
                        }
                    }
                    composable(SettingsRoutes.ACCOUNT_SYNC) {
                        SettingsDetailScreen(
                            group = SettingsGroup.ACCOUNT_SYNC,
                            state = SettingsUiState(
                                loading = false,
                                configured = true,
                                savedConfig = RuleConfig(),
                                draft = RuleDraft.from(RuleConfig()),
                            ).copy(isSyncing = isSyncing, lastSyncError = lastSyncError),
                            actions = noopActions().copy(
                                onSyncNow = {
                                    manualSyncFeedback.onManualSyncStarted()
                                    isSyncing = true
                                },
                            ),
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Account & sync").performClick()
        composeRule.onNodeWithText("Sync now").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings-back").performClick()
        composeRule.onNodeWithText("Settings overview").assertIsDisplayed()
        composeRule.onNodeWithText("Fail sync").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(HapticIntent.Reject), haptics.intents)
    }

    private fun noopActions() = SettingsActions(
        onSyncNow = {},
        onReconcileNow = {},
        onFieldChanged = { _, _ -> },
        onQuestModeChanged = {},
        onAdvancedExpandedChanged = {},
        onRequestSave = {},
        onDiscardChanges = {},
        onConfirmSave = {},
        onDismissConfirmation = {},
        onLiveMonitorEnabledChanged = {},
        onImportHistory = {},
        onResetHistoryImport = {},
        onAutoSnapshotEnabledChanged = {},
        onSnapshotRetentionCountChanged = {},
        onSnapshotIntervalHoursChanged = {},
        onExportBackup = {},
        onImportBackup = {},
        onRestoreSnapshot = {},
        onDeleteSnapshot = {},
        onConfirmMismatchImport = {},
        onDismissMismatchImport = {},
        onDismissBackupMessage = {},
    )

    private class RecordingHapticPlayer : HapticPlayer {
        val intents = mutableListOf<HapticIntent>()

        override fun play(intent: HapticIntent) {
            intents += intent
        }
    }
}
