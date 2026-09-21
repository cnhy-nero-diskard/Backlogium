package com.example.backlogium.ui.analytics

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

        composeRule.onNodeWithText("Game X").performScrollTo().assertIsDisplayed()
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
            AnalyticsDay(TODAY.minusDays(2), 0),
            AnalyticsDay(TODAY.minusDays(1), 90),
            AnalyticsDay(TODAY, 45),
        ),
        questThreshold = 60,
        currentStreak = 2,
        longestStreak = 5,
        questMetDaysCount = 1,
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
