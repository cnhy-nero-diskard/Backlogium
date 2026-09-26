package com.example.backlogium.ui.history

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.data.repo.SessionCloudContribution
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.time.LocalDate
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
        composeRule.onNodeWithText("History loaded:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "No cloud contributions are recorded in this loaded History window.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Reader status").assertIsDisplayed()
        composeRule.onNodeWithText("Reader request failed", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recovered play · 0 contributions").assertDoesNotExist()
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
    fun observationFreshnessRefreshesAtTheStaleBoundaryWithoutAReaderRequest() {
        val observationAt = 1_000L
        val oneDay = 24L * 60L * 60L * 1000L
        val state = mutableStateOf(
            HistoryUiState(
                loading = false,
                configured = true,
                today = TODAY,
                cloudReaderConfigured = true,
                cloudReadSummary = CloudReadSummary(latestObservationAt = observationAt),
                statusNow = observationAt + oneDay,
            ),
        )
        val refreshCount = mutableIntStateOf(0)

        composeRule.setContent {
            BacklogiumTheme {
                CloudActivityContent(
                    state = state.value,
                    onStatusTimeRefresh = {
                        refreshCount.intValue++
                        state.value = state.value.copy(statusNow = state.value.statusNow + 1L)
                    },
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { refreshCount.intValue > 0 }
        composeRule.runOnIdle {
            assertEquals(1, refreshCount.intValue)
            assertEquals(true, observationOlderThanDay(state.value.cloudReadSummary, state.value.statusNow))
        }
    }

    @Test
    fun appResumeRefreshesObservationAgeWithoutReadingCloudHistory() {
        val observationAt = 1_000L
        val oneDay = 24L * 60L * 60L * 1000L
        val state = mutableStateOf(
            HistoryUiState(
                loading = false,
                configured = true,
                today = TODAY,
                cloudReaderConfigured = true,
                cloudReadSummary = CloudReadSummary(latestObservationAt = observationAt),
                statusNow = observationAt + oneDay - 60L * 60L * 1000L,
            ),
        )
        val refreshCount = mutableIntStateOf(0)
        val advanceClock = mutableStateOf(false)
        val lifecycleOwner = TestHistoryLifecycleOwner()

        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                BacklogiumTheme {
                    RefreshCloudStatusTimeOnResume {
                        refreshCount.intValue++
                        if (advanceClock.value) {
                            state.value = state.value.copy(statusNow = observationAt + oneDay + 1L)
                        }
                    }
                    CloudActivityContent(state = state.value)
                }
            }
        }
        composeRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        composeRule.waitForIdle()
        val refreshesBeforeResume = composeRule.runOnIdle { refreshCount.intValue }
        composeRule.runOnIdle { advanceClock.value = true }
        composeRule.runOnUiThread {
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        composeRule.waitUntil(timeoutMillis = 5_000) { refreshCount.intValue > refreshesBeforeResume }
        composeRule.runOnIdle {
            assertEquals(true, observationOlderThanDay(state.value.cloudReadSummary, state.value.statusNow))
        }
    }

    @Test
    fun cloudActivityCountsAndActionsRemainLegibleAtLargeFontScale() {
        val recovered = HistorySessionUi(
            id = 81L,
            startAt = 1_758_432_000_000L,
            minutes = 35,
            open = false,
            cloudContribution = SessionCloudContribution(recoveredSharedPlay = ContributionState.FULL),
        )
        val timed = HistorySessionUi(
            id = 82L,
            startAt = 1_758_432_000_000L,
            minutes = 20,
            open = false,
            cloudContribution = SessionCloudContribution(timingInformedSteamPlay = ContributionState.PARTIAL),
        )
        val state = HistoryUiState(
            loading = false,
            configured = true,
            today = TODAY,
            days = listOf(day("2026-09-20").copy(
                games = listOf(
                    game(appId = 81L, name = "Accessible target", session = recovered),
                    game(appId = 82L, name = "Timed target", session = timed),
                ),
            )),
            cloudReaderConfigured = true,
            cloudReadSummary = CloudReadSummary(latestObservationAt = 1L),
            statusNow = 24L * 60L * 60L * 1000L + 2L,
        )

        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                BacklogiumTheme {
                    CloudActivityContent(state = state)
                }
            }
        }

        composeRule.onNodeWithText("Back to History").assertIsDisplayed()
        composeRule.onNodeWithText("Recovered play · 1 contribution")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Cloud-timed Steam play · 1 contribution")
            .performScrollTo().assertIsDisplayed()
        val accessibleItem = composeRule.onNodeWithText("Accessible target")
        accessibleItem.performScrollTo().assertIsDisplayed()
        accessibleItem.assert(hasClickAction())
        composeRule.onNodeWithText("The latest observation is over a day old.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Reader success does not confirm current poller health. Missing observations do not mean no play.",
        ).performScrollTo().assertIsDisplayed()
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

    @Test
    fun revealExpandsAndShowsExactDistantSessionAndReportsChangedTarget() {
        val targetDate = LocalDate.parse(TODAY).minusDays(20).toString()
        val targetSession = HistorySessionUi(
            id = 991L,
            startAt = 1_758_432_000_000L,
            minutes = 91,
            open = false,
            cloudContribution = SessionCloudContribution(recoveredSharedPlay = ContributionState.FULL),
        )
        val days = (0..20).map { offset ->
            val date = LocalDate.parse(TODAY).minusDays(offset.toLong()).toString()
            when (offset) {
                0 -> day(date, game(appId = 200L, name = "Today's game"))
                1 -> day(date, game(appId = 201L, name = "First earlier game"))
                2 -> day(date, game(appId = 202L, name = "Second earlier game"))
                20 -> day(date, game(appId = 991L, name = "Distant target", session = targetSession))
                else -> day(date)
            }
        }
        val state = HistoryUiState(
            loading = false,
            configured = true,
            today = TODAY,
            days = days,
            cloudReaderConfigured = true,
        )
        val reveal = mutableStateOf<HistoryReveal?>(null)

        composeRule.setContent {
            BacklogiumTheme {
                HistoryContent(
                    state = state,
                    reveal = reveal.value,
                    onRevealHandled = { reveal.value = null },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(historyDayTestTag(TODAY)).assert(hasStateDescription("Expanded"))
        listOf(1, 2).forEach { offset ->
            val date = LocalDate.parse(TODAY).minusDays(offset.toLong()).toString()
            composeRule.onNodeWithTag(historyDayTestTag(date)).performScrollTo().performClick()
            composeRule.onNodeWithTag(historyDayTestTag(date)).assert(hasStateDescription("Expanded"))
        }
        composeRule.onNodeWithTag(historySessionTestTag(targetSession.id)).assertDoesNotExist()

        composeRule.runOnIdle {
            reveal.value = HistoryReveal(targetDate, 991L, targetSession.id)
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(historyDayTestTag(targetDate))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_HISTORY_REVEAL_UNAVAILABLE).assertDoesNotExist()
        composeRule.onNodeWithText("Distant target").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(historySessionTestTag(targetSession.id))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(historySessionTestTag(targetSession.id)).assertIsDisplayed()

        composeRule.runOnIdle {
            reveal.value = HistoryReveal(targetDate, 991L, targetSession.id + 1)
        }
        composeRule.onNodeWithTag(TAG_HISTORY_REVEAL_UNAVAILABLE).assertIsDisplayed()
        composeRule.onNodeWithText(
            "That cloud-assisted session is no longer in the loaded History window.",
        ).assertIsDisplayed()
    }

    @Test
    fun cloudActivityItemReturnsToItsVisibleHistorySession() {
        val targetSession = HistorySessionUi(
            id = 72L,
            startAt = 1_758_432_000_000L,
            minutes = 34,
            open = false,
            cloudContribution = SessionCloudContribution(recoveredSharedPlay = ContributionState.PARTIAL),
        )
        val state = HistoryUiState(
            loading = false,
            configured = true,
            today = TODAY,
            days = listOf(day("2026-09-20", game(
                appId = 72L,
                name = "Activity target",
                session = targetSession,
            ))),
            cloudReaderConfigured = true,
        )
        val showingHistory = mutableStateOf(true)
        val reveal = mutableStateOf<HistoryReveal?>(null)

        composeRule.setContent {
            BacklogiumTheme {
                if (showingHistory.value) {
                    HistoryContent(
                        state = state,
                        onOpenCloudActivity = { showingHistory.value = false },
                        reveal = reveal.value,
                        onRevealHandled = { reveal.value = null },
                    )
                } else {
                    CloudActivityContent(
                        state = state,
                        onOpenSession = { item ->
                            reveal.value = HistoryReveal(item.date, item.game.appId, item.session.id)
                            showingHistory.value = true
                        },
                        onBack = { showingHistory.value = true },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Cloud activity").performClick()
        composeRule.onNodeWithText("Activity target").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(historySessionTestTag(targetSession.id))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(historySessionTestTag(targetSession.id)).assertIsDisplayed()
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

    private fun game(
        appId: Long = 42L,
        name: String = "Test Game",
        session: HistorySessionUi = HistorySessionUi(
            id = 1L,
            startAt = 1_758_432_000_000L,
            minutes = 45,
            open = false,
        ),
    ) = HistoryGameGroup(
        appId = appId,
        name = name,
        iconUrl = "",
        minutesPlayed = session.minutes,
        sessions = listOf(session),
    )

    private companion object {
        const val TODAY = "2026-09-21"
    }

    private class TestHistoryLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
