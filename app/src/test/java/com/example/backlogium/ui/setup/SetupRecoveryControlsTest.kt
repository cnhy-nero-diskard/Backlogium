package com.example.backlogium.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.backlogium.data.setup.SetupJournal
import com.example.backlogium.work.setup.FakeSetupAccounts
import com.example.backlogium.work.setup.fixtureSetupOwner
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.FakeSetupStateStore
import com.example.backlogium.work.setup.FakeStageSource
import com.example.backlogium.work.setup.FirstRunPhase
import com.example.backlogium.work.setup.SetupCoordinator
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStageActivity
import com.example.backlogium.work.setup.SetupStageProgress
import com.example.backlogium.work.setup.fakeStage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupRecoveryControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun skipCannotAdvanceFirstRunWhilePersistedSetupStateIsLoading() = runTest(
        StandardTestDispatcher(),
    ) {
        val loadGate = CompletableDeferred<Unit>()
        val store = FakeSetupStateStore(SetupJournal(fixtureSetupOwner), loadGate = loadGate)
        val subject = SetupCoordinator(
            FakeStageSource(listOf(fakeStage("sync"))),
            store,
            FakeSetupAccounts(),
            backgroundScope,
        )
        val testScope = this

        subject.claimFirstRunSetup()
        subject.ensureLoaded()
        runCurrent()
        assertEquals(false, subject.state.value.loaded)
        val loading = !subject.state.value.loaded

        compose.setContent {
            BacklogiumTheme {
                SetupActions(
                    state = SetupUiState(loading = loading),
                    startLabel = "Start setup",
                    onStart = {},
                    // Mirrors the pre-run onboarding action: decline, then advance to history.
                    onSkip = {
                        testScope.launch {
                            if (subject.skipAll()) subject.advanceToHistory()
                        }
                    },
                )
            }
        }

        compose.onNodeWithText("Skip setup")
            .assertIsNotEnabled()
            .performTouchInput { click() }
        advanceUntilIdle()

        assertEquals(emptyMap<String, SetupOutcome>(), store.outcomes)
        assertEquals(FirstRunPhase.SETUP, subject.firstRunPhase.first())

        loadGate.complete(Unit)
        runCurrent()
        assertEquals(true, subject.state.value.loaded)
        assertEquals(FirstRunPhase.SETUP, subject.firstRunPhase.first())
    }

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
    fun committedPartialStageOffersRetry() {
        var retried: String? = null
        val rows = setupStagesUi(listOf(fakeStage("sync")), emptySet(), SetupRunState(
            loaded = true, finished = true, outcomes = mapOf("sync" to SetupOutcome.Partial)))
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
