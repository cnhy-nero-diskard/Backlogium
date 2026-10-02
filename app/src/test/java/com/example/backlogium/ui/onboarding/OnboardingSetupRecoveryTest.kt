package com.example.backlogium.ui.onboarding

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.runtime.mutableStateOf
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.setup.SetupStageUi
import com.example.backlogium.ui.setup.SetupUiState
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupStageExecution
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The onboarding setup surface's recovery contract, rendered through the real stateless screen
 * content ([OnboardingSetupContent]) so assertions are about what a first-run user actually sees.
 *
 * Desired contract (delta `first-run-setup`): after the foreground attempt settles, a terminally
 * failed stage offers a per-stage Retry. The current onboarding step passes `showRetry = false`, so
 * this test is the baseline red that pins the hidden-Retry defect down before the behavior lands.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnboardingSetupRecoveryTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test
    fun editedSelectionAfterSettlementOffersTheNextStartWithoutErasingTheResult() {
        val state = mutableStateOf(SetupUiState(loading = false, finished = true, inScreenSettled = true,
            stages = listOf(SetupStageUi("library_sync", "Library", "Fetch games",
                SetupStageExecution.IN_SCREEN, null, false, SetupOutcome.Succeeded, false, null))))
        var requested: Set<String>? = null
        composeRule.setContent {
            BacklogiumTheme {
                OnboardingSetupContent(state.value,
                    onToggle = { id, selected -> state.value = state.value.copy(stages = state.value.stages.map {
                        if (it.id == id) it.copy(selected = selected) else it
                    }) }, onRetry = {}, onStart = {
                        requested = state.value.stages.filter { it.selected }.map { it.id }.toSet()
                    }, onSkip = {}, onDone = {})
            }
        }
        composeRule.onNodeWithContentDescription("Library").performSemanticsAction(SemanticsActions.OnClick) { it() }
        composeRule.onNodeWithContentDescription("Library").assertIsOn()
        composeRule.onNodeWithText("Run selected steps").performClick()
        org.junit.Assert.assertEquals(setOf("library_sync"), requested)
        composeRule.onNodeWithContentDescription("Done").assertExists()
    }

    @Test
    fun aFailedStageAfterTheRunHasSettledOffersRetry() {
        val state = SetupUiState(
            loading = false,
            stages = listOf(
                SetupStageUi(
                    id = "library_sync",
                    title = "Sync your Steam library",
                    detail = "Fetches your games and playtime.",
                    execution = SetupStageExecution.IN_SCREEN,
                    unavailableReason = null,
                    selected = false,
                    outcome = SetupOutcome.Failed("Couldn't reach Steam."),
                    running = false,
                    progress = null,
                ),
            ),
            running = false,
            finished = true,
            inScreenSettled = true,
        )

        composeRule.setContent {
            BacklogiumTheme {
                OnboardingSetupContent(
                    state = state,
                    onToggle = { _, _ -> },
                    onRetry = { },
                    onStart = { },
                    onSkip = { },
                    onDone = { },
                )
            }
        }

        composeRule.onNodeWithText("Retry").assertExists()
    }
}
