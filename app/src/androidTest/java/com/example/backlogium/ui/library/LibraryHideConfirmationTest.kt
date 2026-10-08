package com.example.backlogium.ui.library

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.VisibilityChangeEffect
import com.example.backlogium.ui.components.VisibilityChangeDialog
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Library cards and dialog, with a fixture detail action and recomputed-effect inputs. */
@RunWith(AndroidJUnit4::class)
class LibraryHideConfirmationTest {
    @get:Rule val compose = createAndroidComposeRule<ScreenshotTestActivity>()
    private val density = mutableStateOf(GameListDensity.LIST)
    private val detail = mutableStateOf(false)
    private val dialog = mutableStateOf(false)
    private val effect = mutableStateOf(VisibilityChangeEffect(listOf(1), listOf("Portal"), true, 100, 100, 2, 2, emptyList()))
    private var confirms = 0
    private var cancels = 0
    private fun show() = compose.setContent {
        BacklogiumTheme {
            if (detail.value) {
                Button(onClick = { dialog.value = true }) { Text("Hide game") }
            } else {
                LibraryContent(
                    state = LibraryUiState(
                        loading = false, libraryEmpty = false, density = density.value,
                        backlog = listOf(BacklogGameUi(1, "Portal", "", playtimeForever = 0)),
                    ),
                    actions = LibraryContentActions(onOpenGameDetail = { detail.value = true }),
                )
            }
            if (dialog.value) {
                VisibilityChangeDialog(
                    effect.value,
                    onConfirm = { confirms++; dialog.value = false; detail.value = false },
                    onDismiss = { cancels++; dialog.value = false; detail.value = false },
                )
            }
        }
    }
    private fun open(mode: GameListDensity) {
        compose.runOnIdle { density.value = mode }
        compose.onNodeWithText("Portal").performClick()
        compose.onNodeWithText("Hide game").performClick()
        compose.onNodeWithText("Hide Portal?").assertIsDisplayed()
        compose.onNodeWithText("Leaves the Library and other views, and no longer counts toward XP. Restore it from Hidden Games.").assertIsDisplayed()
    }

    @Test fun noChangeHideShowsRestoreGuidanceAndCancelWithoutRedundantProgressLines() {
        show()
        for (mode in GameListDensity.entries) {
            open(mode)
            compose.onNodeWithText("XP:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Level:", substring = true).assertDoesNotExist()
            compose.onNodeWithText("No XP or level change", substring = true).assertDoesNotExist()
            capture("${mode.name}-hide-no-change")
            compose.onNodeWithText("Cancel").performClick()
        }
        compose.runOnIdle { assertEquals(3, cancels); assertEquals(0, confirms) }
    }

    @Test fun consequentialHideDisclosesXpLevelDropAndFocusLossBeforeConfirm() {
        effect.value = effect.value.copy(totalXpBefore = 1_000, totalXpAfter = 500, levelBefore = 3, levelAfter = 2, clearedGoalNames = listOf("Portal"))
        show()
        for (mode in GameListDensity.entries) {
            open(mode)
            compose.onNodeWithText("XP: 1000 → 500").assertIsDisplayed()
            compose.onNodeWithText("Level drops: 3 → 2").assertIsDisplayed()
            compose.onNodeWithText("Portal stops being a Focus game. Unhiding does not restore that.").assertIsDisplayed()
            capture("${mode.name}-hide-effects")
            compose.onNodeWithText("Hide").performClick()
        }
        compose.runOnIdle { assertEquals(3, confirms); assertEquals(0, cancels) }
    }

    @Test fun xpChangeWithoutLevelChangeDoesNotAddAnUnchangedLevelLine() {
        effect.value = effect.value.copy(totalXpAfter = 90)
        show()
        open(GameListDensity.LIST)
        compose.onNodeWithText("XP: 100 → 90").assertIsDisplayed()
        compose.onNodeWithText("Level:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Level drops:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Cancel").performClick()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = File(instrumentation.targetContext.getExternalFilesDir(null), "library-discovery").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(output, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
