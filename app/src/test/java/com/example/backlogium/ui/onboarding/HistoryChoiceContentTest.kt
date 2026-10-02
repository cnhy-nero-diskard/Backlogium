package com.example.backlogium.ui.onboarding

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HistoryChoiceContentTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test fun readyChoiceDoesNotImplyConsentAndSkipImportsNothing() {
        var imports = 0
        var skips = 0
        compose.setContent {
            BacklogiumTheme {
                HistoryChoiceContent(HistoryChoiceUiState(baselineReady = true),
                    { imports++ }, {}, { skips++ }, {}, {})
            }
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Skip / do later").performClick()
        assertEquals(1, skips)
        assertEquals(0, imports)
    }

    @Test fun unavailableBaselineAllowsExitAndSetupRecoveryWithoutImport() {
        var imports = 0
        var skips = 0
        var reviews = 0
        compose.setContent {
            BacklogiumTheme { HistoryChoiceContent(HistoryChoiceUiState(),
                { imports++ }, {}, { skips++ }, {}, { reviews++ }) }
        }
        compose.onNodeWithText("Import history").assertIsNotEnabled()
        compose.onNodeWithText("Review library setup").performClick()
        compose.onNodeWithText("Skip / do later").performClick()
        assertEquals(1, reviews)
        assertEquals(1, skips)
        assertEquals(0, imports)
    }

    @Test fun existingImportOffersContinueRatherThanASecondImport() {
        var continues = 0
        compose.setContent {
            BacklogiumTheme { HistoryChoiceContent(HistoryChoiceUiState(imported = true),
                {}, {}, {}, { continues++ }, {}) }
        }
        compose.onNodeWithText("Import history").assertDoesNotExist()
        compose.onNodeWithText("Continue").performClick()
        assertEquals(1, continues)
    }

    @Test fun pendingRecomputeHasRecoveryAndAlwaysAllowsExplicitExit() {
        var retries = 0
        var skips = 0
        compose.setContent {
            BacklogiumTheme { HistoryChoiceContent(HistoryChoiceUiState(recomputePending = true),
                {}, { retries++ }, { skips++ }, {}, {}) }
        }
        compose.onNodeWithText("Resume import").performClick()
        compose.onNodeWithText("Skip / do later").performClick()
        assertEquals(1, retries)
        assertEquals(1, skips)
    }
}
