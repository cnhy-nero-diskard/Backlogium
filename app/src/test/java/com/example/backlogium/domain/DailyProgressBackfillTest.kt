package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Session
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one-time correction of totals recorded under poll-time attribution
 * (auditfix-day-attribution Decision 7).
 *
 * Targets the pure rule rather than the use case: applying it is a write plus a recompute through
 * machinery covered elsewhere, while *which dates change and to what* is the whole decision.
 */
class DailyProgressBackfillTest {

    private val zone = ZoneId.of("Asia/Manila")

    private fun at(date: String, hour: Int, minute: Int): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, minute), zone).toInstant().toEpochMilli()

    private fun session(appId: Long, startMillis: Long, minutes: Int) = Session(
        appId = appId,
        startAt = startMillis,
        endAt = startMillis,
        minutes = minutes,
        open = false,
    )

    private fun day(date: String, minutes: Int, goalMinutes: Int = 0) =
        DailyProgress(date = date, minutesPlayed = minutes, goalMinutesPlayed = goalMinutes, questMet = minutes >= 30)

    private fun corrections(
        sessions: List<Session>,
        stored: List<DailyProgress>,
        goalAppIds: Set<Long> = emptySet(),
    ) = dailyProgressCorrections(sessions, goalAppIds, stored, zone)

    @Test
    fun `a midnight-crossing session moves its minutes to the date it began`() {
        // The reported case: 31 minutes starting 23:54 on the 13th, credited by the old rule to the
        // 14th because that is when the poll observing it ran.
        val result = corrections(
            sessions = listOf(
                session(1, at("2026-08-13", 23, 54), 31),
                session(2, at("2026-08-14", 9, 9), 21),
            ),
            stored = listOf(day("2026-08-13", 0), day("2026-08-14", 52, goalMinutes = 31)),
        ).associateBy { it.date }

        assertEquals(31, result.getValue("2026-08-13").correctedMinutes)
        assertEquals(21, result.getValue("2026-08-14").correctedMinutes)
        assertEquals(52, result.getValue("2026-08-14").storedMinutes)
    }

    @Test
    fun `dates before the earliest session are left untouched`() {
        // The first sync baselines the library without synthesizing sessions, so the earliest
        // stored row can predate the ledger. Rebuilding it would report no records as no play.
        val result = corrections(
            sessions = listOf(session(1, at("2026-07-24", 12, 0), 60)),
            stored = listOf(day("2026-07-23", 90), day("2026-07-24", 10)),
        )

        assertEquals(listOf("2026-07-24"), result.map { it.date })
    }

    @Test
    fun `a stored date whose sessions all moved away falls to zero`() {
        val result = corrections(
            sessions = listOf(session(1, at("2026-08-13", 23, 30), 45)),
            stored = listOf(day("2026-08-13", 0), day("2026-08-14", 45)),
        ).associateBy { it.date }

        assertEquals(45, result.getValue("2026-08-13").correctedMinutes)
        assertEquals(0, result.getValue("2026-08-14").correctedMinutes)
    }

    @Test
    fun `dates already agreeing with the ledger produce no correction`() {
        val result = corrections(
            sessions = listOf(session(1, at("2026-08-11", 10, 0), 134)),
            stored = listOf(day("2026-08-11", 134)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `focus minutes count only games currently flagged as goals`() {
        val result = corrections(
            sessions = listOf(
                session(1, at("2026-08-12", 10, 0), 60),
                session(2, at("2026-08-12", 14, 0), 44),
            ),
            stored = listOf(day("2026-08-12", 74, goalMinutes = 67)),
            goalAppIds = setOf(1L),
        )

        assertEquals(104, result.single().correctedMinutes)
        assertEquals(60, result.single().correctedGoalMinutes)
    }

    @Test
    fun `a goal-minutes-only disagreement is still corrected`() {
        val result = corrections(
            sessions = listOf(session(1, at("2026-08-12", 10, 0), 60)),
            stored = listOf(day("2026-08-12", 60, goalMinutes = 0)),
            goalAppIds = setOf(1L),
        )

        assertEquals(60, result.single().correctedGoalMinutes)
    }

    @Test
    fun `an empty session ledger corrects nothing`() {
        val result = corrections(
            sessions = emptyList(),
            stored = listOf(day("2026-08-12", 74)),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `session minutes are attributed in the device zone, not UTC`() {
        // 07:30 local on the 14th is 23:30 UTC on the 13th. Attributing in UTC would move this
        // session to the previous day.
        val result = corrections(
            sessions = listOf(session(1, at("2026-08-14", 7, 30), 40)),
            stored = emptyList(),
        )

        assertEquals(listOf("2026-08-14"), result.map { it.date })
    }

    @Test
    fun `several sessions on one date are summed`() {
        val result = corrections(
            sessions = listOf(
                session(1, at("2026-08-13", 20, 18), 19),
                session(2, at("2026-08-13", 22, 34), 7),
                session(3, at("2026-08-13", 23, 4), 60),
            ),
            stored = listOf(day("2026-08-13", 138)),
        )

        assertEquals(86, result.single().correctedMinutes)
    }
}
