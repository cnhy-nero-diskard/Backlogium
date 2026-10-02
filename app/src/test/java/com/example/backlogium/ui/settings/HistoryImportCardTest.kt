package com.example.backlogium.ui.settings

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.domain.HistoryImportResult
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
class HistoryImportCardTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test fun deferredChoiceStillOffersImportOnlyAfterExplicitConfirmation() {
        var imports = 0
        compose.setContent {
            BacklogiumTheme { HistoryImportCard(false, false, false, { imports++ }, {}, baselineReady = true) }
        }
        assertEquals(0, imports)
        compose.onNodeWithText("Import Steam history").performClick()
        assertEquals(0, imports)
        compose.onNodeWithText("Import", useUnmergedTree = true).performClick()
        assertEquals(1, imports)
    }

    @Test fun missingBaselineDoesNotCreateConsentAndOffersSetupRecovery() {
        var imports = 0
        var reviews = 0
        compose.setContent {
            BacklogiumTheme { HistoryImportCard(false, false, false, { imports++ }, {}, onOpenSetup = { reviews++ }) }
        }
        compose.onNodeWithText("Import Steam history").assertIsNotEnabled()
        compose.onNodeWithText("Review library setup").performClick()
        assertEquals(1, reviews)
        assertEquals(0, imports)
    }

    @Test fun rawCommittedPendingDoesNotClaimFullCompletionAndResumesExistingRequest() {
        var resumed = 0
        var imports = 0
        compose.setContent {
            BacklogiumTheme { HistoryImportCard(true, false, false, { imports++ }, {},
                recomputePending = true, requestPending = true, result = HistoryImportResult.PendingRecompute,
                onResume = { resumed++ }) }
        }
        compose.onNodeWithText("History imported").assertDoesNotExist()
        compose.onNodeWithText("Import Steam history").assertDoesNotExist()
        compose.onNodeWithText("Resume import").performClick()
        assertEquals(1, resumed)
        assertEquals(0, imports)
    }

    @Test fun cloudTransferStillBlocksResetUntilReversal() {
        var resets = 0
        compose.setContent {
            BacklogiumTheme { HistoryImportCard(true, false, true, {}, { resets++ }) }
        }
        compose.onNodeWithText("Reset import").assertIsNotEnabled()
        assertEquals(0, resets)
    }
}
