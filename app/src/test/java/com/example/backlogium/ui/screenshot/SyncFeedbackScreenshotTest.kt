package com.example.backlogium.ui.screenshot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import com.example.backlogium.ui.components.OfflineStatus
import com.example.backlogium.data.network.ConnectivityStatus
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.backlogium.domain.*
import com.example.backlogium.ui.settings.SyncCard
import com.example.backlogium.ui.setup.*
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.GenreEnrichmentStatus
import com.example.backlogium.work.setup.*
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

abstract class SyncFeedbackScreenshotBase(private val viewport: String) {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    @Test fun offlineLibraryLight() = capture("offline-library", false)
    @Test fun offlineLibraryDark() = capture("offline-library", true)
    @Test fun offlineDetailLight() = capture("offline-detail", false)
    @Test fun offlineDetailDark() = capture("offline-detail", true)
    @Test fun setupFetchingLight() = capture("setup-fetching", false)
    @Test fun setupFetchingDark() = capture("setup-fetching", true)
    @Test fun setupAchievementsLight() = capture("setup-achievements", false)
    @Test fun setupAchievementsDark() = capture("setup-achievements", true)
    @Test fun settingsOverlapLight() = capture("settings-overlap", false)
    @Test fun settingsOverlapDark() = capture("settings-overlap", true)
    @Test fun settingsPartialLight() = capture("settings-partial", false)
    @Test fun settingsPartialDark() = capture("settings-partial", true)

    private fun capture(scene: String, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent { BacklogiumTheme(darkTheme = dark) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(16.dp)) {
                    if (scene.startsWith("offline")) {
                        OfflineStatus(ConnectivityStatus.OFFLINE)
                        Box(Modifier.weight(1f)) {
                            MainScreenshotFixtureHost(MainScreenshotFixture(
                                if (scene == "offline-library") MainFixtureKind.LIBRARY_POPULATED else MainFixtureKind.DETAIL_OWNED,
                                darkTheme = dark))
                        }
                    } else if (scene.startsWith("setup")) {
                        val operation = OperationProgress("library", OperationStatus.RUNNING,
                            if (scene == "setup-fetching") OperationPhase.FETCHING_LIBRARY else OperationPhase.ACHIEVEMENTS,
                            if (scene == "setup-fetching") null else OperationCounts(3, 8, 0, 1))
                        val stage = fakeStage(SetupStageRegistry.STAGE_LIBRARY_SYNC).copy(
                            title = "Sync your Steam library", detail = "Fetches your games and playtime.")
                        val run = SetupRunState(loaded = true, running = true, pending = setOf(stage.id),
                            workIds = mapOf(stage.id to "library"))
                        SetupChecklist(SetupUiState(loading = false, running = true,
                            stages = setupStagesUi(listOf(stage), setOf(stage.id), run, libraryOperation = operation)),
                            onToggle = { _, _ -> }, onRetry = {}, showRetry = true)
                    } else {
                        val partial = scene == "settings-partial"
                        SyncCard(0, syncing = !partial, reconciling = !partial,
                            steamOperation = OperationProgress("library", if (partial) OperationStatus.SUCCEEDED else OperationStatus.RUNNING,
                                OperationPhase.FETCHING_LIBRARY),
                            achievementOperation = OperationProgress("achievements", if (partial) OperationStatus.PARTIAL else OperationStatus.QUEUED,
                                OperationPhase.ACHIEVEMENTS, if (partial) OperationCounts(8, 8, 1, 2) else null,
                                updated = if (partial) 2 else null),
                            genreStatus = GenreEnrichmentStatus.IDLE, onSyncNow = {}, onReconcileNow = {})
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        if (scene == "settings-partial") compose.onNodeWithText("2 games changed").assertExists()
        if (scene == "setup-fetching") compose.onNodeWithText("0 / 0 processed", substring = true).assertDoesNotExist()
        compose.onRoot().captureRoboImage("sync-feedback/$scene/${if (dark) "dark" else "light"}/$viewport.png")
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = NARROW_SCREEN_QUALIFIERS)
class SyncFeedbackScreenshotNarrowTest : SyncFeedbackScreenshotBase("narrow")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = STANDARD_SCREEN_QUALIFIERS)
class SyncFeedbackScreenshotStandardTest : SyncFeedbackScreenshotBase("standard")
