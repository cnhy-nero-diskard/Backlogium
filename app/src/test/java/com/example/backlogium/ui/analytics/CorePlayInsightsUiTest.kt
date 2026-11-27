package com.example.backlogium.ui.analytics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-rUS-w320dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class CorePlayInsightsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ScreenshotTestActivity>()
    private lateinit var previousLocale: java.util.Locale
    private lateinit var previousZone: java.util.TimeZone
    @Before fun configureLocale() {
        previousLocale = java.util.Locale.getDefault()
        previousZone = java.util.TimeZone.getDefault()
        java.util.Locale.setDefault(java.util.Locale.US)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }
    @After fun restoreLocale() {
        java.util.Locale.setDefault(previousLocale)
        java.util.TimeZone.setDefault(previousZone)
    }
    private val date = LocalDate.of(2026, 11, 27)
    private fun fixture(count: Int = 90): AnalyticsUiState {
        val window = AnalyticsWindow(date, if (count > 180) AnalyticsWindowLength.ONE_YEAR else if (count > 60) AnalyticsWindowLength.NINETY_DAYS else AnalyticsWindowLength.TWO_WEEKS)
        val days = (count - 1 downTo 0).map { AnalyticsDay(date.minusDays(it.toLong()), if (it == 0) 60 else 0) }
        return AnalyticsUiState(loading = false, window = window, windowBounds = window.resolve(),
            dailyMinutes = days, gamesByDate = mapOf(date to listOf(AnalyticsGame(1, "Fixture game", "", 60))),
            topGames = listOf(AnalyticsGame(1, "Fixture game", "", 60)), variety = AnalyticsVariety(1, listOf(1)),
            focusShare = AnalyticsFocusShare(60, 60), activeQuestConsistency = AnalyticsQuestOutcomeSummary(0, 0, 1),
            playMix = listOf(AnalyticsPlayShare(1, 60, 100.0)), sessionInsights = SessionInsights(2, 30, 40, 30.0, listOf(1, 1, 0, 0)))
    }

    private fun advancedFixture(): AnalyticsUiState {
        val state = fixture(14)
        val totals = listOf(com.example.backlogium.domain.DatedCreditedTotal(date, 61, mapOf(1L to 61L)))
        val session = com.example.backlogium.data.repo.PlaySession(1, 1,
            date.atTime(23, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), 61, false)
        val unlock = com.example.backlogium.data.repo.DatedAchievementEvidence(1, "fixture", "Fixture unlock", session.startAt, 1.0)
        return state.copy(genreMix = deriveGenreMix(totals, mapOf(1L to listOf(
            com.example.backlogium.data.repo.GameGenre("a", "Action"), com.example.backlogium.data.repo.GameGenre("p", "Puzzle")))),
            clockMatrix = deriveClockMatrix(listOf(session), ZoneId.of("UTC")),
            achievementActivity = AnalyticsAchievementActivity(listOf(unlock), rarity = mapOf(com.example.backlogium.gamification.RarityTier.LEGENDARY to 1), unsupportedUnlocks = 2, unsupportedCompletions = 1),
            records = deriveRecords(totals, listOf(session), mapOf(1L to com.example.backlogium.data.repo.RecordedActivityBounds(date.minusDays(60), date.minusDays(31))), listOf(unlock)),
            gameNames = mapOf(1L to "Fixture game"), insightsZone = ZoneId.of("UTC"),
            rarityBreakdown = RarityBreakdown(legendary = 4))
    }

    private fun alignAdvancedTitle(title: String) {
        val node = rule.onNodeWithText(title).performScrollTo()
        val offset = node.fetchSemanticsNode().boundsInRoot.top - 16f
        rule.onNodeWithTag("advanced-scroll").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, offset) }
        rule.waitForIdle()
    }

    @Test fun advancedValuesAndRecordsRemainReachableOfflineAtLargeText() {
        rule.setContent { BacklogiumTheme(darkTheme = true) { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("advanced-scroll")) { AdvancedPlayInsights(advancedFixture()) }
        } } }
        rule.onNodeWithText("Genre mix and drift").assertIsDisplayed()
        rule.onRoot().captureRoboImage("alternatives/analytics/advanced-genre-large/dark/narrow.png")
        rule.onNodeWithText("Action: 30.5 min · 50%").performScrollTo().assertIsDisplayed()
        alignAdvancedTitle("Weekday and time of day")
        rule.onRoot().captureRoboImage("alternatives/analytics/advanced-clock-large/dark/narrow.png")
        rule.onNodeWithContentDescription("Friday · Night: 61 min").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show all timing values").performScrollTo().performClick()
        rule.onNodeWithText("Monday · Morning: 0 min").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Hide timing values").performScrollTo().performClick()
        alignAdvancedTitle("Achievement activity in this period")
        rule.onRoot().captureRoboImage("alternatives/analytics/advanced-achievements-large/dark/narrow.png")
        rule.onNodeWithText("Confirmed dated unlocks: 1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Show records").performScrollTo().performClick()
        rule.onNodeWithText("Longest finalized session: Fixture game · 61 min · Nov 27, 2026").performScrollTo().assertIsDisplayed()
        alignAdvancedTitle("Records in this period")
        rule.onRoot().captureRoboImage("alternatives/analytics/advanced-records-large/dark/narrow.png")
        rule.onNodeWithText("Longest qualifying return:", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Rarest dated unlock: Fixture unlock", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun clockMatrixHasAnAccessibleValueForEveryDayAndBucket() {
        rule.setContent { BacklogiumTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("advanced-scroll")) { AdvancedPlayInsights(advancedFixture()) }
        } }
        alignAdvancedTitle("Monday")
        rule.onRoot().captureRoboImage("alternatives/analytics/advanced-clock-values/dark/narrow.png")
        java.time.DayOfWeek.entries.forEach { day -> TimeOfDayBucket.entries.forEach { bucket ->
            val minutes = if (day == java.time.DayOfWeek.FRIDAY && bucket == TimeOfDayBucket.NIGHT) 61 else 0
            val label = bucket.name.lowercase().replaceFirstChar { it.uppercase() }
            rule.onNodeWithContentDescription("${day.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)} · $label: $minutes min")
                .performScrollTo().assertIsDisplayed()
        } }
    }

    @Test fun wholeViewKeepsRetainedDatesAndLifetimeScopeThenClearsAdvancedAccountFacts() {
        val state = mutableStateOf(advancedFixture())
        rule.setContent { BacklogiumTheme { AnalyticsContent(state.value) } }
        rule.onNodeWithText("Achievement activity in this period").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Confirmed dated unlocks: 1").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(updating = true) }
        rule.onNodeWithText("Confirmed dated unlocks: 1").assertExists()
        val earlier = date.minusDays(14)
        rule.runOnIdle {
            state.value = state.value.copy(updating = false,
                window = AnalyticsWindow(earlier, AnalyticsWindowLength.TWO_WEEKS),
                windowBounds = AnalyticsWindow(earlier, AnalyticsWindowLength.TWO_WEEKS).resolve(),
                dailyMinutes = listOf(AnalyticsDay(earlier, 10)), achievementActivity = AnalyticsAchievementActivity())
        }
        rule.onNodeWithText("Confirmed dated unlocks: 0").performScrollTo().assertIsDisplayed()
        listOf("genre", "clock", "achievement", "records").forEach {
            rule.onNodeWithTag("analytics-advanced-$it-scope").assertTextContains("Nov 13, 2026", substring = true)
        }
        rule.onNodeWithText("All-time profile; it does not follow the selected window.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("4 unlocked").assertExists()
        rule.runOnIdle {
            state.value = AnalyticsUiState(loading = false, window = state.value.window, windowBounds = state.value.windowBounds)
        }
        rule.onNodeWithText("Show records").performScrollTo().performClick()
        rule.onNodeWithText("Rarest dated unlock unavailable:", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Fixture unlock", substring = true).assertDoesNotExist()
        rule.onNodeWithText("4 unlocked").assertDoesNotExist()
    }

    @Test fun exactCalendarDateAndMonthNavigationShareInspectorAtLargeText() {
        rule.setContent { BacklogiumTheme(darkTheme = true) { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) { AnalyticsContent(fixture()) } } }
        rule.onNodeWithText("Weekly playtime").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Thursday, November 26, 2026: 0 mins").performScrollTo().performClick()
        rule.onNodeWithTag(TAG_ANALYTICS_CHART).assert(hasContentDescription("Nov 26, 2026", substring = true))
        rule.onNodeWithText("Previous month").performScrollTo().performClick()
        rule.onNodeWithTag(TAG_ANALYTICS_CHART).assert(hasContentDescription("Oct 1, 2026", substring = true))
        rule.onNodeWithText("Aug 30, 2026").assertExists()
        rule.onNodeWithText("Activity calendar · select an exact date").performScrollTo()
        rule.onRoot().captureRoboImage("alternatives/analytics/calendar-large/dark/narrow.png")
    }

    @Test fun coreFactsRemainReachableAtLargeText() {
        rule.setContent { BacklogiumTheme(darkTheme = true) { CompositionLocalProvider(LocalDensity provides Density(1f, 1.5f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { CorePlayInsights(fixture()) }
        } } }
        rule.onNodeWithText("Core play insights").assertIsDisplayed()
        rule.onRoot().captureRoboImage("alternatives/analytics/core-large/dark/narrow.png")
        rule.onNodeWithText("120 min or more: 0 sessions").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Focus with today’s tags: 60 / 60 visible dated minutes").performScrollTo().assertIsDisplayed()
    }

    @Test fun openSessionKeepsStartAttributionWhileFinalizedStatisticsStayUnavailable() {
        val openSession = com.example.backlogium.data.repo.PlaySession(
            id = 1L,
            appId = 1L,
            startAt = date.atTime(19, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(),
            minutes = 45,
            open = true,
        )
        val ongoing = deriveAnalyticsTimingInsights(listOf(openSession), 45, ZoneId.of("UTC"))
        val allocated = deriveAnalyticsTimingInsights(emptyList(), 45, ZoneId.of("UTC"))
        val game = AnalyticsGame(1, "Fixture game", "", 45)
        val state = mutableStateOf(
            fixture().copy(
                headline = AnalyticsHeadline.LeadingGame(game),
                dailyMinutes = listOf(AnalyticsDay(date, 45)),
                topGames = listOf(game),
                sessionInsights = ongoing.sessionInsights,
                timeOfDayPattern = ongoing.timeOfDayPattern,
                finalizedSessionStatsUnavailable = ongoing.finalizedStatisticsUnavailable,
                timeOfDayUnavailable = ongoing.timeOfDayUnavailable,
            ),
        )
        rule.setContent { BacklogiumTheme { AnalyticsContent(state.value) } }

        rule.onNodeWithText("Finalized-session statistics are unavailable for this period.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Peak time: Evening").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Time of day is unavailable for this period", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Finalized sessions · median", substring = true).assertDoesNotExist()

        val allocatedGame = game.copy(estimatedMinutes = 45)
        rule.runOnIdle {
            state.value = state.value.copy(
                headline = AnalyticsHeadline.LeadingGame(allocatedGame),
                dailyMinutes = listOf(AnalyticsDay(date, 45, estimatedMinutes = 45)),
                topGames = listOf(allocatedGame),
                gamesByDate = mapOf(date to listOf(allocatedGame)),
                sessionInsights = allocated.sessionInsights,
                timeOfDayPattern = allocated.timeOfDayPattern,
                finalizedSessionStatsUnavailable = allocated.finalizedStatisticsUnavailable,
                timeOfDayUnavailable = allocated.timeOfDayUnavailable,
            )
        }

        rule.onNodeWithText("Finalized-session statistics are unavailable for this period.")
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Time of day is unavailable for this period", substring = true)
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Peak time: Evening").assertDoesNotExist()
    }

    @Test fun periodSwitchReplacesLongTrendAndExactDayWithoutRelabellingRetainedData() {
        val state = mutableStateOf(fixture())
        rule.setContent { BacklogiumTheme(darkTheme = true) { AnalyticsContent(state.value) } }
        rule.onNodeWithText("Weekly playtime").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(updating = true) }
        rule.onNodeWithText("Weekly playtime").assertExists()
        rule.runOnIdle { state.value = fixture(300).copy(updating = false) }
        rule.onNodeWithText("Monthly playtime").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { state.value = fixture(14).copy(dailyMinutes = listOf(AnalyticsDay(date.minusDays(1), 20))) }
        rule.onNodeWithTag(TAG_ANALYTICS_CHART).assert(hasContentDescription("Nov 26, 2026", substring = true))
    }
}
