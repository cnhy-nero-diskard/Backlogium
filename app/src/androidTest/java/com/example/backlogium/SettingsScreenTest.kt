package com.example.backlogium

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.settings.RuleDraft
import com.example.backlogium.ui.settings.RuleField
import com.example.backlogium.ui.settings.SettingsActions
import com.example.backlogium.ui.settings.SettingsScreen
import com.example.backlogium.ui.settings.SettingsUiState
import com.example.backlogium.ui.util.HapticIntent
import com.example.backlogium.ui.util.HapticPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Compose behavior of the Settings screen that state alone cannot express — chiefly that the
 * advanced rule controls are genuinely not composed while the section is collapsed, rather than
 * composed and hidden.
 */
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun advancedControls_areNotComposedUntilTheSectionIsExpanded() {
        var expanded = false
        val expansions = mutableListOf<Boolean>()

        composeRule.setContent {
            SettingsScreen(
                state = state(advancedExpanded = expanded),
                onEditCredentials = {},
                actions = noopActions().copy(
                    onAdvancedExpandedChanged = {
                        expansions += it
                        expanded = it
                    },
                ),
            )
        }

        // Collapsed: the advanced fields do not exist in the tree at all.
        RuleField.entries.filter { it.advanced }.forEach { field ->
            composeRule.onNodeWithText(field.label).assertDoesNotExist()
        }
        // The primary quest controls are unaffected by the collapse.
        composeRule.onNodeWithText(RuleField.QUEST_GOAL_MINUTES.label)
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("settings-advanced-toggle")
            .performScrollTo()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(true), expansions)
        assertTrue(expanded)
    }

    @Test
    fun advancedControls_areComposedOnceExpanded() {
        composeRule.setContent {
            SettingsScreen(
                state = state(advancedExpanded = true),
                onEditCredentials = {},
                actions = noopActions(),
            )
        }

        composeRule.onNodeWithText(RuleField.XP_PER_MINUTE.label).assertExists()
        composeRule.onNodeWithText(RuleField.LEVEL_BASE.label).assertExists()
        composeRule.onNodeWithText(RuleField.LEGENDARY_ACHIEVEMENT_XP.label).assertExists()
    }

    @Test
    fun manualSyncFailure_deliversRejectExactlyOnce() {
        val state = androidx.compose.runtime.mutableStateOf(state(advancedExpanded = false))
        val haptics = RecordingHapticPlayer()

        composeRule.setContent {
            SettingsScreen(
                state = state.value,
                onEditCredentials = {},
                actions = noopActions().copy(
                    onSyncNow = { state.value = state.value.copy(isSyncing = true) },
                ),
                haptics = haptics,
            )
        }

        composeRule.onNodeWithText("Sync now").performClick()
        composeRule.waitForIdle()
        state.value = state.value.copy(isSyncing = false, lastSyncError = "offline")
        composeRule.waitForIdle()

        assertEquals(listOf(HapticIntent.Reject), haptics.intents)
    }

    @Test
    fun backgroundSyncFailure_whileSettingsIsOpen_deliversNothing() {
        val state = androidx.compose.runtime.mutableStateOf(state(advancedExpanded = false))
        val haptics = RecordingHapticPlayer()

        composeRule.setContent {
            SettingsScreen(
                state = state.value,
                onEditCredentials = {},
                actions = noopActions(),
                haptics = haptics,
            )
        }

        state.value = state.value.copy(isSyncing = true)
        composeRule.waitForIdle()
        state.value = state.value.copy(isSyncing = false, lastSyncError = "offline")
        composeRule.waitForIdle()

        assertEquals(emptyList<HapticIntent>(), haptics.intents)
    }

    @Test
    fun successfulManualSync_deliversNoReject() {
        val state = androidx.compose.runtime.mutableStateOf(state(advancedExpanded = false))
        val haptics = RecordingHapticPlayer()

        composeRule.setContent {
            SettingsScreen(
                state = state.value,
                onEditCredentials = {},
                actions = noopActions().copy(
                    onSyncNow = { state.value = state.value.copy(isSyncing = true) },
                ),
                haptics = haptics,
            )
        }

        composeRule.onNodeWithText("Sync now").performClick()
        composeRule.waitForIdle()
        state.value = state.value.copy(isSyncing = false, lastSyncError = null)
        composeRule.waitForIdle()

        assertEquals(emptyList<HapticIntent>(), haptics.intents)
    }

    private fun state(advancedExpanded: Boolean) = SettingsUiState(
        loading = false,
        configured = true,
        steamId = "76561197960287930",
        apiKeyMasked = "••••1234",
        savedConfig = RuleConfig(),
        draft = RuleDraft.from(RuleConfig()),
        advancedExpanded = advancedExpanded,
    )

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
