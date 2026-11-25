package com.example.backlogium.ui.analytics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AnalyticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun headlineWindowContextAndDefaultChartModeAreVisible() {
        setContent(
            state = analyticsState(),
            actions = AnalyticsActions(),
        )

        composeRule.onNodeWithText("Play snapshot").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Avg per active day").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "2 hrs 15 mins tracked, up from 1 hr 30 mins in the previous period",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Selected period").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Show chart options").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Active days only (default)").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ANALYTICS_CHART_OPTIONS).performScrollTo().performClick()
        composeRule.onNodeWithText("Active days only").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("All days").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun chartSummaryAndControlsExposeSelectedDayDetailsWithoutAPreciseTap() {
        setContent(analyticsState())

        composeRule.onNode(
            hasContentDescription("Daily playtime chart", substring = true),
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ANALYTICS_PREVIOUS_DAY)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()

        // The inspected-day list precedes the period-wide most-played list with the same title.
        composeRule.onAllNodesWithText("Game X")[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Games played").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_ANALYTICS_NEXT_DAY)
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No games tracked on this day")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun questStatusAndDenominatorUseStoredOutcomes() {
        setContent(
            state = analyticsState().copy(
                dailyMinutes = listOf(
                    AnalyticsDay(TODAY.minusDays(2), 30, questMet = null),
                    // The total reaches the visible threshold, but the stored focus-only outcome did not.
                    AnalyticsDay(TODAY.minusDays(1), 90, questMet = false),
                    // The total is below the threshold, but the stored outcome says the quest was met.
                    AnalyticsDay(TODAY, 45, questMet = true),
                ),
                questMetDaysCount = 1,
                questOutcomesAvailableDayCount = 2,
                questOutcomesUnavailableDayCount = 1,
            ),
        )

        composeRule.onNodeWithTag(TAG_ANALYTICS_PREVIOUS_DAY).performScrollTo().performClick()
        composeRule.onNodeWithText("Quest not met").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ANALYTICS_PREVIOUS_DAY).performScrollTo().performClick()
        composeRule.onNodeWithText("Quest outcome unavailable").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1/2").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 date has no stored quest outcome.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun inspectedDateSurvivesQuestOutcomeRefreshAndFallsBackWhenRemoved() {
        val state = mutableStateOf(
            analyticsState().copy(
                dailyMinutes = listOf(
                    AnalyticsDay(TODAY.minusDays(2), 20, questMet = false),
                    AnalyticsDay(TODAY.minusDays(1), 30, questMet = null),
                    AnalyticsDay(TODAY, 40, questMet = false),
                ),
            ),
        )
        composeRule.setContent {
            BacklogiumTheme {
                AnalyticsContent(state = state.value, actions = AnalyticsActions())
            }
        }
        val chart = composeRule.onNodeWithTag(TAG_ANALYTICS_CHART)

        chart.assert(hasContentDescription("Quest not met", substring = true))
        composeRule.onNodeWithTag(TAG_ANALYTICS_PREVIOUS_DAY).performScrollTo().performClick()
        chart.assert(hasContentDescription("Quest outcome unavailable", substring = true))

        composeRule.runOnIdle {
            state.value = state.value.copy(
                dailyMinutes = state.value.dailyMinutes.map { day ->
                    if (day.date == TODAY.minusDays(1)) day.copy(questMet = true) else day
                },
            )
        }
        chart.assert(hasContentDescription("Daily goal met", substring = true))

        composeRule.runOnIdle {
            state.value = state.value.copy(
                dailyMinutes = state.value.dailyMinutes.filterNot { it.date == TODAY.minusDays(1) },
            )
        }
        chart.assert(hasContentDescription("Quest not met", substring = true))
    }

    @Test
    fun lifetimeStreaksAreGroupedApartFromWindowQuestResults() {
        setContent(
            state = analyticsState().copy(
                updating = true,
                isCurrentWindow = false,
                canStepLater = true,
            ),
        )

        composeRule.onNodeWithText("Lifetime streaks").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Current")[1].performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Longest")[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Quest outcomes in this window").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Quest met").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun activeDayChartDisclosesDateGapsAndSessionStartAttribution() {
        setContent(
            state = analyticsState().copy(
                dailyMinutes = listOf(
                    AnalyticsDay(TODAY.minusDays(3), 25, questMet = false),
                    AnalyticsDay(TODAY.minusDays(2), 0, questMet = false),
                    AnalyticsDay(TODAY.minusDays(1), 0, questMet = false),
                    AnalyticsDay(TODAY, 45, questMet = true),
                ),
                questMetDaysCount = 1,
                questOutcomesAvailableDayCount = 4,
                questOutcomesUnavailableDayCount = 0,
                timeOfDayPattern = TimeOfDayPattern(nightMinutes = 150),
            ),
        )

        composeRule.onNodeWithText("2 zero-minute dates omitted", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ANALYTICS_CHART)
            .assert(hasContentDescription("2 no-play dates omitted", substring = true))
        composeRule.onNodeWithText("Session-start attribution").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Recorded session minutes are grouped by local start hour",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun largeTextWindowControlsAndOfflineChartNavigationFitNarrowViewport() {
        val state = analyticsState(
            isCurrentWindow = false,
            canStepEarlier = true,
            canStepLater = true,
        )
        var selectedLength: AnalyticsWindowLength? = null
        var earlier = 0
        var later = 0

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                BacklogiumTheme {
                    Box(Modifier.width(320.dp).height(720.dp)) {
                        AnalyticsContent(
                            state = state,
                            actions = AnalyticsActions(
                                onLengthSelected = { selectedLength = it },
                                onStepEarlier = { earlier++ },
                                onStepLater = { later++ },
                            ),
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(TAG_ANALYTICS_WINDOW_OPTIONS).performScrollTo().performClick()
        composeRule.onNodeWithText("2 weeks", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1 year", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(AnalyticsWindowLength.ONE_YEAR, selectedLength)

        composeRule.onNodeWithTag(TAG_ANALYTICS_EARLIER).performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_ANALYTICS_LATER).performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_ANALYTICS_PREVIOUS_DAY).performScrollTo().performClick()
        composeRule.onNode(hasContentDescription("Daily playtime chart", substring = true))
            .performScrollTo().assertIsDisplayed()
        assertEquals(1, earlier)
        assertEquals(1, later)
    }

    @Test
    fun initialLoadingStateHidesSentinelPeriodMetadata() {
        // The bare default carries the 1970 INITIAL_WINDOW sentinel with loading=true and no
        // snapshot yet: no period identity, length, or option row may surface from it.
        setContent(state = AnalyticsUiState())

        composeRule.onNodeWithText("Analytics").assertIsDisplayed()
        composeRule.onNodeWithText("Updating the selected window...").assertIsDisplayed()
        composeRule.onNodeWithText("Selected period").assertDoesNotExist()
        composeRule.onNodeWithText("30 days", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Show window options").assertDoesNotExist()
        composeRule.onNodeWithText("Show chart options").assertDoesNotExist()
        composeRule.onNodeWithText("1970", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("1969", substring = true).assertDoesNotExist()
    }

    @Test
    fun periodNavigationUsesAccessibleEarlierLaterAndCurrentActions() {
        var earlier = 0
        var later = 0
        var current = 0
        setContent(
            state = analyticsState(
                window = AnalyticsWindow(TODAY.minusDays(30), AnalyticsWindowLength.THIRTY_DAYS),
                isCurrentWindow = false,
                canStepEarlier = true,
                canStepLater = true,
            ),
            actions = AnalyticsActions(
                onStepEarlier = { earlier++ },
                onStepLater = { later++ },
                onReturnToCurrent = { current++ },
            ),
        )

        composeRule.onNodeWithTag(TAG_ANALYTICS_EARLIER).performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_ANALYTICS_LATER).performScrollTo().performClick()
        composeRule.onNodeWithTag(TAG_ANALYTICS_CURRENT).performScrollTo().performClick()

        assertEquals(1, earlier)
        assertEquals(1, later)
        assertEquals(1, current)
    }

    private fun setContent(
        state: AnalyticsUiState,
        actions: AnalyticsActions = AnalyticsActions(),
    ) {
        composeRule.setContent {
            BacklogiumTheme {
                AnalyticsContent(state = state, actions = actions)
            }
        }
    }

    private fun analyticsState(
        window: AnalyticsWindow = AnalyticsWindow(TODAY, AnalyticsWindowLength.THIRTY_DAYS),
        isCurrentWindow: Boolean = true,
        canStepEarlier: Boolean = false,
        canStepLater: Boolean = false,
    ) = AnalyticsUiState(
        loading = false,
        configured = true,
        window = window,
        windowBounds = window.resolve(),
        canStepEarlier = canStepEarlier,
        canStepLater = canStepLater,
        isCurrentWindow = isCurrentWindow,
        headline = AnalyticsHeadline.Compared(currentMinutes = 135, previousMinutes = 90),
        dailyMinutes = listOf(
            AnalyticsDay(TODAY.minusDays(2), 0, questMet = false),
            AnalyticsDay(TODAY.minusDays(1), 90, questMet = false),
            AnalyticsDay(TODAY, 45, questMet = true),
        ),
        questThreshold = 60,
        currentStreak = 2,
        longestStreak = 5,
        questMetDaysCount = 1,
        questOutcomesAvailableDayCount = 3,
        questOutcomesUnavailableDayCount = 0,
        topGames = listOf(AnalyticsGame(1L, "Game X", "", 135)),
        sessionInsights = SessionInsights(sessionCount = 3, averageMinutes = 45, longestMinutes = 90),
        timeOfDayPattern = TimeOfDayPattern(eveningMinutes = 135),
        gamesByDate = mapOf(
            TODAY.minusDays(1) to listOf(AnalyticsGame(1L, "Game X", "", 90)),
        ),
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 21)
    }
}
