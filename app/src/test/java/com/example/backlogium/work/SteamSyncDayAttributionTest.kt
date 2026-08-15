package com.example.backlogium.work

import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.SessionDiffer.GameDiffState
import com.example.backlogium.domain.SessionDiffer.OpenSession
import com.example.backlogium.domain.SessionDiffer.PollGame
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SteamSyncDayAttributionTest {

    private val differ = SessionDiffer()
    private val zone = ZoneId.of("UTC")

    @Test
    fun newSessionCrossingMidnight_creditsItsStartDate() {
        val result = differ.diff(
            polls = listOf(PollGame(appId = 1L, playtimeForever = 130)),
            priorStates = mapOf(1L to GameDiffState(lastPlaytime = 100)),
            now = atUtc(2026, 7, 26, 0, 10),
            previousPollAt = atUtc(2026, 7, 25, 23, 50),
        )

        assertEquals(
            mapOf("2026-07-25" to DailyProgressCredit(minutesPlayed = 30, goalMinutesPlayed = 30)),
            attributeDailyProgress(result.actions, goalAppIds = setOf(1L), zone),
        )
    }

    @Test
    fun openSessionExtendedAfterMidnight_creditsItsOriginalStartDate() {
        val result = differ.diff(
            polls = listOf(PollGame(appId = 1L, playtimeForever = 150)),
            priorStates = mapOf(
                1L to GameDiffState(
                    lastPlaytime = 130,
                    openSession = OpenSession(
                        startAt = atUtc(2026, 7, 25, 23, 50),
                        minutes = 30,
                        lastIncreaseAt = atUtc(2026, 7, 26, 0, 0),
                    ),
                ),
            ),
            now = atUtc(2026, 7, 26, 0, 10),
            previousPollAt = atUtc(2026, 7, 26, 0, 0),
        )

        assertEquals(
            mapOf("2026-07-25" to DailyProgressCredit(minutesPlayed = 20, goalMinutesPlayed = 20)),
            attributeDailyProgress(result.actions, goalAppIds = setOf(1L), zone),
        )
    }

    @Test
    fun onePollWithOldAndNewSessions_creditsBothStartDates() {
        val result = differ.diff(
            polls = listOf(
                PollGame(appId = 1L, playtimeForever = 140),
                PollGame(appId = 2L, playtimeForever = 220),
            ),
            priorStates = mapOf(
                1L to GameDiffState(
                    lastPlaytime = 130,
                    openSession = OpenSession(
                        startAt = atUtc(2026, 7, 25, 23, 50),
                        minutes = 30,
                        lastIncreaseAt = atUtc(2026, 7, 26, 0, 0),
                    ),
                ),
                2L to GameDiffState(lastPlaytime = 200),
            ),
            now = atUtc(2026, 7, 26, 0, 10),
            previousPollAt = atUtc(2026, 7, 26, 0, 5),
        )

        assertEquals(
            mapOf(
                "2026-07-25" to DailyProgressCredit(minutesPlayed = 10, goalMinutesPlayed = 10),
                "2026-07-26" to DailyProgressCredit(minutesPlayed = 20, goalMinutesPlayed = 0),
            ),
            attributeDailyProgress(result.actions, goalAppIds = setOf(1L), zone),
        )
    }

    private fun atUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hour, minute)).toEpochMilli()
}
