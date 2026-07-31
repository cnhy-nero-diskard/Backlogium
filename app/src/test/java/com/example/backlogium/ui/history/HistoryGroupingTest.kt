package com.example.backlogium.ui.history

import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PlaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The day → game → session grouping (regroup-history tasks 2.8, 3.5): midnight attribution, day
 * totals that must equal their own contents rather than the stored [DayProgress] total, days with
 * progress but no sessions, unknown-game fallback, and the achievement thumbnail cap.
 *
 * A fixed UTC zone keeps every case deterministic regardless of the host machine's timezone.
 */
class HistoryGroupingTest {

    private val zone = ZoneId.of("UTC")

    @Test
    fun midnightCrossingSession_landsOnItsStartDay() {
        // Started 23:50 on the 25th, still open past midnight — must sit entirely on the 25th.
        val startAt = atUtc(2026, 7, 25, 23, 50)
        val session = session(id = 1, appId = 10, startAt = startAt, minutes = 37)

        val days = groupHistory(
            sessions = listOf(session),
            games = listOf(game(10, "Game X")),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            zone = zone,
        )

        assertEquals(listOf("2026-07-25"), days.map { it.date })
        assertEquals(1, days.single().games.single().sessions.size)
    }

    @Test
    fun openSession_countsTowardItsDayAndGameTotals() {
        val open = session(
            id = 1,
            appId = 10,
            startAt = atUtc(2026, 7, 25, 15, 0),
            minutes = 42,
            open = true,
        )

        val result = groupHistory(
            sessions = listOf(open),
            games = listOf(game(10, "Game X")),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            zone = zone,
        ).single()

        assertEquals(42, result.minutesPlayed)
        val gameGroup = result.games.single()
        assertEquals(42, gameGroup.minutesPlayed)
        assertTrue(gameGroup.sessions.single().open)
    }

    @Test
    fun dayTotal_equalsSumOfItsSessions_notTheStoredProgressTotal() {
        val day = "2026-07-25"
        val sessions = listOf(
            session(1, appId = 10, startAt = atUtc(2026, 7, 25, 15, 0), minutes = 30),
            session(2, appId = 20, startAt = atUtc(2026, 7, 25, 20, 0), minutes = 45),
        )
        // The stored totals (100 / 999) deliberately disagree with the sessions (75 / 45) — a
        // sync that lands just after local midnight can cause this; the header must reflect the
        // sessions shown, not the poll-time-bucketed DayProgress counters.
        val progress = DayProgress(date = day, minutesPlayed = 100, goalMinutesPlayed = 999, questMet = true)

        val result = groupHistory(
            sessions = sessions,
            games = listOf(game(10, "Game X"), game(20, "Game Y", isGoal = true)),
            dailyProgress = listOf(progress),
            achievementUnlocks = emptyList(),
            zone = zone,
        ).single()

        assertEquals(75, result.minutesPlayed)
        assertEquals(45, result.goalMinutesPlayed)
        assertTrue(result.questMet)
    }

    @Test
    fun goalMinutes_neverExceedsTotalMinutes_evenWhenStoredProgressDisagrees() {
        // Regression for the History bug where "Focus games" minutes exceeded the day's total:
        // DailyProgress.goalMinutesPlayed was bucketed by sync poll-time, not session start-day.
        val day = "2026-07-29"
        val sessions = listOf(
            session(1, appId = 10, startAt = atUtc(2026, 7, 29, 10, 0), minutes = 153),
        )
        val progress = DayProgress(date = day, minutesPlayed = 153, goalMinutesPlayed = 255, questMet = true)

        val result = groupHistory(
            sessions = sessions,
            games = listOf(game(10, "Focus Game", isGoal = true)),
            dailyProgress = listOf(progress),
            achievementUnlocks = emptyList(),
            zone = zone,
        ).single()

        assertEquals(153, result.minutesPlayed)
        assertEquals(153, result.goalMinutesPlayed)
    }

    @Test
    fun dayWithProgressButNoSessions_stillAppears() {
        val progress = DayProgress(date = "2026-07-20", minutesPlayed = 0, goalMinutesPlayed = 0, questMet = false)

        val days = groupHistory(
            sessions = emptyList(),
            games = emptyList(),
            dailyProgress = listOf(progress),
            achievementUnlocks = emptyList(),
            zone = zone,
        )

        val result = days.single()
        assertEquals("2026-07-20", result.date)
        assertEquals(0, result.minutesPlayed)
        assertTrue(result.games.isEmpty())
    }

    @Test
    fun sessionForUnknownGame_fallsBackToAppIdLabel() {
        val session = session(1, appId = 999, startAt = atUtc(2026, 7, 25, 12, 0), minutes = 10)

        val result = groupHistory(
            sessions = listOf(session),
            games = emptyList(),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            zone = zone,
        ).single()

        assertEquals("App 999", result.games.single().name)
    }

    @Test
    fun exactlyFiveUnlocks_showsFiveIconsAndNoOverflow() {
        val day = "2026-07-25"
        val unlocks = (1..5).map { unlock(appId = it.toLong(), icon = "icon$it", day = day) }

        val result = groupHistory(
            sessions = emptyList(),
            games = emptyList(),
            dailyProgress = listOf(DayProgress(day, 0, 0, false)),
            achievementUnlocks = unlocks,
            zone = zone,
        ).single()

        assertEquals(5, result.achievements.iconUrls.size)
        assertEquals(0, result.achievements.overflowCount)
    }

    @Test
    fun sixUnlocks_showsFiveIconsPlusCorrectOverflowCount() {
        val day = "2026-07-25"
        val unlocks = (1..6).map { unlock(appId = it.toLong(), icon = "icon$it", day = day) }

        val result = groupHistory(
            sessions = emptyList(),
            games = emptyList(),
            dailyProgress = listOf(DayProgress(day, 0, 0, false)),
            achievementUnlocks = unlocks,
            zone = zone,
        ).single()

        assertEquals(5, result.achievements.iconUrls.size)
        assertEquals(1, result.achievements.overflowCount)
    }

    @Test
    fun noUnlocks_producesEmptyThumbnailRow() {
        val day = "2026-07-25"

        val result = groupHistory(
            sessions = emptyList(),
            games = emptyList(),
            dailyProgress = listOf(DayProgress(day, 0, 0, false)),
            achievementUnlocks = emptyList(),
            zone = zone,
        ).single()

        assertTrue(result.achievements.iconUrls.isEmpty())
        assertEquals(0, result.achievements.overflowCount)
    }

    @Test
    fun unlockFromGameNotPlayedThatDay_stillAppearsInTheRow() {
        // The player finished nothing that day, but an achievement unlocked retroactively/idle.
        val day = "2026-07-25"
        val unlocks = listOf(unlock(appId = 42, icon = "icon42", day = day))

        val result = groupHistory(
            sessions = emptyList(),
            games = listOf(game(42, "Untouched Today")),
            dailyProgress = listOf(DayProgress(day, 0, 0, false)),
            achievementUnlocks = unlocks,
            zone = zone,
        ).single()

        assertTrue(result.games.isEmpty())
        assertEquals(listOf("icon42"), result.achievements.iconUrls)
    }

    @Test
    fun historyWindowCutoff_isTheLocalDayBoundary_notNowMinusNTimes24Hours() {
        val today = LocalDate.of(2026, 7, 30)

        val cutoff = historyWindowCutoffMillis(windowDays = 30, today = today, zone = zone)

        // 30-day window ending today includes the 29-days-ago boundary, i.e. starts on Jul 1.
        val expected = today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, cutoff)
    }

    private fun atUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun session(
        id: Long,
        appId: Long,
        startAt: Long,
        minutes: Int,
        open: Boolean = false,
    ) = PlaySession(id = id, appId = appId, startAt = startAt, minutes = minutes, open = open)

    private fun game(appId: Long, name: String, isGoal: Boolean = false) =
        LibraryGame(appId = appId, name = name, iconUrl = "icon-$appId", playtimeForever = 0, isGoal = isGoal)

    private fun unlock(appId: Long, icon: String, day: String) =
        AchievementUnlock(appId = appId, iconUrl = icon, unlockedAt = atUtcDate(day))

    private fun atUtcDate(isoDate: String): Long =
        LocalDate.parse(isoDate).atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
}
