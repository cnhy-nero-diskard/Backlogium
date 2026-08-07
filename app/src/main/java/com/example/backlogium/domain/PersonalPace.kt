package com.example.backlogium.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.pow

/** One local-date observation consumed by the Personal Pace engine. */
data class DatedPlayTotal(
    val date: LocalDate,
    val minutes: Int,
)

/** The small, pure session projection used when deriving local-date observations. */
data class PersonalPaceSession(
    val startAtMillis: Long,
    val minutes: Int,
    val open: Boolean = false,
)

enum class PersonalPaceConfidence {
    LEARNING,
    RELIABLE,
}

/** A weekday's expected active-day frequency and duration, both intentionally unformatted. */
data class WeekdayHabit(
    val weekday: DayOfWeek,
    val activeProbability: Double,
    val typicalActiveDayMinutes: Double,
    val activeDateCount: Int,
    val observedDateCount: Int,
) {
    val expectedActiveProbability: Double
        get() = activeProbability

    val expectedActiveDayMinutes: Double
        get() = typicalActiveDayMinutes
}

/** Numeric projection for an inclusive local-date range. */
data class PersonalPaceForecast(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val expectedActiveDays: Double,
    val expectedGamingMinutes: Double,
    val requiredMinutesPerActiveDay: Double?,
) {
    val projectedActiveDays: Double
        get() = expectedActiveDays

    val projectedMinutes: Double
        get() = expectedGamingMinutes

    val requiredMinutesPerProjectedActiveDay: Double?
        get() = requiredMinutesPerActiveDay
}

/**
 * A confidence-aware, local-only habit profile. It contains no formatted strings and has no
 * Android or persistence dependency, which keeps collection feasibility JVM-testable.
 */
data class PersonalPaceProfile(
    val confidence: PersonalPaceConfidence,
    val coveredDates: Int,
    val activeDates: Int,
    val activeDayProbability: Double,
    val typicalActiveDayMinutes: Double,
    val weekdayHabits: Map<DayOfWeek, WeekdayHabit>,
    val dailyTotals: List<DatedPlayTotal> = emptyList(),
) {
    val isReliable: Boolean
        get() = confidence == PersonalPaceConfidence.RELIABLE

    val globalActiveProbability: Double
        get() = activeDayProbability

    val globalActiveDayMinutes: Double
        get() = typicalActiveDayMinutes

    fun habitFor(date: LocalDate): WeekdayHabit = weekdayHabits[date.dayOfWeek]
        ?: WeekdayHabit(
            weekday = date.dayOfWeek,
            activeProbability = activeDayProbability,
            typicalActiveDayMinutes = typicalActiveDayMinutes,
            activeDateCount = 0,
            observedDateCount = 0,
        )

    /** Project an inclusive date range; an inverted range produces a zero forecast. */
    fun forecast(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        requiredMinutes: Int? = null,
    ): PersonalPaceForecast {
        if (startDate.isAfter(endDateInclusive)) {
            return PersonalPaceForecast(
                startDate = startDate,
                endDate = endDateInclusive,
                expectedActiveDays = 0.0,
                expectedGamingMinutes = 0.0,
                requiredMinutesPerActiveDay = null,
            )
        }

        var expectedActiveDays = 0.0
        var expectedGamingMinutes = 0.0
        var date = startDate
        while (!date.isAfter(endDateInclusive)) {
            val habit = habitFor(date)
            expectedActiveDays += habit.activeProbability
            expectedGamingMinutes += habit.activeProbability * habit.typicalActiveDayMinutes
            date = date.plusDays(1)
        }
        val requiredPace = requiredMinutes
            ?.takeIf { it > 0 }
            ?.toDouble()
            ?.takeIf { expectedActiveDays > 0.0 }
            ?.div(expectedActiveDays)
        return PersonalPaceForecast(
            startDate = startDate,
            endDate = endDateInclusive,
            expectedActiveDays = expectedActiveDays,
            expectedGamingMinutes = expectedGamingMinutes,
            requiredMinutesPerActiveDay = requiredPace,
        )
    }

    fun forecast(
        range: ClosedRange<LocalDate>,
        requiredMinutes: Int? = null,
    ): PersonalPaceForecast = forecast(range.start, range.endInclusive, requiredMinutes)

    /**
     * Find the first date on which cumulative expected capacity covers [requiredMinutes]. The
     * horizon is bounded so an inactive profile cannot create an unbounded loop.
     */
    fun earliestFitDate(
        startDate: LocalDate,
        requiredMinutes: Int,
        maxDays: Int = DEFAULT_FIT_HORIZON_DAYS,
    ): LocalDate? {
        if (requiredMinutes <= 0) return startDate
        if (maxDays <= 0) return null
        var accumulatedMinutes = 0.0
        var date = startDate
        repeat(maxDays) {
            val habit = habitFor(date)
            accumulatedMinutes += habit.activeProbability * habit.typicalActiveDayMinutes
            if (accumulatedMinutes >= requiredMinutes) return date
            date = date.plusDays(1)
        }
        return null
    }

    companion object {
        const val DEFAULT_FIT_HORIZON_DAYS: Int = 1_095

        fun empty(): PersonalPaceProfile = PersonalPaceProfile(
            confidence = PersonalPaceConfidence.LEARNING,
            coveredDates = 0,
            activeDates = 0,
            activeDayProbability = 0.0,
            typicalActiveDayMinutes = 0.0,
            weekdayHabits = DayOfWeek.entries.associateWith { day ->
                WeekdayHabit(day, 0.0, 0.0, 0, 0)
            },
        )
    }
}

/**
 * Pure Personal Pace derivation. The latest 56 completed local dates are represented by the
 * available history span, with zero-minute dates filled between the first and latest observation.
 * This avoids pretending that dates before the first tracked session were observed while still
 * allowing gaps inside the tracked window to reduce active-day frequency.
 */
object PersonalPace {
    const val LOOKBACK_DAYS: Long = 56L
    private const val HALF_LIFE_DAYS = 28.0
    private const val MIN_ACTIVE_OBSERVATIONS_FOR_WEEKDAY = 4.0
    private const val RELIABLE_COVERED_DATES = 28
    private const val RELIABLE_ACTIVE_DATES = 6

    /** Aggregate closed session rows into local-date totals and exclude the current date. */
    fun dailyTotals(
        sessions: Iterable<PersonalPaceSession>,
        today: LocalDate,
        zone: ZoneId,
    ): List<DatedPlayTotal> {
        val firstDate = today.minusDays(LOOKBACK_DAYS)
        val lastDate = today.minusDays(1)
        return sessions.asSequence()
            .filterNot { it.open }
            .map { session ->
                Instant.ofEpochMilli(session.startAtMillis).atZone(zone).toLocalDate() to
                    session.minutes.coerceAtLeast(0)
            }
            .filter { (date, _) -> !date.isBefore(firstDate) && !date.isAfter(lastDate) }
            .groupingBy { it.first }
            .fold(0) { total, (_, minutes) -> total + minutes }
            .map { (date, minutes) -> DatedPlayTotal(date, minutes) }
            .sortedBy { it.date }
    }

    /** Derive directly from timestamped session projections. */
    fun derive(
        sessions: Iterable<PersonalPaceSession>,
        today: LocalDate,
        zone: ZoneId,
    ): PersonalPaceProfile = derive(dailyTotals(sessions, today, zone), today)

    /** Derive from already bucketed local-date totals. Duplicate dates are summed safely. */
    fun derive(
        totals: Iterable<DatedPlayTotal>,
        today: LocalDate,
    ): PersonalPaceProfile {
        val firstDate = today.minusDays(LOOKBACK_DAYS)
        val lastDate = today.minusDays(1)
        val aggregated = totals.asSequence()
            .filter { !it.date.isBefore(firstDate) && !it.date.isAfter(lastDate) }
            .groupingBy { it.date }
            .fold(0) { total, item -> total + item.minutes.coerceAtLeast(0) }
        val firstObserved = aggregated.keys.minOrNull() ?: return PersonalPaceProfile.empty()
        val dates = generateSequence(firstObserved) { current ->
            current.plusDays(1).takeUnless { it.isAfter(lastDate) }
        }.map { date ->
            DatedPlayTotal(date, aggregated[date] ?: 0)
        }.toList()

        val observations = dates.map { observation ->
            WeightedObservation(
                date = observation.date,
                minutes = observation.minutes.coerceAtLeast(0),
                weight = recencyWeight(observation.date, lastDate),
            )
        }
        val activeObservations = observations.filter { it.minutes > 0 }
        val totalWeight = observations.sumOf { it.weight }
        val activeWeight = activeObservations.sumOf { it.weight }
        val activeProbability = if (totalWeight == 0.0) 0.0 else activeWeight / totalWeight
        val globalNinetieth = weightedPercentile(activeObservations, 0.90)
        val globalTypical = robustTypicalMinutes(activeObservations, globalNinetieth)

        val weekdayHabits = DayOfWeek.entries.associateWith { weekday ->
            val weekdayObservations = observations.filter { it.date.dayOfWeek == weekday }
            val weekdayActive = weekdayObservations.filter { it.minutes > 0 }
            val weekdayWeight = weekdayObservations.sumOf { it.weight }
            val weekdayActiveWeight = weekdayActive.sumOf { it.weight }
            val probability = if (weekdayWeight == 0.0) 0.0 else weekdayActiveWeight / weekdayWeight
            val localTypical = robustTypicalMinutes(weekdayActive, globalNinetieth)
            val blend = (weekdayActive.size / MIN_ACTIVE_OBSERVATIONS_FOR_WEEKDAY).coerceIn(0.0, 1.0)
            WeekdayHabit(
                weekday = weekday,
                activeProbability = probability,
                typicalActiveDayMinutes = (globalTypical * (1.0 - blend) + localTypical * blend)
                    .coerceAtLeast(0.0),
                activeDateCount = weekdayActive.size,
                observedDateCount = weekdayObservations.size,
            )
        }

        return PersonalPaceProfile(
            confidence = if (
                dates.size >= RELIABLE_COVERED_DATES &&
                activeObservations.size >= RELIABLE_ACTIVE_DATES
            ) {
                PersonalPaceConfidence.RELIABLE
            } else {
                PersonalPaceConfidence.LEARNING
            },
            coveredDates = dates.size,
            activeDates = activeObservations.size,
            activeDayProbability = activeProbability,
            typicalActiveDayMinutes = globalTypical,
            weekdayHabits = weekdayHabits,
            dailyTotals = dates,
        )
    }

    private data class WeightedObservation(
        val date: LocalDate,
        val minutes: Int,
        val weight: Double,
    )

    private fun recencyWeight(date: LocalDate, lastDate: LocalDate): Double {
        val age = (lastDate.toEpochDay() - date.toEpochDay()).coerceAtLeast(0)
        return 2.0.pow(-age.toDouble() / HALF_LIFE_DAYS)
    }

    /** Use a weighted percentile and cap the upper tail before taking the weighted median. */
    private fun robustTypicalMinutes(
        observations: List<WeightedObservation>,
        upperBound: Double,
    ): Double {
        if (observations.isEmpty()) return 0.0
        val bounded = observations.map { it.copy(minutes = it.minutes.toDouble().coerceAtMost(upperBound).toInt()) }
        return weightedMedian(bounded)
    }

    private fun weightedPercentile(
        observations: List<WeightedObservation>,
        percentile: Double,
    ): Double {
        if (observations.isEmpty()) return 0.0
        val sorted = observations.sortedBy { it.minutes }
        val target = observations.sumOf { it.weight } * percentile.coerceIn(0.0, 1.0)
        var cumulative = 0.0
        for (observation in sorted) {
            cumulative += observation.weight
            if (cumulative >= target) return observation.minutes.toDouble()
        }
        return sorted.last().minutes.toDouble()
    }

    private fun weightedMedian(observations: List<WeightedObservation>): Double =
        weightedPercentile(observations, 0.5)
}
