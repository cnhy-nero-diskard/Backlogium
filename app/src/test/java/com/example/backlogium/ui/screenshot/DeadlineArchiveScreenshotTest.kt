package com.example.backlogium.ui.screenshot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.DeadlineArchive
import com.example.backlogium.domain.DeadlineArchivedMember
import com.example.backlogium.domain.DeadlineCollectionIdentity
import com.example.backlogium.domain.DeadlinePlayBounds
import com.example.backlogium.domain.deadlineCutoff
import com.example.backlogium.ui.collections.FinishDeadlineDialog
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = NARROW_SCREEN_QUALIFIERS)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeadlineArchiveScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()

    @Test
    fun previewShowsFrozenEvidenceAndCanBeCancelled() {
        val cutoff = deadlineCutoff("2026-11-24", "Asia/Manila")
        val archive = DeadlineArchive(
            DeadlineCollectionIdentity(1, cutoff - 100), "November backlog", "2026-11-24",
            "Asia/Manila", CollectionTimeBasis.MAIN_STORY, cutoff, cutoff + 20,
            cutoff - 100, true,
            listOf(DeadlineArchivedMember(
                appId = 10, name = "Private game", hiddenAtCutoff = false,
                membershipObservedAt = cutoff - 20, estimateMinutes = 90,
                play = null, recordedSessions = 2, recordedTrophies = null,
                playBounds = DeadlinePlayBounds(30, 45, cutoff - 10, cutoff + 10),
            )),
        )
        var confirmed = false
        compose.setContent {
            BacklogiumTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    var preview by remember { mutableStateOf(true) }
                    if (preview) FinishDeadlineDialog(
                        archive = archive, hidden = setOf(10), busy = false,
                        onConfirm = { confirmed = true },
                        onDismiss = { preview = false },
                    )
                }
            }
        }
        compose.onNodeWithText("Estimated goal not met").assertIsDisplayed()
        compose.onNodeWithText("Trusted Steam lower bound · ≥ 30 min").assertIsDisplayed()
        compose.onNodeWithText("Trusted Steam upper bound · ≤ 45 min").assertExists()
        compose.onNodeWithText("Hidden game").assertIsDisplayed()
        compose.onNodeWithText("Private game").assertDoesNotExist()
        compose.onNode(isDialog()).captureRoboImage("collections/archive-preview/dark/narrow.png")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Finish and archive?").assertDoesNotExist()
        assertFalse(confirmed)
    }
}
