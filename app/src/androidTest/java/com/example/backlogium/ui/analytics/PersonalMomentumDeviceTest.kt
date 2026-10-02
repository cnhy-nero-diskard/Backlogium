package com.example.backlogium.ui.analytics

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PersonalMomentumDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<ScreenshotTestActivity>()
    private val key = MomentumKey("fixture", LocalDate.of(2026, 10, 3), ZoneId.of("UTC"))
    private val weeks = completedWeeks(key.today)
    private val longName = "A Long Game Name With An Expanded Edition And Additional Adventure"
    private fun fixture(kind: String): MomentumUiState {
        val rows = listOf(MomentumCandidate(MomentumGame(1, longName, ""), 120, 80, 2, MomentumKind.GROWTH),
            MomentumCandidate(MomentumGame(2, "Newly recorded game", ""), 90, 0, 2, MomentumKind.NEWLY_RECORDED))
        val availability = when (kind) {
            "learning" -> MomentumAvailability.LEARNING
            "empty" -> MomentumAvailability.EMPTY_LIBRARY
            "no-increase" -> MomentumAvailability.NO_INCREASE
            else -> MomentumAvailability.CANDIDATES
        }
        val result = PersonalMomentum(key, weeks, availability,
            if (availability == MomentumAvailability.CANDIDATES) rows else emptyList())
        return MomentumUiState(MomentumRead(key, result, kind == "updating"))
    }

    @Test fun candidatesLight() = capture("candidates", false)
    @Test fun candidatesDarkLarge() = capture("candidates", true)
    @Test fun learningLight() = capture("learning", false)
    @Test fun learningDarkLarge() = capture("learning", true)
    @Test fun emptyLight() = capture("empty", false)
    @Test fun emptyDarkLarge() = capture("empty", true)
    @Test fun noIncreaseLight() = capture("no-increase", false)
    @Test fun noIncreaseDarkLarge() = capture("no-increase", true)
    @Test fun updatingLight() = capture("updating", false)
    @Test fun updatingDarkLarge() = capture("updating", true)

    private fun capture(kind: String, dark: Boolean) {
        val opened = mutableListOf<Long>()
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (dark) 1.5f else 1f)) {
                BacklogiumTheme(darkTheme = dark) { Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                        PersonalMomentumCard(fixture(kind), opened::add)
                    }
                } }
            }
        }
        rule.onNodeWithText("Personal play momentum").assertIsDisplayed()
        rule.onNodeWithTag("momentum-current-dates").assertTextContains("Sep 26", substring = true)
        rule.onNodeWithTag("momentum-baseline-dates").assertTextContains("Sep 19", substring = true)
        if (kind == "updating") rule.onNodeWithText("Updating personal comparison…").assertIsDisplayed()
        screenshot("$kind-${if (dark) "dark-large" else "light"}")
        if (kind in listOf("candidates", "updating")) {
            rule.onNodeWithTag("momentum-open-1").performScrollTo().assertHasClickAction()
                .assert(hasText("Open $longName details"))
            val height = rule.onNodeWithTag("momentum-open-1").fetchSemanticsNode().boundsInRoot.height
            assertTrue(height >= 48 * rule.activity.resources.displayMetrics.density)
            rule.onNodeWithTag("momentum-open-1").performClick()
            rule.runOnIdle { assertEquals(listOf(1L), opened) }
            rule.onNodeWithTag("momentum-open-2").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("No positive finalized activity recorded in the previous week. This does not mean first-ever play.")
                .assertExists()
            rule.onNodeWithText("How personal momentum is calculated").performScrollTo().assertIsDisplayed()
            screenshot("$kind-${if (dark) "dark-large" else "light"}-newly-recorded")
        }
        if (kind == "updating") rule.onNodeWithText("Updating personal comparison…").assertExists()
    }

    @Test fun explanationAndUnavailableFeedbackAreAccessible() {
        rule.setContent { BacklogiumTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            PersonalMomentumCard(fixture("learning").copy(detailUnavailable = true)) {}
        } } }
        rule.onNodeWithText("This game is no longer available in the visible library.").assertExists()
        rule.onNodeWithText("How personal momentum is calculated").performScrollTo().performClick()
        rule.onNodeWithText("at least 25% growth", substring = true).assertExists()
        rule.onNodeWithText("That history gate does not establish continuous coverage.", substring = true).assertExists()
        rule.onNodeWithText("OK").performClick()
    }

    @Test fun selectedMainPeriodAndDetailReturnLeaveFixedWeeksIntact() {
        var selected by mutableStateOf(AnalyticsWindow(key.today, AnalyticsWindowLength.ONE_MONTH))
        var detail by mutableStateOf<Long?>(null)
        rule.setContent { BacklogiumTheme {
            val holder = rememberSaveableStateHolder()
            if (detail != null) TextButton(onClick = { detail = null }) { Text("Return to Analytics") }
            else holder.SaveableStateProvider("analytics") {
                AnalyticsContent(AnalyticsUiState(loading = false, window = selected, windowBounds = selected.resolve()),
                    actions = AnalyticsActions(onLengthSelected = { selected = selected.copy(length = it) }),
                    momentum = fixture("candidates"), onOpenMomentumGame = { detail = it })
            }
        } }
        val dates = rule.onNodeWithTag("momentum-current-dates").fetchSemanticsNode().config[SemanticsProperties.Text]
        rule.onNodeWithTag(TAG_ANALYTICS_WINDOW_OPTIONS).performScrollTo().performClick()
        rule.onNodeWithText("1 year", useUnmergedTree = true).performScrollTo().performClick()
        rule.runOnIdle { assertEquals(AnalyticsWindowLength.ONE_YEAR, selected.length) }
        rule.onNodeWithTag("momentum-current-dates").assertTextEquals(dates.single().text)
        rule.onNodeWithTag("momentum-open-1").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(1L, detail) }
        rule.onNodeWithText("Return to Analytics").performClick()
        rule.runOnIdle { assertEquals(AnalyticsWindowLength.ONE_YEAR, selected.length) }
        rule.onNodeWithTag("momentum-current-dates").assertTextEquals(dates.single().text)
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        android.os.SystemClock.sleep(250)
        File(instrumentation.targetContext.getExternalFilesDir(null), "momentum-$name.png").outputStream().use {
            instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
