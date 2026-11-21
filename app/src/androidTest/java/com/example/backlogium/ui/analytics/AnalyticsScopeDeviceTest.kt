package com.example.backlogium.ui.analytics

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class AnalyticsScopeDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ScreenshotTestActivity>()
    private val today = LocalDate.of(2026, 10, 3)
    private val longName = "Primary Game With A Long Edition And Expansion Title"

    private fun fixture(kind: String): AnalyticsUiState {
        val window = AnalyticsWindow(today, if (kind == "year") AnalyticsWindowLength.ONE_YEAR else AnalyticsWindowLength.ONE_MONTH)
        val total = if (kind == "empty") 0 else if (kind == "tie") 200 else 180
        val games = if (total == 0) emptyList() else listOf(
            AnalyticsGame(1, longName, if (kind == "missing") "file:///missing-art.png" else "", 100),
            AnalyticsGame(2, "Side Game", "", total - 100),
        )
        val days = generateSequence(window.resolve().start) { it.plusDays(1) }.takeWhile { it <= today }.map {
            AnalyticsDay(it, if (it == today) total else 0)
        }.toList()
        return AnalyticsUiState(loading = false, window = window, windowBounds = window.resolve(),
            headline = deriveAnalyticsHeadline(total, if (total > 0) 1 else 0, days.size, games.firstOrNull(),
                if (kind == "comparison") 90 else null), dailyMinutes = days, topGames = games)
    }

    @Test fun leadingLight() = capture("leading", false, 1f)
    @Test fun leadingDarkLarge() = capture("leading", true, 1.5f)
    @Test fun comparisonLight() = capture("comparison", false, 1f)
    @Test fun emptyLight() = capture("empty", false, 1f)
    @Test fun tiedLight() = capture("tie", false, 1f)
    @Test fun missingArtworkLight() = capture("missing", false, 1f)
    @Test fun currentYearLight() = capture("year", false, 1f)

    @Test fun rapidHeadlineChangesClearFeaturedPanelAndUpdatingKeepsOldDates() {
        val state = mutableStateOf(fixture("leading"))
        rule.setContent { BacklogiumTheme { AnalyticsContent(state.value, AnalyticsActions()) } }
        rule.onNodeWithTag("analytics-featured-game").assertExists()
        val dates = rule.onNodeWithTag("analytics-overview-dates").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
        rule.runOnIdle { state.value = state.value.copy(updating = true) }
        rule.onNodeWithTag("analytics-overview-dates").assertTextEquals(dates.single().text)
        for (kind in listOf("comparison", "empty", "leading", "comparison")) {
            rule.runOnIdle { state.value = fixture(kind) }
            if (kind == "leading") rule.onNodeWithTag("analytics-featured-game").assertExists()
            else rule.onNodeWithTag("analytics-featured-game").assertDoesNotExist()
        }
    }

    private fun capture(kind: String, dark: Boolean, font: Float) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, font)) {
                BacklogiumTheme(darkTheme = dark) { Surface(color = MaterialTheme.colorScheme.background) {
                    AnalyticsContent(fixture(kind), AnalyticsActions())
                } }
            }
        }
        rule.onNodeWithText("Play snapshot").assertIsDisplayed()
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250) // Let the Surface's first frame reach the display capture.
        val stage = InstrumentationRegistry.getArguments().getString("captureStage") ?: "revised"
        if (stage != "baseline") {
            rule.onNodeWithText("All eligible visible games in this period").assertExists()
            rule.onNodeWithTag("analytics-overview-dates").assertExists()
            if (kind in listOf("comparison", "empty")) rule.onNodeWithTag("analytics-featured-game").assertDoesNotExist()
            else {
                rule.onNodeWithText("Most played in this period").assertExists()
                rule.onNodeWithTag("analytics-featured-game").assert(hasContentDescription(longName, substring = true))
            }
        }
        val name = "analytics-$stage-$kind-${if (dark) "dark-large" else "light"}.png"
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
