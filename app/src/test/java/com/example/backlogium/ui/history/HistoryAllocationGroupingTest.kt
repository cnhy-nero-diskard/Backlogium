package com.example.backlogium.ui.history

import com.example.backlogium.data.repo.DayProgress
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.ui.components.RecoveryExplanationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Unknown-time allocation grouping (recover-missed-playtime tasks 7.1/7.3): allocation-only and
 * mixed days show exact frozen-date totals, allocations render as leaves with no invented clock
 * boundaries, multi-day windows group into one honest `!` explanation, and older-record detection
 * includes allocation dates for paging.
 */
class HistoryAllocationGroupingTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 7, 27)

    private val multiWindow = HistoryAllocationWindow(
        windowStartDate = LocalDate.of(2026, 7, 24),
        windowEndDate = LocalDate.of(2026, 7, 27),
        zone = "UTC",
        totalMinutes = 105,
    )

    @Test
    fun allocationOnlyDay_appearsWithGameLeafAndExactFrozenTotal() {
        val allocation = allocation(date = "2026-07-27", minutes = 27, window = multiWindow)
        val progress = DayProgress("2026-07-27", 27, 0, questMet = true)

        val days = groupHistory(
            sessions = emptyList(),
            games = listOf(game(440, "Hades II")),
            dailyProgress = listOf(progress),
            achievementUnlocks = emptyList(),
            allocations = listOf(allocation),
        )

        val day = days.single()
        assertEquals("2026-07-27", day.date)
        assertEquals(27, day.minutesPlayed)
        assertEquals(27, day.estimatedMinutes)
        assertTrue(day.questMet)
        val gameGroup = day.games.single()
        assertEquals(27, gameGroup.minutesPlayed)
        assertEquals(27, gameGroup.estimatedMinutes)
        assertTrue(gameGroup.sessions.isEmpty())
        assertEquals(listOf(allocation), gameGroup.allocations)
        // Allocation leaves carry date + minutes + method only; there is no start/end or open
        // state anywhere in the model that could render a clock boundary.
        assertFalse(gameGroup.allocations.any { it is HistorySessionUi })
    }

    @Test
    fun mixedDay_totalsEqualSessionsPlusAllocations_andBothRenderUnderTheGame() {
        val session = session(id = 1, appId = 440, startAt = atUtc(2026, 7, 27, 18, 20), minutes = 42)
        val allocation = allocation(date = "2026-07-27", minutes = 35, window = multiWindow)

        val day = groupHistory(
            sessions = listOf(session),
            games = listOf(game(440, "Hades II")),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            zone = zone,
            allocations = listOf(allocation),
        ).single()

        assertEquals(77, day.minutesPlayed)
        assertEquals(35, day.estimatedMinutes)
        val gameGroup = day.games.single()
        assertEquals(77, gameGroup.minutesPlayed)
        assertEquals(listOf(42), gameGroup.sessions.map { it.minutes })
        assertEquals(listOf(35), gameGroup.allocations.map { it.minutes })
    }

    @Test
    fun multiDayShares_GroupIntoOneWindowExplanationWithExactShares() {
        val window = HistoryAllocationWindow(
            windowStartDate = LocalDate.of(2026, 7, 24),
            windowEndDate = LocalDate.of(2026, 7, 27),
            zone = "UTC",
            totalMinutes = 105,
        )
        val allocations = listOf(
            allocation(date = "2026-07-24", minutes = 27, window = window),
            allocation(date = "2026-07-25", minutes = 26, window = window),
            allocation(date = "2026-07-26", minutes = 26, window = window),
            allocation(date = "2026-07-27", minutes = 26, window = window),
        )

        val shares = recoveryShares(allocations)

        assertEquals(1, shares.size)
        val share = shares.single()
        assertEquals(RecoveryExplanationKind.MULTI_DAY_ESTIMATE, share.kind)
        assertEquals(LocalDate.of(2026, 7, 24), share.windowStart)
        assertEquals(LocalDate.of(2026, 7, 27), share.windowEnd)
        assertEquals("UTC", share.zone)
        assertEquals(105, share.windowTotalMinutes)
        assertEquals(105, share.shareMinutes)
    }

    @Test
    fun multiDayShares_splitAcrossTwoWindows_ProduceTwoExplanationsInDateOrder() {
        val first = HistoryAllocationWindow(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 7, 21), null, 10)
        val second = HistoryAllocationWindow(LocalDate.of(2026, 7, 24), LocalDate.of(2026, 7, 27), "UTC", 105)
        val shares = recoveryShares(listOf(
            allocation(date = "2026-07-27", minutes = 26, window = second),
            allocation(date = "2026-07-20", minutes = 10, window = first),
        ))

        assertEquals(2, shares.size)
        assertEquals(LocalDate.of(2026, 7, 20), shares[0].windowStart)
        assertEquals(LocalDate.of(2026, 7, 24), shares[1].windowStart)
        assertEquals(10, shares[0].shareMinutes)
        assertEquals(26, shares[1].shareMinutes)
    }

    @Test
    fun singleDayAllocation_hasDateOnlyWindowWithItsOwnTotal() {
        val allocation = allocation(
            date = "2026-07-27",
            minutes = 70,
            method = HistoryAllocationMethod.SINGLE_DAY_ESTIMATE,
            window = HistoryAllocationWindow(
                windowStartDate = LocalDate.of(2026, 7, 27),
                windowEndDate = LocalDate.of(2026, 7, 27),
                zone = null,
                totalMinutes = 70,
            ),
            appId = 610L,
        )

        val day = groupHistory(
            sessions = emptyList(),
            games = listOf(game(610, "Celeste")),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            allocations = listOf(allocation),
        ).single()

        assertEquals(70, day.minutesPlayed)
        assertEquals(70, day.estimatedMinutes)
        val share = recoveryShares(listOf(allocation)).single()
        assertEquals(RecoveryExplanationKind.SINGLE_DAY_ESTIMATE, share.kind)
        assertEquals(LocalDate.of(2026, 7, 27), share.windowStart)
        assertEquals(LocalDate.of(2026, 7, 27), share.windowEnd)
        assertEquals(70, share.windowTotalMinutes)
    }

    @Test
    fun goalTotals_UseTheFrozenAllocationFactWhenPresent() {
        val allocation = allocation(
            date = "2026-07-27",
            minutes = 70,
            goalAtCredit = true,
        )
        val day = groupHistory(
            sessions = emptyList(),
            games = listOf(game(610, "Celeste", isGoal = false)),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            allocations = listOf(allocation),
        ).single()

        assertEquals(70, day.goalMinutesPlayed)
    }

    @Test
    fun goalTotals_fallBackToCurrentGoalSetWhenFrozenFactAbsent() {
        val allocation = allocation(date = "2026-07-27", minutes = 70, goalAtCredit = null, appId = 610)
        val session = session(1, 610, atUtc(2026, 7, 27, 12, 0), 30)

        val day = groupHistory(
            sessions = listOf(session),
            games = listOf(game(610, "Celeste", isGoal = true)),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            allocations = listOf(allocation),
        ).single()

        assertEquals(100, day.goalMinutesPlayed)
    }

    @Test
    fun windowFilters_KeepAllocationDatesInsideTheLoadedRange() {
        val inside = allocation(date = "2026-07-20", minutes = 26, window = multiWindow)
        val outside = allocation(date = "2026-06-01", minutes = 30)

        val days = groupHistory(
            sessions = emptyList(),
            games = listOf(game(440, "Hades II")),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            allocations = listOf(inside, outside),
            windowStartDate = "2026-07-01",
            windowEndDate = "2026-07-27",
        )

        assertEquals(listOf("2026-07-20"), days.map { it.date })
    }

    @Test
    fun allocationsOlderThanTheLoadedWindow_KeepLoadOlderAvailable() {
        val olderAllocationDate = "2026-06-01"
        assertTrue(historyHasOlderRecords(
            windowStartDate = "2026-07-01",
            earliestSessionAt = null,
            dailyProgress = emptyList(),
            zone = zone,
            earliestAllocationDate = olderAllocationDate,
        ))
        assertFalse(historyHasOlderRecords(
            windowStartDate = "2026-06-01",
            earliestSessionAt = null,
            dailyProgress = emptyList(),
            zone = zone,
            earliestAllocationDate = "2026-07-20",
        ))
    }

    @Test
    fun allocationOnlyDays_StillProduceGameThumbnailsForTheHeader() {
        val allocation = allocation(date = "2026-07-27", minutes = 70, appId = 610)
        val day = groupHistory(
            sessions = emptyList(),
            games = listOf(game(610, "Celeste")),
            dailyProgress = emptyList(),
            achievementUnlocks = emptyList(),
            allocations = listOf(allocation),
        ).single()

        assertEquals(listOf(610L), day.gameThumbnails.games.map { it.appId })
        assertEquals(0, day.gameThumbnails.overflowCount)
    }

    private fun allocation(
        date: String,
        minutes: Int,
        method: HistoryAllocationMethod = HistoryAllocationMethod.MULTI_DAY_ESTIMATE,
        window: HistoryAllocationWindow = multiWindow,
        appId: Long = 440L,
        goalAtCredit: Boolean? = null,
    ) = HistoryAllocationUi(
        placementId = "a|$date|$minutes",
        appId = appId,
        date = LocalDate.parse(date),
        minutes = minutes,
        method = method,
        window = window,
        goalAtCredit = goalAtCredit,
    )

    private fun session(id: Long, appId: Long, startAt: Long, minutes: Int, open: Boolean = false) =
        PlaySession(id = id, appId = appId, startAt = startAt, minutes = minutes, open = open)

    private fun game(appId: Long, name: String, isGoal: Boolean = false) =
        LibraryGame(appId = appId, name = name, iconUrl = "icon-$appId", playtimeForever = 0, isGoal = isGoal)

    private fun atUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
}
