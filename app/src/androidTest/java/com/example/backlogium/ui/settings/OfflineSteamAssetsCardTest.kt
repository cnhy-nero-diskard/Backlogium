package com.example.backlogium.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import com.example.backlogium.data.local.entity.SteamAssetDownloadState
import com.example.backlogium.data.steamassets.SteamAssetDownloadMode
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.SteamAssetDownloadProgress
import com.example.backlogium.work.SteamAssetDownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class OfflineSteamAssetsCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        initial: SettingsUiState,
        onStart: (SteamAssetDownloadMode) -> Unit = {},
        onCancel: () -> Unit = {},
    ): androidx.compose.runtime.MutableState<SettingsUiState> {
        val state = mutableStateOf(initial)
        composeRule.setContent {
            BacklogiumTheme {
                OfflineSteamAssetsCard(
                    state = state.value,
                    onStart = onStart,
                    onCancel = onCancel,
                )
            }
        }
        return state
    }

    @Test
    fun defaultState_showsCardAsIndependentSection() {
        setContent(SettingsUiState())

        composeRule.onNodeWithTag("offline-steam-assets").assertIsDisplayed()
        composeRule.onNodeWithText("Offline Steam assets").assertIsDisplayed()
    }

    @Test
    fun emptyInventory_showsZeroSummaryHintAndDisabledButton() {
        setContent(
            SettingsUiState(
                storedSteamAssetCount = 0,
                storedSteamAssetBytes = 0L,
                lastSteamAssetRun = null,
                hasSteamAssetInventory = false,
            ),
        )

        composeRule.onNodeWithText("0 stored • 0 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Sync a Steam library first to discover images.").assertIsDisplayed()
        composeRule.onNodeWithText("Download Steam assets").assertIsNotEnabled()
    }

    @Test
    fun populatedInventory_showsSummaryAndLastRunAndEnablesButton() {
        val lastRun = SteamAssetDownloadState(
            mode = "DOWNLOAD_MISSING",
            completedAt = 1_000L,
            storedCount = 12,
            alreadyPresentCount = 3,
            unavailableCount = 1,
            failedCount = 2,
        )
        setContent(
            SettingsUiState(
                storedSteamAssetCount = 42,
                storedSteamAssetBytes = 10L * 1024 * 1024,
                lastSteamAssetRun = lastRun,
                hasSteamAssetInventory = true,
            ),
        )

        composeRule.onNodeWithText("42 stored • 10 MB").assertIsDisplayed()
        composeRule.onNodeWithText("Last run: 12 downloaded, 3 already present, 1 unavailable, 2 failed")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Download Steam assets").assertIsEnabled()
    }

    @Test
    fun downloadMissing_opensDialogAndInvokesOnStartWithDownloadMissing() {
        var started: SteamAssetDownloadMode? = null
        setContent(
            SettingsUiState(hasSteamAssetInventory = true),
            onStart = { started = it },
        )

        composeRule.onNodeWithText("Download Steam assets").performClick()
        composeRule.onNodeWithText("Download missing assets").performClick()
        composeRule.waitForIdle()

        assertEquals(SteamAssetDownloadMode.DOWNLOAD_MISSING, started)
        composeRule.onNodeWithText("Download missing assets").assertDoesNotExist()
    }

    @Test
    fun refreshAll_opensDialogAndInvokesOnStartWithRefreshAll() {
        var started: SteamAssetDownloadMode? = null
        setContent(
            SettingsUiState(hasSteamAssetInventory = true),
            onStart = { started = it },
        )

        composeRule.onNodeWithText("Download Steam assets").performClick()
        composeRule.onNodeWithText("Refresh all assets").performClick()
        composeRule.waitForIdle()

        assertEquals(SteamAssetDownloadMode.REFRESH_ALL, started)
        composeRule.onNodeWithText("Refresh all assets").assertDoesNotExist()
    }

    @Test
    fun dismissingDialogWithoutChoosing_invokesNeitherCallback() {
        var started: SteamAssetDownloadMode? = null
        setContent(
            SettingsUiState(hasSteamAssetInventory = true),
            onStart = { started = it },
        )

        composeRule.onNodeWithText("Download Steam assets").performClick()
        composeRule.onNodeWithText("Download missing assets").assertIsDisplayed()

        // AlertDialog's onDismissRequest is wired to back-press dismissal; neither of the
        // dialog's two buttons is pressed, so onStart must never fire.
        Espresso.pressBack()
        composeRule.waitForIdle()

        assertNull(started)
        composeRule.onNodeWithText("Download missing assets").assertDoesNotExist()
    }

    @Test
    fun activeStatuses_showDistinctStatusTextAndNoProgressWhenProgressIsNull() {
        val state = setContent(
            SettingsUiState(steamAssetStatus = SteamAssetDownloadStatus.QUEUED, steamAssetProgress = null),
        )

        composeRule.onNodeWithText("Queued for network and available storage").assertIsDisplayed()
        composeRule.onNodeWithText("Preparing image inventory").assertDoesNotExist()

        state.value = state.value.copy(steamAssetStatus = SteamAssetDownloadStatus.PREPARING)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Preparing image inventory").assertIsDisplayed()
        composeRule.onNodeWithText("Queued for network and available storage").assertDoesNotExist()
    }

    @Test
    fun runningWithProgress_showsProgressBarAndCounts() {
        setContent(
            SettingsUiState(
                steamAssetStatus = SteamAssetDownloadStatus.RUNNING,
                steamAssetProgress = SteamAssetDownloadProgress(
                    processed = 25,
                    total = 100,
                    label = "assets",
                    stored = 20,
                    alreadyPresent = 3,
                    unavailable = 1,
                    failed = 1,
                ),
            ),
        )

        composeRule.onNodeWithText("Downloading Steam assets").assertIsDisplayed()
        composeRule.onNodeWithText("25 / 100 • 20 downloaded, 1 unavailable, 1 failed").assertIsDisplayed()
    }

    @Test
    fun overlappingSteamSync_doesNotAffectAssetCardIdleState() {
        setContent(
            SettingsUiState(
                isSyncing = true,
                steamAssetStatus = SteamAssetDownloadStatus.IDLE,
                hasSteamAssetInventory = true,
            ),
        )

        composeRule.onNodeWithText("Download Steam assets").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("Stop download").assertDoesNotExist()
    }

    @Test
    fun activeState_stopButtonInvokesOnCancel() {
        var cancelled = false
        setContent(
            SettingsUiState(steamAssetStatus = SteamAssetDownloadStatus.RUNNING),
            onCancel = { cancelled = true },
        )

        composeRule.onNodeWithText("Stop download").performClick()
        composeRule.waitForIdle()

        assert(cancelled)
    }

    @Test
    fun terminalStatusAfterRunning_returnsToDownloadButton() {
        val state = setContent(
            SettingsUiState(
                steamAssetStatus = SteamAssetDownloadStatus.RUNNING,
                steamAssetProgress = SteamAssetDownloadProgress(
                    processed = 5,
                    total = 10,
                    label = "assets",
                    stored = 4,
                    alreadyPresent = 0,
                    unavailable = 1,
                    failed = 0,
                ),
                hasSteamAssetInventory = true,
            ),
        )

        composeRule.onNodeWithText("Stop download").assertIsDisplayed()

        state.value = state.value.copy(
            steamAssetStatus = SteamAssetDownloadStatus.FAILED,
            steamAssetProgress = null,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stop download").assertDoesNotExist()
        composeRule.onNodeWithText("Download Steam assets").assertIsDisplayed().assertIsEnabled()

        state.value = state.value.copy(steamAssetStatus = SteamAssetDownloadStatus.CANCELLED)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Stop download").assertDoesNotExist()
        composeRule.onNodeWithText("Download Steam assets").assertIsDisplayed().assertIsEnabled()
    }
}
