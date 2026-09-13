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

    /**
     * Both halves of the reliability rule, pinned independently at their boundaries
     * (lower-pace-reliability-threshold lowered the covered-date half from 28 to 14).
     *
     * They are separate assertions because they refuse different things: the covered-date
     * threshold refuses a profile that has not observed enough time to describe a pattern, and the
     * active-date floor refuses one assembled from almost no play. A single combined case could
     * pass while one of them had stopped being enforced.
     */
    @Test
    fun profile_fillsCoveredZeroDates_andConfidenceUsesCoverageAndActiveBoundaries() {
        val first = today.minusDays(14)
        val sixActive = (0 until 6).map { offset ->
            DatedPlayTotal(first.plusDays(offset.toLong()), 60)
        }
        val reliable = PersonalPace.derive(sixActive, today)
        assertEquals(14, reliable.coveredDates)
        assertEquals(6, reliable.activeDates)
        assertEquals(PersonalPaceConfidence.RELIABLE, reliable.confidence)
        // The span is filled with zero-minute dates rather than being treated as unobserved.
        assertEquals(8, reliable.dailyTotals.count { it.minutes == 0 })

        // One date short of the span, with the same six active dates.
        val thirteen = PersonalPace.derive(
            sixActive.map { it.copy(date = it.date.plusDays(1)) },
            today,
        )
        assertEquals(13, thirteen.coveredDates)
        assertEquals(PersonalPaceConfidence.LEARNING, thirteen.confidence)

        // Full span, one active date short.
        val fiveActive = PersonalPace.derive(
            sixActive.dropLast(1),
            today,
        )
        assertEquals(14, fiveActive.coveredDates)
        assertEquals(PersonalPaceConfidence.LEARNING, fiveActive.confidence)
    }

    /**
     * The threshold decides a label and nothing else.
     *
     * The same history is derived twice at spans that straddle the old threshold, and the forecast
     * is identical across both: reclassification cannot move a number. This is what makes lowering
     * the threshold safe for every profile that was *already* reliable — none of their figures
     * shift — and it is why the change needs no migration or recompute.
     */
    @Test
    fun theReliabilityThresholdGatesClassificationWithoutAlteringAnyForecast() {
        // Twenty covered dates with seven active: reliable under the new threshold, and learning
        // under the old one, so this is exactly a profile the change reclassifies.
        val history = (0 until 20).map { offset ->
            DatedPlayTotal(today.minusDays((offset + 1).toLong()), if (offset % 3 == 0) 90 else 0)
        }
        val profile = PersonalPace.derive(history, today)

        assertEquals(20, profile.coveredDates)
        assertEquals(7, profile.activeDates)
        assertEquals(PersonalPaceConfidence.RELIABLE, profile.confidence)

        // Confidence is derived *alongside* the forecast inputs, never *into* them. Relabelling an
        // otherwise identical profile cannot move a projected number — which is what makes
        // reclassifying an existing profile safe, and why the change needs no recompute and no
        // migration.
        val relabelled = profile.copy(confidence = PersonalPaceConfidence.LEARNING)
        val start = today.plusDays(1)
        val end = today.plusDays(30)
        val reliableForecast = profile.forecast(start, end)
        val learningForecast = relabelled.forecast(start, end)

        assertEquals(
            reliableForecast.expectedGamingMinutes,
            learningForecast.expectedGamingMinutes,
            0.0,
        )
        assertEquals(reliableForecast.expectedActiveDays, learningForecast.expectedActiveDays, 0.0)
        assertEquals(profile.weekdayHabits, relabelled.weekdayHabits)
        assertTrue("the fixture must actually forecast something", reliableForecast.expectedGamingMinutes > 0.0)
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

    /**
     * The sharp edge at the new reliability floor, pinned deliberately rather than left to be
     * discovered (lower-pace-reliability-threshold design decision 3).
     *
     * The weekday model has two halves and only one is protected against a thin sample.
     * `typicalActiveDayMinutes` is blended toward the global figure; `activeProbability` is not.
     * At exactly 14 covered dates each weekday is observed twice, so a weekday that happened to
     * catch no play reads as a *certainty* of no play, and contributes nothing to any forecast
     * containing it — while the profile is classified reliable.
     *
     * This is accepted because it understates capacity rather than overstating it, which is the
     * safe direction for a feasibility claim. Blending frequency the same way duration is blended
     * would change the numbers for every profile, so it is a separate change.
     */
    @Test
    fun atTheFloorAWeekdayWithNoActiveObservationsForecastsZeroCapacity() {
        // Fri 2026-07-24 through Thu 2026-08-06: exactly two weeks, so every weekday is observed
        // exactly twice — the thinnest sample the reliable classification now admits.
        val first = LocalDate.parse("2026-07-24")
        assertEquals(DayOfWeek.FRIDAY, first.dayOfWeek)
        val history = (0 until 14).map { offset ->
            val date = first.plusDays(offset.toLong())
            DatedPlayTotal(date, if (date.dayOfWeek == DayOfWeek.MONDAY) 0 else 60)
        }

        val profile = PersonalPace.derive(history, today)

        assertEquals(14, profile.coveredDates)
        assertEquals(12, profile.activeDates)
        assertEquals(PersonalPaceConfidence.RELIABLE, profile.confidence)

        val monday = profile.weekdayHabits.getValue(DayOfWeek.MONDAY)
        assertEquals(2, monday.observedDateCount)
        assertEquals(0, monday.activeDateCount)
        // Not blended toward the global active-day probability the way duration is, so two misses
        // read as certainty rather than as a sparse sample.
        assertEquals(0.0, monday.activeProbability, 0.0)

        val nextMonday = LocalDate.parse("2026-08-10")
        assertEquals(DayOfWeek.MONDAY, nextMonday.dayOfWeek)
        val mondayOnly = profile.forecast(nextMonday, nextMonday)
        assertEquals(0.0, mondayOnly.expectedGamingMinutes, 0.0)
        assertEquals(0.0, mondayOnly.expectedActiveDays, 0.0)

        // A weekday that was observed active forecasts normally, so the zero is specific to the
        // unobserved-active weekday rather than a profile that failed to derive at all.
        val nextTuesday = nextMonday.plusDays(1)
        assertTrue(profile.forecast(nextTuesday, nextTuesday).expectedGamingMinutes > 0.0)
    }

    /**
     * The other half of the same floor: duration *is* protected. A weekday whose two active
     * observations are wildly longer than everything else does not get to claim that duration as
     * its own typical value — the sparse-weekday blend pulls it toward the global figure, and at
     * two active observations the blend is capped at half.
     */
    @Test
    fun atTheFloorASparseWeekdayDurationStaysNearTheGlobalPattern() {
        val first = LocalDate.parse("2026-07-24")
        val history = (0 until 14).map { offset ->
            val date = first.plusDays(offset.toLong())
            DatedPlayTotal(date, if (date.dayOfWeek == DayOfWeek.MONDAY) 600 else 60)
        }

        val profile = PersonalPace.derive(history, today)
        val monday = profile.weekdayHabits.getValue(DayOfWeek.MONDAY)

        assertEquals(14, profile.coveredDates)
        assertEquals(PersonalPaceConfidence.RELIABLE, profile.confidence)
        assertEquals(2, monday.activeDateCount)

        // Its own sample still counts for something, but never for everything.
        assertTrue(monday.typicalActiveDayMinutes > profile.typicalActiveDayMinutes)
        assertTrue(monday.typicalActiveDayMinutes < 600.0)
        // Two of the four observations the blend wants means at most a half-weight on the local
        // figure, so the result cannot exceed the midpoint between global and local.
        assertTrue(
            "sparse weekday duration must stay at or below the global/local midpoint",
            monday.typicalActiveDayMinutes <= (profile.typicalActiveDayMinutes + 600.0) / 2.0 + 1e-6,
        )
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
