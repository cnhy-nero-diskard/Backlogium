package com.example.backlogium.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.backlogium.ui.onboarding.HistoryChoiceContent
import com.example.backlogium.ui.onboarding.HistoryChoiceUiState
import com.example.backlogium.ui.settings.HistoryImportCard
import com.example.backlogium.ui.setup.SetupChecklist
import com.example.backlogium.ui.setup.SetupStageUi
import com.example.backlogium.ui.setup.SetupSummary
import com.example.backlogium.ui.setup.SetupUiState
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.setup.SetupOperationState
import com.example.backlogium.work.setup.SetupOutcome
import com.example.backlogium.work.setup.SetupStageExecution
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real stateless recovery surfaces, with deterministic domain facts and no Hilt/worker runtime. */
internal abstract class SetupRecoveryScreenshotBase : MainScreenshotTestBase() {
    protected abstract val viewport: String

    private fun capture(scene: String, dark: Boolean, content: @Composable () -> Unit) {
        composeRule.setContent {
            BacklogiumTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onRoot().captureRoboImage("setup/$scene/${if (dark) "dark" else "light"}/$viewport.png")
    }

    private fun mixed(dark: Boolean) = capture("settled-pending", dark) {
        val state = SetupUiState(loading = false, finished = true, stages = listOf(
            row("library_sync", "Sync your Steam library", SetupOperationState.RetryScheduled(1, "Waiting for the scheduled retry")),
            row("steam_assets", "Download game artwork", SetupOperationState.Succeeded("No artwork was available yet — run again after library sync.")),
            row("completion_times", "Fetch completion times", SetupOperationState.Failed("The dataset couldn't be downloaded. Check your connection.")),
        ))
        Text("Setup", style = MaterialTheme.typography.headlineSmall)
        SetupChecklist(state, { _, _ -> }, {}, true)
        SetupSummary(state)
    }

    private fun history(dark: Boolean, ready: Boolean) = capture(if (ready) "history-ready" else "history-needs-baseline", dark) {
        HistoryChoiceContent(HistoryChoiceUiState(baselineReady = ready), {}, {}, {}, {}, {})
    }

    private fun pending(dark: Boolean) = capture("settings-pending-import", dark) {
        Text("Data & privacy", style = MaterialTheme.typography.headlineSmall)
        HistoryImportCard(true, false, false, {}, {}, recomputePending = true, requestPending = true)
    }

    @Test fun settledPendingLight() = mixed(false)
    @Test fun settledPendingDark() = mixed(true)
    @Test fun historyReadyLight() = history(false, true)
    @Test fun historyReadyDark() = history(true, true)
    @Test fun historyNeedsBaselineLight() = history(false, false)
    @Test fun historyNeedsBaselineDark() = history(true, false)
    @Test fun settingsPendingLight() = pending(false)
    @Test fun settingsPendingDark() = pending(true)

    private fun row(id: String, title: String, operation: SetupOperationState) = SetupStageUi(
        id, title, "Optional setup work. You can continue while admitted work finishes.",
        SetupStageExecution.IN_SCREEN, null, false, SetupOutcome.NeverRun, false, null, operation,
    )
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = NARROW_SCREEN_QUALIFIERS)
internal class SetupRecoveryScreenshotNarrowTest : SetupRecoveryScreenshotBase() {
    override val viewport = "narrow"
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], qualifiers = STANDARD_SCREEN_QUALIFIERS)
internal class SetupRecoveryScreenshotStandardTest : SetupRecoveryScreenshotBase() {
    override val viewport = "standard"
}
