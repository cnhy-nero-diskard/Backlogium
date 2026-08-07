package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PersonalPaceTest {

    private val today = LocalDate.parse("2026-08-07")

    @Test
    fun dailyTotals_bucketByInjectedZone_andExcludeCurrentAndOpenSessions() {
        val zone = ZoneId.of("Asia/Taipei")
        val sameLocalDate = Instant.parse("2026-08-01T23:30:00Z").toEpochMilli()
        val currentDate = Instant.parse("2026-08-07T01:00:00Z").toEpochMilli()
        val totals = PersonalPace.dailyTotals(
            sessions = listOf(
                PersonalPaceSession(sameLocalDate, minutes = 20),
                PersonalPaceSession(sameLocalDate + 20 * 60_000L, minutes = 35),
                PersonalPaceSession(
                    startAtMillis = sameLocalDate + 30 * 60_000L,
                    minutes = 99,
                    open = true,
                ),
                PersonalPaceSession(currentDate, minutes = 80),
            ),
            today = today,
            zone = zone,
        )

        assertEquals(listOf(DatedPlayTotal(LocalDate.parse("2026-08-02"), 55)), totals)
    }

    @Test
    fun profile_fillsCoveredZeroDates_andConfidenceUsesCoverageAndActiveBoundaries() {
        val first = today.minusDays(28)
        val sixActive = (0 until 6).map { offset ->
            DatedPlayTotal(first.plusDays(offset.toLong()), 60)
        }
        val reliable = PersonalPace.derive(sixActive, today)
        assertEquals(28, reliable.coveredDates)
        assertEquals(6, reliable.activeDates)
        assertEquals(PersonalPaceConfidence.RELIABLE, reliable.confidence)
        assertEquals(22, reliable.dailyTotals.count { it.minutes == 0 })

        val twentySeven = PersonalPace.derive(
            sixActive.map { it.copy(date = it.date.plusDays(1)) },
            today,
        )
        assertEquals(27, twentySeven.coveredDates)
        assertEquals(PersonalPaceConfidence.LEARNING, twentySeven.confidence)

        val fiveActive = PersonalPace.derive(
            sixActive.dropLast(1),
            today,
        )
        assertEquals(PersonalPaceConfidence.LEARNING, fiveActive.confidence)
    }

    @Test
    fun recentBehaviorOutweighsOlderBehavior_andMarathonDoesNotDefineTypicalDuration() {
        val recent = (0 until 28).map { offset ->
            DatedPlayTotal(today.minusDays((offset + 1).toLong()), 60)
        }
        val old = (28 until 55).map { offset ->
            DatedPlayTotal(today.minusDays((offset + 1).toLong()), 180)
        }
        val profile = PersonalPace.derive(recent + old + DatedPlayTotal(today.minusDays(56), 2_000), today)

        assertTrue(profile.typicalActiveDayMinutes < 120.0)
        assertTrue(profile.typicalActiveDayMinutes <= 60.0)
    }

    @Test
    fun sparseWeekdayDuration_blendsTowardGlobalPattern() {
        val dates = listOf(
            DatedPlayTotal(LocalDate.parse("2026-07-31"), 60), // Friday
            DatedPlayTotal(LocalDate.parse("2026-08-01"), 60), // Saturday
            DatedPlayTotal(LocalDate.parse("2026-08-02"), 60), // Sunday
            DatedPlayTotal(LocalDate.parse("2026-08-03"), 120), // Monday, sparse
        )
        val profile = PersonalPace.derive(dates, today)
        val monday = profile.weekdayHabits.getValue(DayOfWeek.MONDAY)

        assertEquals(1, monday.activeDateCount)
        assertTrue(monday.typicalActiveDayMinutes > profile.typicalActiveDayMinutes)
        assertTrue(monday.typicalActiveDayMinutes < 120.0)
    }

    @Test
    fun forecast_isInclusive_andCalculatesRequiredPaceWithoutDivisionByZero() {
        val profile = PersonalPace.derive(
            (1..28).map { offset ->
                DatedPlayTotal(today.minusDays(offset.toLong()), 60)
            },
            today,
        )
        val forecast = profile.forecast(today.plusDays(1), today.plusDays(3), 180)

        assertEquals(3, forecast.endDate.toEpochDay() - forecast.startDate.toEpochDay() + 1)
        assertTrue(forecast.expectedActiveDays > 0.0)
        assertEquals(
            180.0 / forecast.expectedActiveDays,
            forecast.requiredMinutesPerActiveDay!!,
            1e-9,
        )

        val emptyForecast = PersonalPaceProfile.empty().forecast(
            today.plusDays(1),
            today.plusDays(3),
            requiredMinutes = 180,
        )
        assertEquals(0.0, emptyForecast.expectedActiveDays, 0.0)
        assertEquals(null, emptyForecast.requiredMinutesPerActiveDay)
    }

    @Test
    fun derive_isDeterministicForSameInputs() {
        val totals = listOf(
            DatedPlayTotal(today.minusDays(1), 45),
            DatedPlayTotal(today.minusDays(8), 90),
        )

        val first = PersonalPace.derive(totals, today)
        val second = PersonalPace.derive(totals, today)

        assertEquals(first, second)
        assertNotEquals(first.dailyTotals, emptyList<DatedPlayTotal>())
    }
}
