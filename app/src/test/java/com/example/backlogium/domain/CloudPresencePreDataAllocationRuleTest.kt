package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresencePreDataAllocationRuleTest {
    @Test
    fun importedBudgetUsesNewestEligibleSlotsFirst() {
        val oldStart = instant("2025-06-01T10:00:00Z")
        val recentStart = instant("2025-06-01T12:00:00Z")
        val selection = selection(oldStart, recentStart + minutes(4))

        val allocation = allocate(
            intervals = listOf(
                interval(GAME, oldStart, oldStart + minutes(3)),
                interval(GAME, recentStart, recentStart + minutes(4)),
            ),
            balances = mapOf(GAME to 2),
            selection = selection,
            cutoffAt = recentStart + minutes(4),
        )

        assertEquals(2, allocation.transferredMinutesByAppId[GAME])
        assertEquals(0, allocation.remainingImportedMinutesByAppId[GAME])
        assertEquals(listOf(recentStart + minutes(2)), allocation.sessions.map { it.startAt })
        assertEquals(listOf(recentStart + minutes(4)), allocation.sessions.map { it.endAt })
        assertEquals(listOf(2), allocation.sessions.map { it.minutes })
    }

    @Test
    fun sessionsAreSplitAtLocalMidnightAndOnlyWholeMinutesAreCredited() {
        val zone = ZoneId.of("America/Los_Angeles")
        val start = instant("2025-06-02T06:58:30Z")
        val end = instant("2025-06-02T07:02:30Z")
        val allocation = allocate(
            intervals = listOf(interval(GAME, start, end)),
            balances = mapOf(GAME to 10),
            selection = selection(start, end, zone.id),
            cutoffAt = end,
        )

        assertEquals(3, allocation.transferredMinutesByAppId[GAME])
        assertEquals(
            listOf(
                SessionExpectation(
                    startAt = instant("2025-06-02T06:59:00Z"),
                    endAt = instant("2025-06-02T07:00:00Z"),
                    minutes = 1,
                    date = LocalDate.parse("2025-06-01"),
                ),
                SessionExpectation(
                    startAt = instant("2025-06-02T07:00:00Z"),
                    endAt = instant("2025-06-02T07:02:00Z"),
                    minutes = 2,
                    date = LocalDate.parse("2025-06-02"),
                ),
            ),
            allocation.sessions.map { session ->
                SessionExpectation(
                    session.startAt,
                    session.endAt!!,
                    session.minutes,
                    Instant.ofEpochMilli(session.startAt).atZone(zone).toLocalDate(),
                )
            },
        )
        assertTrue(allocation.sessions.all {
            it.open.not() && it.recoveredSharedPlay == RecoveredSharedPlayState.NONE &&
                it.timingInformedSteamPlay == TimingInformedSteamPlayState.FULL
        })
    }

    @Test
    fun budgetsArePerOwnedGameAndUnspentImportedMinutesRemainUntouched() {
        val start = instant("2025-06-01T10:00:00Z")
        val allocation = allocate(
            intervals = listOf(
                interval(GAME, start, start + minutes(5)),
                interval(OTHER_GAME, start + minutes(10), start + minutes(13)),
                interval(SHARED_GAME, start + minutes(20), start + minutes(23)),
            ),
            ownedAppIds = setOf(GAME, OTHER_GAME),
            balances = mapOf(GAME to 2, OTHER_GAME to 5, SHARED_GAME to 9),
            selection = selection(start, start + minutes(30)),
            cutoffAt = start + minutes(30),
        )

        assertEquals(mapOf(GAME to 2, OTHER_GAME to 3), allocation.transferredMinutesByAppId)
        assertEquals(
            mapOf(GAME to 0, OTHER_GAME to 2, SHARED_GAME to 9),
            allocation.remainingImportedMinutesByAppId,
        )
        assertEquals(2, allocation.sessions.filter { it.appId == GAME }.sumOf { it.minutes })
        assertEquals(3, allocation.sessions.filter { it.appId == OTHER_GAME }.sumOf { it.minutes })
        assertFalse(allocation.sessions.any { it.appId == SHARED_GAME })
    }

    @Test
    fun unknownLongGapAndUnobservedTailDoNotCreateUnsafeSlots() {
        val start = instant("2025-06-01T10:00:00Z")
        val allocation = allocate(
            intervals = listOf(
                interval(GAME, start, start + minutes(5), coverage = CloudCoverageState.UNKNOWN),
                interval(
                    GAME,
                    start + minutes(10),
                    start + minutes(15),
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = start + minutes(13),
                ),
                interval(
                    GAME,
                    start + minutes(20),
                    start + minutes(25),
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = start + minutes(25),
                    lapseFrom = start + minutes(22),
                    lapseRecoveredAt = start + minutes(23),
                ),
                interval(
                    GAME,
                    start + minutes(30),
                    start + minutes(50),
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = start + minutes(50),
                    lapseFrom = start + minutes(31),
                    lapseRecoveredAt = start + minutes(45),
                ),
            ),
            balances = mapOf(GAME to 100),
            selection = selection(start, start + minutes(50)),
            cutoffAt = start + minutes(50),
        )

        assertEquals(7, allocation.transferredMinutesByAppId[GAME])
        assertEquals(
            listOf(
                start + minutes(10), start + minutes(11), start + minutes(12),
                start + minutes(20), start + minutes(21), start + minutes(23), start + minutes(24),
            ),
            allocation.sessions.flatMap { session ->
                generateSequence(session.startAt) { previous ->
                    (previous + CloudPresencePreDataAllocationRule.MINUTE_MILLIS)
                        .takeIf { it < session.endAt!! }
                }.toList()
            },
        )
    }

    @Test
    fun selectedRangeAndCutoffClipSlotsAndExistingRefilesReserveTheirTime() {
        val start = instant("2025-06-01T10:00:00Z")
        val through = start + minutes(10)
        val original = session(GAME, start + minutes(6), through, 1)
        val selection = selection(start, through)
        val presence = listOf(
            interval(GAME, start, start + minutes(6)),
            interval(GAME, start + minutes(7), start + minutes(9)),
        )
        val refiles = cloudPresenceSessionRefiles(
            sessions = listOf(original),
            ownedAppIds = setOf(GAME),
            intervals = presence,
            range = selection,
        )
        val replacementRows = refiles.flatMap { it.replacement }

        val allocation = allocate(
            intervals = presence,
            balances = mapOf(GAME to 10),
            // This represents the rows left in Room after replacing the original with its plan.
            existingSessions = emptyList(),
            refiledSessions = replacementRows,
            selection = selection,
            cutoffAt = through,
        )

        assertEquals(1, replacementRows.size)
        assertEquals(start + minutes(7), replacementRows.single().startAt)
        assertEquals(6, allocation.transferredMinutesByAppId[GAME])
        assertEquals(4, allocation.remainingImportedMinutesByAppId[GAME])
        assertTrue(allocation.sessions.all { session ->
            session.startAt >= selection.effectiveStartAt && session.endAt!! <= through &&
                replacementRows.none { replacement -> overlaps(session, replacement) }
        })
    }

    private fun allocate(
        intervals: List<CloudPresenceInterval>,
        ownedAppIds: Set<Long> = setOf(GAME),
        balances: Map<Long, Int>,
        existingSessions: List<Session> = emptyList(),
        refiledSessions: List<Session> = emptyList(),
        selection: CloudPresenceHistoricalSelection,
        cutoffAt: Long,
    ) = CloudPresencePreDataAllocationRule.allocate(
        intervals = intervals,
        ownedAppIds = ownedAppIds,
        importedMinutesByAppId = balances,
        existingSessions = existingSessions,
        refiledSessions = refiledSessions,
        selection = selection,
        cutoffAt = cutoffAt,
    )

    private fun selection(
        startAt: Long,
        throughAt: Long,
        zoneId: String = "UTC",
    ) = CloudPresenceHistoricalSelection(
        choice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
        selectedStartAt = startAt,
        effectiveStartAt = startAt,
        throughAt = throughAt,
        zoneId = zoneId,
    )

    private fun interval(
        appId: Long,
        startAt: Long,
        endAt: Long,
        coverage: CloudCoverageState = CloudCoverageState.CONTINUOUS,
        observedUntil: Long? = null,
        lapseFrom: Long? = null,
        lapseRecoveredAt: Long? = null,
    ) = CloudPresenceInterval(
        appId = appId,
        gameName = "Game $appId",
        startAt = startAt,
        endAt = endAt,
        ongoing = false,
        coverage = coverage,
        observedUntil = observedUntil,
        coverageLapseFrom = lapseFrom,
        coverageLapseRecoveredAt = lapseRecoveredAt,
        mayHaveStartedBefore = false,
    )

    private fun session(appId: Long, startAt: Long, endAt: Long, minutes: Int) = Session(
        appId = appId,
        startAt = startAt,
        endAt = endAt,
        minutes = minutes,
        open = false,
    )

    private fun overlaps(first: Session, second: Session): Boolean =
        first.startAt < second.endAt!! && second.startAt < first.endAt!!

    private fun minutes(value: Long): Long = value * CloudPresencePreDataAllocationRule.MINUTE_MILLIS

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()

    private data class SessionExpectation(
        val startAt: Long,
        val endAt: Long,
        val minutes: Int,
        val date: LocalDate,
    )

    private companion object {
        const val GAME = 440L
        const val OTHER_GAME = 570L
        const val SHARED_GAME = 999L
    }
}
