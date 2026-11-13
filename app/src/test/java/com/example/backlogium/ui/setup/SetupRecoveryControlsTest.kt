package com.example.backlogium.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.backlogium.data.setup.ActiveSetupStage
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.FakeSetupStateStore
import com.example.backlogium.work.setup.FakeStageRunner
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
        val runner = FakeStageRunner().also { it.autoComplete = false }
        val active = ActiveSetupStage("sync", null, setOf("sync"))
        val store = FakeSetupStateStore(
            initialOutcomes = mutableMapOf("sync" to SetupOutcome.NeverRun),
            activeStage = active,
            storedOutcomesGate = loadGate,
        )
        val subject = SetupCoordinator(
            FakeStageSource(listOf(fakeStage("sync", runner))),
            store,
            this,
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

        assertEquals(mapOf("sync" to SetupOutcome.NeverRun), store.outcomes)
        assertEquals(active, store.active)
        assertEquals(FirstRunPhase.SETUP, subject.firstRunPhase.first())

        // Let the persisted marker recover normally, then finish the held stage so the test scope
        // has no suspended work left behind.
        loadGate.complete(Unit)
        runCurrent()
        assertEquals(true, subject.state.value.running)
        assertEquals(SetupOutcome.NeverRun, store.outcomes["sync"])
        assertEquals(FirstRunPhase.SETUP, subject.firstRunPhase.first())

        runner.gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SetupOutcome.Succeeded, store.outcomes["sync"])
        assertEquals(FirstRunPhase.HISTORY, subject.firstRunPhase.first())
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
