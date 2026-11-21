package com.example.backlogium.ui.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.backlogium.data.repo.HistoryImportReadiness
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryImportStepTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test
    fun skipIsDisabledWhileHistoryImportIsRunning() {
        var skipped = false
        compose.setContent {
            BacklogiumTheme {
                HistoryImportContent(
                    state = HistoryImportUiState(
                        readiness = HistoryImportReadiness.READY,
                        importing = true,
                    ),
                    onImport = {},
                    onSkip = { skipped = true },
                )
            }
        }

        compose.onNodeWithText("Skip").assertIsNotEnabled()
        assertFalse(skipped)
    }
}
