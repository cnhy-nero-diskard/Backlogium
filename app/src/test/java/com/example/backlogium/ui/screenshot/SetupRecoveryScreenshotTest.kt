package com.example.backlogium.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.backlogium.data.repo.HistoryImportReadiness
import com.example.backlogium.ui.onboarding.HistoryImportContent
import com.example.backlogium.ui.onboarding.HistoryImportUiState
import com.example.backlogium.ui.setup.SetupActions
import com.example.backlogium.ui.setup.SetupChecklist
import com.example.backlogium.ui.setup.SetupUiState
import com.example.backlogium.ui.setup.setupStagesUi
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupRunState
import com.example.backlogium.work.setup.SetupStageActivity
import com.example.backlogium.work.setup.SetupStageProgress
import com.example.backlogium.work.setup.fakeStage
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class SetupRecoveryScreenshotBase(private val viewport: String) {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    @Test fun waitingLight() = capture("waiting", false)
    @Test fun waitingDark() = capture("waiting", true)
    @Test fun backoffLight() = capture("backoff", false)
    @Test fun backoffDark() = capture("backoff", true)
    @Test fun failedLight() = capture("failed", false)
    @Test fun failedDark() = capture("failed", true)
    @Test fun recoveryLight() = capture("recovery", false)
    @Test fun recoveryDark() = capture("recovery", true)
    @Test fun historyReadyLight() = capture("history-ready", false)
    @Test fun historyReadyDark() = capture("history-ready", true)
    @Test fun historyNotReadyLight() = capture("history-not-ready", false)
    @Test fun historyNotReadyDark() = capture("history-not-ready", true)
    @Test fun historyErrorLight() = capture("history-error", false)
    @Test fun historyErrorDark() = capture("history-error", true)

    private fun capture(scene: String, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { BacklogiumTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (scene.startsWith("history")) {
                        Text("Bring your past playtime", style = MaterialTheme.typography.headlineSmall)
                        HistoryImportContent(HistoryImportUiState(
                            readiness = if (scene == "history-not-ready") HistoryImportReadiness.NEEDS_SYNC else HistoryImportReadiness.READY,
                            error = if (scene == "history-error") "History import didn't finish. Try again." else null,
                        ), onImport = {}, onSkip = {})
                    } else {
                        Text("Set up your library", style = MaterialTheme.typography.headlineSmall)
                        val running = scene == "waiting" || scene == "backoff"
                        val run = SetupRunState(loaded = true, running = running, finished = !running,
                            currentStageId = if (running) "sync" else null, selected = setOf("sync"),
                            progress = SetupStageProgress(0, 0, activity = if (scene == "backoff")
                                SetupStageActivity.RETRY_BACKOFF else SetupStageActivity.WAITING),
                            outcomes = mapOf("sync" to if (running) SetupOutcome.NeverRun else
                                SetupOutcome.Failed("Steam did not return a readable library. Check your profile privacy, then retry.")))
                        val state = SetupUiState(loading = false, running = running, finished = !running,
                            editing = scene == "recovery", stages = setupStagesUi(listOf(
                                fakeStage("sync").copy(title = "Sync your Steam library", detail = "Fetches your games and playtime.")),
                                setOf("sync"), run, editing = scene == "recovery"))
                        SetupChecklist(state, onToggle = { _, _ -> }, onRetry = {}, showRetry = true)
                        SetupActions(state, "Run selected steps", onStart = {}, onSkip = {})
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        if (scene.startsWith("history")) {
            compose.onNodeWithText("Skip").assertIsDisplayed().assertIsEnabled()
            if (scene == "history-not-ready") compose.onNodeWithText("Import Steam history").assertIsNotEnabled()
        }
        if (scene == "recovery") compose.onNodeWithText("Run selected steps").assertIsEnabled()
        compose.onRoot().captureRoboImage("setup/$scene/${if (dark) "dark" else "light"}/$viewport.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = NARROW_SCREEN_QUALIFIERS)
class SetupRecoveryScreenshotNarrowTest : SetupRecoveryScreenshotBase("narrow")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = STANDARD_SCREEN_QUALIFIERS)
class SetupRecoveryScreenshotStandardTest : SetupRecoveryScreenshotBase("standard")
