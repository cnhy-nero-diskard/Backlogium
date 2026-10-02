package com.example.backlogium.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.SetupOperationState
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupStageExecution
import com.example.backlogium.work.setup.SetupStageProgress
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SetupRecoveryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    private fun row(id: String, operation: SetupOperationState) = SetupStageUi(
        id = id, title = id, detail = "Optional step", execution = SetupStageExecution.IN_SCREEN,
        unavailableReason = null, selected = false, outcome = SetupOutcome.NeverRun,
        running = operation is SetupOperationState.Running,
        progress = (operation as? SetupOperationState.Running)?.progress,
        operationState = operation,
    )

    @Test
    fun pendingRowsOfferObservationAndNeverClaimCompletion() {
        var observed: String? = null
        var retried: String? = null
        val state = SetupUiState(loading = false, finished = true, stages = listOf(
            row("Library", SetupOperationState.Waiting("Waiting for connectivity")),
            row("Dataset", SetupOperationState.RetryScheduled(1, "Backoff is respected")),
        ))
        compose.setContent {
            BacklogiumTheme {
                Column {
                    SetupChecklist(state, { _, _ -> }, { retried = it }, true, onReobserve = { observed = it })
                    SetupSummary(state)
                }
            }
        }
        compose.onNodeWithText("2 operations are still pending.").assertExists()
        compose.onNodeWithText("Setup complete").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onAllNodesWithText("View progress")[0].performClick()
        assertEquals("Library", observed)
        assertEquals(null, retried)
    }

    @Test
    fun retryTargetsOnlyTheFailedRowAndKeepsSuccessVisible() {
        val requests = mutableListOf<String>()
        val state = SetupUiState(loading = false, finished = true, stages = listOf(
            row("Library", SetupOperationState.Failed("Offline")),
            row("Dataset", SetupOperationState.Succeeded()),
        ))
        compose.setContent {
            BacklogiumTheme { SetupChecklist(state, { _, _ -> }, requests::add, true) }
        }
        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("Library"), requests)
        compose.onNodeWithContentDescription("Done").assertExists()
        compose.onNodeWithText("Run again").assertExists()
    }

    @Test
    fun cancellationAndMissingWorkHaveDistinctAccessibleStates() {
        val state = SetupUiState(loading = false, stages = listOf(
            row("Library", SetupOperationState.Cancelled),
            row("Dataset", SetupOperationState.RecoveryRequired("Saved work is no longer available")),
        ))
        compose.setContent {
            BacklogiumTheme { SetupChecklist(state, { _, _ -> }, {}, true) }
        }
        compose.onNodeWithContentDescription("Cancelled").assertExists()
        compose.onNodeWithContentDescription("Recovery required — request this step again.").assertExists()
        compose.onNodeWithText("Saved work is no longer available").assertExists()
    }

    @Test
    fun zeroTotalIsIndeterminateRatherThanZeroOverZero() {
        val state = SetupUiState(loading = false, running = true, stages = listOf(
            row("Library", SetupOperationState.Running(SetupStageProgress(0, 0))),
        ))
        compose.setContent {
            BacklogiumTheme { SetupChecklist(state, { _, _ -> }, {}, true) }
        }
        compose.onNodeWithText("0 / 0").assertDoesNotExist()
        compose.onAllNodes(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate,
        )).assertCountEquals(2)
    }

    @Test
    fun activeForegroundStillOffersContinueWithoutInvokingStart() {
        var continues = 0
        var starts = 0
        compose.setContent {
            BacklogiumTheme {
                SetupActions(SetupUiState(loading = false, running = true), "Setting up…",
                    onStart = { starts++ }, onSkip = { continues++ })
            }
        }
        compose.onNodeWithText("Continue / do later").performClick()
        assertEquals(1, continues)
        assertEquals(0, starts)
    }
}
