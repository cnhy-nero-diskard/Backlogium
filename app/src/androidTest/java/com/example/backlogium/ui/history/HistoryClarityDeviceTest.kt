package com.example.backlogium.ui.history

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import org.junit.Assert.assertEquals
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.repo.*
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HistoryClarityDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ScreenshotTestActivity>()
    private val today = LocalDate.of(2026, 10, 3)
    private lateinit var oldZone: TimeZone
    @Before fun zone() { oldZone = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(oldZone) }
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun fixture(sparse: Boolean): HistoryUiState {
        val games = listOf(LibraryGame(1, "Primary Game", "", playtimeForever = 0),
            LibraryGame(2, "Side Game", "", playtimeForever = 0))
        val sessions = if (sparse) listOf(
            PlaySession(1, 1, at(today, 3), 5, false), PlaySession(2, 1, at(today, 22), 10, false),
        ) else listOf(
            PlaySession(1, 1, at(today, 9), 40, false, cloudContribution = SessionCloudContribution(ContributionState.PARTIAL, ContributionState.FULL)),
            PlaySession(2, 2, at(today, 10), 30, false), PlaySession(3, 1, at(today, 11), 20, true),
            PlaySession(4, 1, at(today.minusDays(1), 23, 50), 80, true),
        )
        val progress = listOf(DayProgress(today.toString(), if (sparse) 5 else 110, 0, true),
            DayProgress(today.minusDays(8).toString(), 60, 0, true))
        return HistoryUiState(loading = false, today = today.toString(), windowStartDate = today.minusDays(29).toString(),
            days = groupHistory(sessions, games, progress, emptyList(), ZoneId.of("UTC")), cloudReaderConfigured = true)
    }

    @Test fun multiGameLight() = capture(false, 1f, false, "multi-light")
    @Test fun multiGameDarkLarge() = capture(true, 1.5f, false, "multi-dark-large")
    @Test fun sparseAndProgressOnlyLight() = capture(false, 1f, true, "sparse-light")

    @Test fun navigationReturnRefreshPagingMidnightAndAccountResetPreserveValidState() {
        val state = mutableStateOf(fixture(false).copy(accountId = "first", hasOlderHistory = true))
        val details = mutableStateOf(false)
        var opens = 0
        var older = 0
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            BacklogiumTheme {
                if (details.value) TextButton(onClick = { details.value = false }) { Text("Back") }
                else holder.SaveableStateProvider("history") {
                    HistoryContent(state.value, onOpenGame = { opens++; details.value = true }, onLoadOlder = { older++ })
                }
            }
        }
        rule.onNodeWithText("Primary Game").performClick()
        rule.onNodeWithText("Open Primary Game details").performScrollTo().performClick()
        rule.onNodeWithText("Back").performClick()
        rule.onNodeWithTag(historySessionTestTag(1)).assertExists()
        rule.runOnIdle { state.value = state.value.copy(updating = true) }
        rule.onNodeWithTag(historySessionTestTag(1)).assertExists()
        rule.onNode(hasScrollAction()).performScrollToKey("load-older")
        rule.onNodeWithText("Load older").performClick()
        rule.runOnIdle {
            assertEquals(1, opens); assertEquals(1, older)
            state.value = state.value.copy(updating = false, today = today.plusDays(1).toString(), hasOlderHistory = false)
        }
        rule.onNode(hasScrollAction()).performScrollToKey("games-${today}")
        rule.onNodeWithTag(historySessionTestTag(1)).assertExists()
        rule.runOnIdle { state.value = state.value.copy(accountId = "replacement") }
        rule.onNodeWithTag(historySessionTestTag(1)).assertDoesNotExist()
    }

    private fun capture(dark: Boolean, font: Float, sparse: Boolean, name: String) {
        val state = fixture(sparse)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                BacklogiumTheme(darkTheme = dark) { Surface(color = MaterialTheme.colorScheme.background) {
                    HistoryContent(state, reveal = HistoryReveal(today.toString(), 1, 1))
                } }
            }
        }
        rule.onNodeWithTag(historySessionTestTag(1)).assertExists()
        rule.waitForIdle()
        screenshot(name)
        if (!sparse) {
            rule.onNodeWithTag(historyDayTestTag(today.minusDays(1).toString())).performScrollTo().performClick()
            rule.onNode(hasScrollAction()).performScrollToKey("games-${today.minusDays(1)}")
            rule.onAllNodesWithText("Primary Game").onLast().performClick()
            rule.onNodeWithTag(historySessionTestTag(4)).performScrollTo().assertIsDisplayed()
            rule.waitForIdle()
            screenshot("$name-overnight")
        } else {
            rule.onNodeWithTag(historyDayTestTag(today.minusDays(8).toString())).performScrollTo()
            screenshot("$name-progress-only")
        }
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val stage = InstrumentationRegistry.getArguments().getString("captureStage") ?: "revised"
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "history-$stage-$name.png")
        file.outputStream().use { instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
