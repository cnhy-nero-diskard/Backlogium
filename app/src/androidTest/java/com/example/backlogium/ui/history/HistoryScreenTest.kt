package com.example.backlogium.ui.history

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.ui.theme.BacklogiumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun populatedHistorySeparatesTodayAndEarlierAndExplainsMeasurement() {
        setContent(
            HistoryUiState(
                loading = false,
                configured = true,
                today = TODAY,
                days = listOf(day(TODAY, game = game()), day("2026-09-20")),
            ),
        )

        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier history").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(TAG_HISTORY_MEASUREMENT_HELP).performClick()
        composeRule.onNodeWithText("How tracked sessions are measured").assertIsDisplayed()
        composeRule.onNodeWithText("Session starts are approximate", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Got it").performClick()
        composeRule.onNodeWithText("How tracked sessions are measured").assertDoesNotExist()
    }

    @Test
    fun loadingWithNoDaysShowsProgressInsteadOfEmptyState() {
        setContent(
            HistoryUiState(
                loading = true,
                configured = true,
                days = emptyList(),
            ),
        )

        composeRule.onNodeWithTag(TAG_HISTORY_LOADING).assertIsDisplayed()
        composeRule.onNodeWithText("No history yet").assertDoesNotExist()
    }

    @Test
    fun emptyConfiguredHistoryKeepsCloudActivityReachableWithLoadedWindowAndStatus() {
        val showCloudActivity = mutableStateOf(false)
        val state = HistoryUiState(
            loading = false,
            configured = true,
            today = TODAY,
            windowStartDate = "2026-08-23",
            cloudReaderConfigured = true,
            cloudReadSummary = CloudReadSummary(
                lastAttemptAt = 1_758_000_000_000L,
                lastOutcome = CloudReadSummaryOutcome.FAILED,
            ),
            statusNow = 1_758_000_000_000L,
        )
        composeRule.setContent {
            BacklogiumTheme {
                if (showCloudActivity.value) {
                    CloudActivityContent(state = state, onBack = { showCloudActivity.value = false })
                } else {
                    HistoryContent(state = state, onOpenCloudActivity = { showCloudActivity.value = true })
                }
            }
        }

        composeRule.onNodeWithText("Cloud activity").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Back to History").assertIsDisplayed()
        composeRule.onNodeWithText("Loaded History window:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "No cloud-assisted sessions are recorded in this loaded History window.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Reader and observation status").assertIsDisplayed()
        composeRule.onNodeWithText("Reader request failed", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recovered play · 0 facts").assertDoesNotExist()
        composeRule.onNodeWithText("Back to History").performClick()
        composeRule.onNodeWithText("No history yet").assertIsDisplayed()
    }

    @Test
    fun cloudActivityLoadingKeepsBackActionAvailable() {
        var backClicks = 0
        composeRule.setContent {
            BacklogiumTheme {
                CloudActivityContent(
                    state = HistoryUiState(loading = true, cloudReaderConfigured = true),
                    onBack = { backClicks++ },
                )
            }
        }

        composeRule.onNodeWithText("Back to History").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Loading cloud activity").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(1, backClicks) }
    }

    @Test
    fun unconfiguredHistoryDoesNotShowCloudActivityEntry() {
        setContent(
            HistoryUiState(
                loading = false,
                configured = true,
                today = TODAY,
                days = emptyList(),
                cloudReaderConfigured = false,
            ),
        )

        composeRule.onNodeWithText("Cloud activity").assertDoesNotExist()
    }

    @Test
    fun todayExpandsAndGameSemanticsExposeTimeAndExpandedSession() {
        setContent(
            HistoryUiState(
                loading = false,
                configured = true,
                today = TODAY,
                days = listOf(day(TODAY, game = game())),
            ),
        )
        composeRule.waitForIdle()

        val gameNode = composeRule.onNode(
            hasContentDescription("Test Game", substring = true),
        )
        gameNode.performScrollTo().assertIsDisplayed()
        gameNode.assert(hasStateDescription("Collapsed"))

        gameNode.performClick()
        composeRule.waitForIdle()
        gameNode.assert(hasStateDescription("Expanded"))
        val playtimeNodes = composeRule.onAllNodesWithText("45 mins played", substring = true)
        playtimeNodes.assertCountEquals(2)
        playtimeNodes[1].performScrollTo().assertIsDisplayed()
    }

    private fun setContent(state: HistoryUiState) {
        composeRule.setContent {
            BacklogiumTheme {
                HistoryContent(state = state)
            }
        }
    }

    private fun day(date: String, game: HistoryGameGroup? = null) = HistoryDayGroup(
        date = date,
        minutesPlayed = game?.minutesPlayed ?: 0,
        goalMinutesPlayed = 0,
        questMet = game != null,
        games = game?.let(::listOf).orEmpty(),
        achievements = HistoryAchievements(iconUrls = emptyList(), overflowCount = 0),
    )

    private fun game() = HistoryGameGroup(
        appId = 42L,
        name = "Test Game",
        iconUrl = "",
        minutesPlayed = 45,
        sessions = listOf(
            HistorySessionUi(
                id = 1L,
                startAt = 1_758_432_000_000L,
                minutes = 45,
                open = false,
            ),
        ),
    )

    private companion object {
        const val TODAY = "2026-09-21"
    }
}
