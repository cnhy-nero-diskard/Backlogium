package com.example.backlogium.ui.history

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.data.repo.SessionCloudContribution
import com.example.backlogium.domain.PlaytimeReadProjector
import com.example.backlogium.domain.SessionCredit
import com.example.backlogium.domain.TimedShareCredit
import com.example.backlogium.ui.screenshot.ScreenshotTestActivity
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.ui.util.UiFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35])
class HistoryScreenRegressionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ScreenshotTestActivity>()

    private lateinit var previousLocale: Locale
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun useStableLocaleAndTimeZone() {
        previousLocale = Locale.getDefault()
        previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreLocaleAndTimeZone() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun fullyFundedSessionsKeepClockRowsAndCloudActivityEntry() {
        val date = LocalDate.of(2026, 10, 15)
        val zone = ZoneId.of("UTC")
        val ordinaryStart = atUtc(10)
        val cloudStart = atUtc(11)
        val ordinary = playSession(
            id = 81L,
            appId = 10L,
            startAt = ordinaryStart,
            minutes = 20,
            contribution = SessionCloudContribution(
                recoveredSharedPlay = ContributionState.NONE,
                timingInformedSteamPlay = ContributionState.NONE,
            ),
        )
        val cloudTimed = playSession(
            id = 82L,
            appId = 20L,
            startAt = cloudStart,
            minutes = 30,
            contribution = SessionCloudContribution(
                recoveredSharedPlay = ContributionState.NONE,
                timingInformedSteamPlay = ContributionState.FULL,
            ),
        )
        val days = groupHistory(
            sessions = listOf(ordinary, cloudTimed),
            games = listOf(
                LibraryGame(appId = 10L, name = "Ordinary target", iconUrl = "", playtimeForever = 0),
                LibraryGame(appId = 20L, name = "Timed target", iconUrl = "", playtimeForever = 0),
            ),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            zone = zone,
            sessionActivities = listOf(
                fullyFundedActivity(ordinary, date),
                fullyFundedActivity(cloudTimed, date),
            ),
        )
        val day = days.single()
        assertEquals(50, day.minutesPlayed)
        assertEquals(
            mapOf("Ordinary target" to 20, "Timed target" to 30),
            day.games.associate { it.name to it.minutesPlayed },
        )

        val state = HistoryUiState(
            loading = false,
            configured = true,
            today = date.toString(),
            windowStartDate = date.toString(),
            days = days,
            cloudReaderConfigured = true,
        )
        val showCloudActivity = mutableStateOf(false)
        composeRule.mainClock.autoAdvance = true
        composeRule.setContent {
            BacklogiumTheme {
                if (showCloudActivity.value) {
                    CloudActivityContent(state = state)
                } else {
                    HistoryContent(
                        state = state,
                        onOpenCloudActivity = { showCloudActivity.value = true },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("50 mins played").assertIsDisplayed()
        composeRule.onNodeWithText("Ordinary target").performClick()
        composeRule.onNodeWithText("${UiFormat.approxTime(ordinaryStart)} - 20 mins played")
            .assertIsDisplayed()
        composeRule.onNodeWithText("20 mins from a timestamped session").assertIsDisplayed()
        composeRule.onNodeWithText("Timed target").performClick()
        composeRule.onNodeWithText("${UiFormat.approxTime(cloudStart)} - 30 mins played")
            .assertIsDisplayed()
        composeRule.onNodeWithText("30 mins from a timestamped session").assertIsDisplayed()
        composeRule.onAllNodesWithTag(TAG_HISTORY_CLOUD_MARK).assertCountEquals(1)

        composeRule.onNodeWithText("Cloud activity").performClick()
        composeRule.onNodeWithText("Cloud-timed Steam play · 1 contribution").assertIsDisplayed()
        composeRule.onNodeWithText("Timed target").assertIsDisplayed()
        composeRule.onNodeWithText(
            "${formatHistoryDate(date.toString())} · ${UiFormat.approxTime(cloudStart)} · 30 mins",
        ).assertIsDisplayed()
        composeRule.onAllNodesWithText("Ordinary target").assertCountEquals(0)
    }

    private fun playSession(
        id: Long,
        appId: Long,
        startAt: Long,
        minutes: Int,
        contribution: SessionCloudContribution,
    ) = PlaySession(
        id = id,
        appId = appId,
        startAt = startAt,
        minutes = minutes,
        open = false,
        endAt = startAt + minutes * 60_000L,
        cloudContribution = contribution,
    )

    private fun fullyFundedActivity(session: PlaySession, date: LocalDate) =
        PlaytimeReadProjector.sessionActivities(
            sessions = listOf(
                SessionCredit(
                    id = session.id,
                    appId = session.appId,
                    startAt = session.startAt,
                    minutes = session.minutes,
                    open = session.open,
                    endAt = session.endAt,
                ),
            ),
            timedShares = listOf(
                TimedShareCredit(
                    placementId = "funded-${session.id}",
                    contributionId = "funded-${session.id}",
                    appId = session.appId,
                    sessionId = session.id,
                    minutes = session.minutes.toLong(),
                    creditedDate = date,
                    frozenZone = "UTC",
                ),
            ),
            zone = ZoneId.of("UTC"),
            startDateInclusive = date,
            endDateExclusive = date.plusDays(1),
        ).single()

    private fun atUtc(hour: Int): Long =
        ZonedDateTime.of(2026, 10, 15, hour, 0, 0, 0, ZoneId.of("UTC"))
            .toInstant().toEpochMilli()
}
