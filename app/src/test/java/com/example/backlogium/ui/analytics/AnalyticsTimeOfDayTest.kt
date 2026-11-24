package com.example.backlogium.ui.analytics

import com.example.backlogium.data.repo.PlaySession
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTimeOfDayTest {

    private val zone = ZoneId.of("Asia/Manila")

    @Test
    fun overnightSessionMinutesStayInTheLocalStartBucket() {
        val start = LocalDate.of(2026, 9, 10).atTime(23, 30).atZone(zone).toInstant()
        val session = PlaySession(
            id = 1,
            appId = 440,
            startAt = start.toEpochMilli(),
            minutes = 150,
            open = false,
            endAt = start.plusSeconds(3 * 60 * 60).toEpochMilli(),
        )

        assertEquals(
            TimeOfDayPattern(nightMinutes = 150),
            deriveTimeOfDayPattern(listOf(session), zone),
        )
    }

    @Test
    fun recoveredMinutesWithoutSessionStartsStayOutOfClockBuckets() {
        val sessions = emptyList<PlaySession>()

        assertEquals(TimeOfDayPattern(), deriveTimeOfDayPattern(sessions, zone))
        assertTrue(analyticsTimingUnavailable(totalMinutes = 105, sessions = sessions))
    }
}
