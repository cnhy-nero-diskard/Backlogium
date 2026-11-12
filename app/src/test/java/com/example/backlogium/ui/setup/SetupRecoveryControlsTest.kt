package com.example.backlogium.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStageActivity
import com.example.backlogium.work.setup.SetupStageProgress
import com.example.backlogium.work.setup.fakeStage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupRecoveryControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test
    fun retryIsUsableAfterFailure() {
        var retried: String? = null
        val rows = setupStagesUi(listOf(fakeStage("sync")), emptySet(), SetupRunState(
            loaded = true, finished = true, outcomes = mapOf("sync" to SetupOutcome.Failed("Offline"))))
        compose.setContent { BacklogiumTheme {
            SetupChecklist(SetupUiState(loading = false, stages = rows, finished = true),
                onToggle = { _, _ -> }, onRetry = { retried = it }, showRetry = true)
        } }
        compose.onNodeWithText("Retry").assertIsEnabled().performClick()
        assertEquals("sync", retried)
    }

    @Test
    fun continueRemainsEnabledDuringBackoff() {
        var continued = false
        val rows = setupStagesUi(listOf(fakeStage("sync")), emptySet(), SetupRunState(
            loaded = true, running = true, currentStageId = "sync",
            progress = SetupStageProgress(0, 0, activity = SetupStageActivity.RETRY_BACKOFF)))
        val state = SetupUiState(loading = false, stages = rows, running = true)
        compose.setContent { BacklogiumTheme { Column {
            SetupChecklist(state, onToggle = { _, _ -> }, onRetry = {}, showRetry = true)
            SetupActions(state, "Setting up…", onStart = {}, onSkip = { continued = true })
        } } }
        compose.onNodeWithText("Waiting to retry automatically").assertExists()
        compose.onNodeWithText("Continue").assertIsEnabled().performClick()
        assertEquals(true, continued)
    }
}
