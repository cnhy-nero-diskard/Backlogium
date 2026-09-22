package com.example.backlogium.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import com.example.backlogium.gamification.RuleConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsOverviewScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun loadingKeepsFourRowsAndDoesNotExposeResolvedAction() {
        composeRule.setContent {
            SettingsOverviewScreen(
                state = SettingsUiState(),
                onOpenGroup = {},
            )
        }

        SettingsGroup.entries.forEach { group ->
            composeRule.onNodeWithTag("settings-group-${group.route.substringAfterLast('/')}")
                .assertIsDisplayed()
        }
        composeRule.onNodeWithText("Loading saved settings…", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Connect account").assertDoesNotExist()
    }

    @Test
    fun rowsExposePlainLanguageStatusAndNextAction() {
        composeRule.setContent {
            SettingsOverviewScreen(
                state = SettingsUiState(
                    loading = false,
                    configured = true,
                    cloudHealthy = true,
                    savedConfig = RuleConfig(),
                    draft = RuleDraft.from(RuleConfig()),
                ),
                onOpenGroup = {},
            )
        }

        composeRule.onNodeWithText("Account & sync").assertIsDisplayed()
        composeRule.onNodeWithText("Status: Your account and sync are ready").assertIsDisplayed()
        composeRule.onNodeWithText("Next: Manage account & sync").assertIsDisplayed()
        composeRule.onNodeWithText("Status: Your data tools are quiet and healthy").assertIsDisplayed()
        composeRule.onNodeWithText("Next: Manage data & privacy").assertIsDisplayed()
    }

    @Test
    fun openingGroupInvokesOnlyThatGroupDestination() {
        var opened: SettingsGroup? = null
        composeRule.setContent {
            SettingsOverviewScreen(
                state = SettingsUiState(loading = false),
                onOpenGroup = { opened = it },
            )
        }

        composeRule.onNodeWithTag("settings-group-data-privacy").performClick()
        assertEquals(SettingsGroup.DATA_PRIVACY, opened)
    }
}
