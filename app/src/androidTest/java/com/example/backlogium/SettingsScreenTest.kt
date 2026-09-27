package com.example.backlogium

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.CloudRoutineState
import com.example.backlogium.ui.settings.RuleDraft
import com.example.backlogium.ui.settings.RuleField
import com.example.backlogium.ui.settings.SettingsActions
import com.example.backlogium.ui.settings.SettingsDetailScreen
import com.example.backlogium.ui.settings.SettingsGroup
import com.example.backlogium.ui.settings.SettingsOverviewScreen
import com.example.backlogium.ui.settings.SettingsScreen
import com.example.backlogium.ui.settings.SettingsUiState
import com.example.backlogium.ui.settings.SETTINGS_INVENTORY
import com.example.backlogium.ui.settings.SettingsSection
import com.example.backlogium.ui.settings.settingsInventoryIssues
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
    fun allFourDetailDestinations_keepTheirTitleAndBackAffordance() {
        val titles = mapOf(
            SettingsGroup.ACCOUNT_SYNC to "Account & sync",
            SettingsGroup.GAMEPLAY to "Gameplay",
            SettingsGroup.DATA_PRIVACY to "Data & privacy",
            SettingsGroup.ADVANCED to "Advanced & diagnostics",
        )
        val selectedGroup = androidx.compose.runtime.mutableStateOf(SettingsGroup.ACCOUNT_SYNC)

        composeRule.setContent {
            SettingsDetailScreen(
                group = selectedGroup.value,
                state = state(advancedExpanded = false),
                actions = noopActions(),
            )
        }

        SettingsGroup.entries.forEachIndexed { index, group ->
            if (index > 0) {
                selectedGroup.value = group
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithTag("settings-detail-title")
                .assertIsDisplayed()
            composeRule.onNodeWithText(titles.getValue(group)).assertIsDisplayed()
            composeRule.onNodeWithTag("settings-back").assertIsDisplayed()
        }
    }

    @Test
    fun overviewLoadingAndAttention_usePlayerFacingStatusAndActionCopy() {
        val state = androidx.compose.runtime.mutableStateOf(SettingsUiState())
        composeRule.setContent {
            SettingsOverviewScreen(
                state = state.value,
                onOpenGroup = {},
            )
        }

        SettingsGroup.entries.forEach { group ->
            composeRule.onNodeWithTag("settings-group-${group.route.substringAfterLast('/')}")
                .assertIsDisplayed()
        }
        composeRule.onAllNodesWithText("Loading saved settings", substring = true)
            .assertCountEquals(SettingsGroup.entries.size)

        state.value = SettingsUiState(loading = false, configured = false)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Status: Your Steam account is not connected")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Next: Connect account").assertIsDisplayed()
    }

    @Test
    fun inventory_keepsEverySectionAndActionReachableExactlyOnce() {
        assertEquals(SettingsSection.entries.size, SETTINGS_INVENTORY.size)
        assertEquals(SettingsSection.entries.toSet(), SETTINGS_INVENTORY.map { it.section }.toSet())
        assertTrue(settingsInventoryIssues().isEmpty())
        SettingsGroup.entries.forEach { group ->
            assertTrue(SETTINGS_INVENTORY.any { it.group == group })
        }
    }

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
    fun manualOnlyKeepsReaderAndManualReadAvailableAndReenabledCadenceShowsEligibility() {
        val policy = androidx.compose.runtime.mutableStateOf(CloudRoutinePolicy.OFF_MANUAL_ONLY)
        val routine = CloudRoutineState(
            policy = CloudRoutinePolicy.OFF_MANUAL_ONLY,
            lastAdmittedAt = 1_758_000_000_000L,
            lastOutcome = CloudReadSummaryOutcome.FAILED,
        )
        val settings = state(advancedExpanded = false).copy(
            cloudEndpoint = "https://reader.example.com/read",
            cloudRoutine = routine,
        )
        val changedPolicies = mutableListOf<CloudRoutinePolicy>()
        composeRule.setContent {
            SettingsDetailScreen(
                group = SettingsGroup.DATA_PRIVACY,
                state = settings.copy(cloudRoutine = routine.copy(policy = policy.value)),
                actions = noopActions().copy(onCloudRoutinePolicyChanged = {
                    changedPolicies += it
                    policy.value = it
                }),
            )
        }

        composeRule.onNodeWithText("Read now").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Off · Manual only").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(CloudRoutinePolicy.OFF_MANUAL_ONLY, changedPolicies.last())
        assertEquals("https://reader.example.com/read", settings.cloudEndpoint)
        composeRule.onNodeWithText(
            "Automatic is the initial policy when a reader is first verified.", substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Routine catch-up is off.", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Last routine attempt:", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Next eligible after", substring = true).assertDoesNotExist()

        composeRule.onNodeWithText("How routine catch-up works").performScrollTo().performClick()
        composeRule.onNodeWithText("manual Read now", substring = true)
            .performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithText("At least 24 hours apart").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(CloudRoutinePolicy.DAILY, policy.value)
        composeRule.onNodeWithText("Next eligible after", substring = true)
            .performScrollTo().assertIsDisplayed()
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
}
