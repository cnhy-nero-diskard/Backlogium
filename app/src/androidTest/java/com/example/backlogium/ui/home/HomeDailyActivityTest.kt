package com.example.backlogium.ui.home

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.domain.*
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HomeDailyActivityTest {
    @get:Rule val rule = createAndroidComposeRule<ScreenshotTestActivity>()
    private val date = LocalDate.of(2026, 10, 3)
    private fun state(account: String = "fixture-a", day: LocalDate = date) = HomeUiState(
        loading = false, hasRenderableContent = true,
        dailyActivityKey = DailyActivityKey(account, day),
        dailyActivity = DailyActivity(day, account, listOf(
            DailyActivityGame(1, "A game with a longer title for accessible wrapping", 25, true),
            DailyActivityGame(2, "Another game", 15, false),
        ), 60, true),
        todayMinutes = 60, questMet = true,
    )

    @Test fun expandedLightDeviceEvidence() = capture(false, 1f, "home-daily-light")
    @Test fun expandedDarkLargeFontDeviceEvidence() = capture(true, 1.5f, "home-daily-dark-large")

    @Test fun navigationReturnRestoresSameDayExpansion() {
        val homeVisible = mutableStateOf(true)
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            BacklogiumTheme {
                if (homeVisible.value) holder.SaveableStateProvider("home") {
                    HomeContent(state(), HomeContentActions(onOpenDailyGame = { homeVisible.value = false }))
                }
            }
        }
        rule.onNodeWithText("Show today’s game breakdown").performScrollTo().performClick()
        rule.onNodeWithText("Open A game with a longer title for accessible wrapping details")
            .performScrollTo().performClick()
        rule.runOnIdle { homeVisible.value = true }
        rule.onNodeWithText("Hide today’s game breakdown").assertExists()
        rule.onNodeWithText("Credited quest minutes: 60").assertExists()
    }

    private fun capture(dark: Boolean, fontScale: Float, name: String) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                BacklogiumTheme(darkTheme = dark) { Surface(color = MaterialTheme.colorScheme.background) {
                    HomeContent(state())
                } }
            }
        }
        rule.onNodeWithText("Show today’s game breakdown").performScrollTo().assertIsDisplayed()
        captureImage("$name-collapsed")
        rule.onNodeWithText("Show today’s game breakdown").performClick()
        rule.onNodeWithText("Visible recorded minutes: 40").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Credited quest minutes: 60").assertExists()
        rule.onNodeWithText("Open A game with a longer title for accessible wrapping details")
            .performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        captureImage("$name-expanded")
    }

    @Test fun collapseResetsOnDateAndAccountAndNavigationRemainsNamed() {
        val current = mutableStateOf(state())
        var opened: Long? = null
        rule.setContent { BacklogiumTheme {
            HomeContent(current.value, HomeContentActions(onOpenDailyGame = { opened = it }))
        } }
        rule.onNodeWithText("Show today’s game breakdown").performScrollTo().performClick()
        rule.onNodeWithText("Open A game with a longer title for accessible wrapping details")
            .performScrollTo().performClick()
        assertEquals(1L, opened)
        // A normal local refresh keeps expansion; a new date/account starts collapsed.
        rule.runOnIdle { current.value = state().copy(todayMinutes = 61) }
        rule.onNodeWithText("Hide today’s game breakdown").assertExists()
        rule.runOnIdle { current.value = state(day = date.plusDays(1)) }
        rule.onNodeWithText("Show today’s game breakdown").assertExists()
        rule.onNodeWithText("Show today’s game breakdown").performScrollTo().performClick()
        rule.runOnIdle { current.value = state(account = "fixture-b", day = date.plusDays(1)) }
        rule.onNodeWithText("Show today’s game breakdown").assertExists()
        rule.onNodeWithText("Credited quest minutes: 60").assertDoesNotExist()
    }

    private fun captureImage(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
