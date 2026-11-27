package com.example.backlogium.ui.sync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.data.network.ConnectivityStatus
import com.example.backlogium.domain.*
import com.example.backlogium.ui.components.OfflineStatus
import com.example.backlogium.ui.components.OperationStatusRow
import com.example.backlogium.ui.settings.SyncCard
import com.example.backlogium.ui.setup.*
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.GenreEnrichmentStatus
import com.example.backlogium.work.setup.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SyncFeedbackDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineShellPersistsAcrossPushedContentAndLeavesLocalActionsUsable() {
        val status = mutableStateOf(ConnectivityStatus.UNKNOWN)
        compose.setContent { BacklogiumTheme {
            Scaffold(topBar = { OfflineStatus(status.value) }) { padding ->
                var detail by remember { mutableStateOf(false) }
                var favorite by remember { mutableStateOf(false) }
                Column(Modifier.padding(padding)) {
                    Text(if (detail) "Game detail" else "Library")
                    TextButton(onClick = { detail = !detail }) { Text(if (detail) "Back to library" else "Open game") }
                    TextButton(onClick = { favorite = !favorite }) { Text(if (favorite) "Favorite saved" else "Add favorite") }
                    OperationStatusRow("Steam sync", OperationProgress("failed", OperationStatus.FAILED))
                }
            }
        } }
        compose.onNodeWithText("Offline", substring = true).assertDoesNotExist()
        compose.runOnIdle { status.value = ConnectivityStatus.OFFLINE }
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Open game").performClick()
        compose.onNodeWithText("Game detail").assertIsDisplayed()
        compose.onNodeWithText("Offline", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add favorite").performClick()
        compose.onNodeWithText("Favorite saved").assertIsDisplayed()
        compose.runOnIdle { status.value = ConnectivityStatus.ONLINE }
        compose.onNodeWithText("Offline", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Steam sync — Could not complete; try again or check your Steam account").assertIsDisplayed()
    }

    @Test fun settingsRowsKeepIndependentResultsAndRecoveryActions() {
        var retries = 0
        compose.setContent { BacklogiumTheme {
            SyncCard(0, syncing = false, reconciling = true,
                steamOperation = OperationProgress("steam", OperationStatus.PARTIAL,
                    OperationPhase.ACHIEVEMENTS, OperationCounts(4, 4, 0, 1)),
                achievementOperation = OperationProgress("achievement", OperationStatus.QUEUED),
                genreStatus = GenreEnrichmentStatus.IDLE, onSyncNow = { retries++ }, onReconcileNow = {})
        } }
        compose.onNodeWithText("4 / 4 processed · 0 skipped · 1 failed").assertIsDisplayed()
        compose.onNodeWithText("Achievement refresh — Queued; waiting for background work").assertIsDisplayed()
        compose.onNodeWithText("Sync now").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun setupNamesInlineAchievementWorkWithoutStartingAnotherStage() {
        val stage = SetupStageUi("library_sync", "Sync your Steam library", "Fetches games and playtime.",
            SetupStageExecution.IN_SCREEN, null, true, SetupOutcome.NeverRun, true, null,
            operation = OperationProgress("admitted-library", OperationStatus.RUNNING,
                OperationPhase.ACHIEVEMENTS, OperationCounts(3, 8, 0, 1)))
        compose.setContent { BacklogiumTheme {
            SetupChecklist(SetupUiState(loading = false, running = true, stages = listOf(stage)),
                onToggle = { _, _ -> }, onRetry = {}, showRetry = true)
        } }
        compose.onNodeWithText("Sync your Steam library — Refreshing achievements").assertIsDisplayed()
        compose.onNodeWithText("3 / 8 processed · 0 skipped · 1 failed").assertIsDisplayed()
    }
}
