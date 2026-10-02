package com.example.backlogium.domain

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class PersonalMomentumTest {
    private val key = MomentumKey("fixture", LocalDate.of(2026, 10, 3), ZoneId.of("UTC"))
    private val weeks = completedWeeks(key.today)
    private val game = MomentumGame(1, "Fixture", "")
    private fun days(id: Long, c: Long, p: Long, twoDates: Boolean = true) = listOf(
        MomentumDay(id, weeks.baselineStart, p), MomentumDay(id, weeks.currentStart, c / 2),
        MomentumDay(id, if (twoDates) weeks.currentEnd else weeks.currentStart, c - c / 2))
    private fun result(c: Long, p: Long, twoDates: Boolean = true) =
        personalMomentum(key, listOf(game), days(1, c, p, twoDates), weeks.baselineStart)

    @Test fun octoberBoundsExcludeTodayAndUseSevenLocalDates() {
        assertEquals(LocalDate.of(2026, 9, 19), weeks.baselineStart)
        assertEquals(LocalDate.of(2026, 9, 25), weeks.baselineEnd)
        assertEquals(LocalDate.of(2026, 9, 26), weeks.currentStart)
        assertEquals(LocalDate.of(2026, 10, 2), weeks.currentEnd)
    }
    @Test fun exactThresholdsUseUnroundedRatioAndDistinctActiveDates() {
        val qualifying = listOf(60L to 30L, 150L to 120L, 120L to 80L)
        qualifying.forEach { (c, p) -> assertEquals(MomentumKind.GROWTH, result(c, p).candidates.single().kind) }
        for ((c, p) in listOf(59L to 30L, 151L to 121L, 230L to 200L, 60L to 29L, 60L to 5L, 149L to 120L)) {
            assertEquals("C=$c P=$p", MomentumAvailability.NO_INCREASE, result(c, p).availability)
        }
        assertEquals(MomentumAvailability.NO_INCREASE, result(60, 30, false).availability)
        assertEquals(MomentumKind.NEWLY_RECORDED, result(60, 0).candidates.single().kind)
    }
    @Test fun unknownOrLateHistoryWithholdsRankingAndEmptyLibraryIsDistinct() {
        for (earliest in listOf(null, weeks.baselineStart.plusDays(1))) {
            assertEquals(MomentumAvailability.LEARNING,
                personalMomentum(key, listOf(game), days(1, 120, 80), earliest).availability)
        }
        assertEquals(MomentumAvailability.EMPTY_LIBRARY, personalMomentum(key, emptyList(), emptyList(), null).availability)
        assertEquals(MomentumAvailability.NO_INCREASE,
            personalMomentum(key, listOf(game), days(99, 200, 0), weeks.baselineStart).availability)
    }
    @Test fun rankingTiesAndFiveTitleLimitKeepNewActivityAfterGrowth() {
        val amounts = listOf(6L to (90L to 30L), 5L to (140L to 100L), 2L to (120L to 80L),
            1L to (120L to 80L), 10L to (130L to 0L), 9L to (100L to 0L), 11L to (90L to 0L))
        for (input in listOf(amounts, amounts.reversed())) {
            val games = input.map { MomentumGame(it.first, "Same name", "") }
            val rows = input.flatMap { (id, amount) -> days(id, amount.first, amount.second) }
            assertEquals(listOf(6L, 5L, 1L, 2L, 10L), personalMomentum(key, games, rows, weeks.baselineStart)
                .candidates.map { it.game.appId })
        }
    }
    @Test fun wideAmountsDoNotWrapOrDetermineEligibilityFromRoundedPercentages() {
        val row = result(4_000_000_000, 3_000_000_000).candidates.single()
        assertEquals(1_000_000_000L, row.additionalMinutes)
        assertEquals(4_000_000_000L, row.currentMinutes)
    }
    @Test fun wholeOvernightRecordsOpenExclusionAndExactEdgesSurviveDst() {
        val zone = ZoneId.of("America/New_York")
        val dstWeeks = completedWeeks(LocalDate.of(2026, 3, 15))
        assertEquals(14 * 24 * 3_600_000L - 3_600_000L, dstWeeks.endExclusiveMillis(zone) - dstWeeks.startMillis(zone))
        val overnight = dstWeeks.baselineEnd.atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val records = listOf(
            MomentumRecord(1, dstWeeks.startMillis(zone) - 1, 90, false),
            MomentumRecord(1, dstWeeks.startMillis(zone), 10, false),
            MomentumRecord(1, overnight, 80, false),
            MomentumRecord(1, dstWeeks.currentStart.atStartOfDay(zone).toInstant().toEpochMilli(), 90, true),
            MomentumRecord(1, dstWeeks.endExclusiveMillis(zone) - 1, 20, false),
            MomentumRecord(1, dstWeeks.endExclusiveMillis(zone), 90, false))
        val projected = momentumDays(records, dstWeeks, zone)
        assertEquals(110L, projected.sumOf { it.minutes })
        assertEquals(80L, projected.single { it.date == dstWeeks.baselineEnd }.minutes)
    }
    @Test fun zoneAndMidnightReattributeBoundsWithoutSplittingRecords() {
        val record = MomentumRecord(1, key.today.minusDays(1).atTime(23, 30).atZone(key.zone).toInstant().toEpochMilli(), 80, false)
        assertEquals(80L, momentumDays(listOf(record), weeks, key.zone).single().minutes)
        assertTrue(momentumDays(listOf(record), weeks, ZoneId.of("Asia/Taipei")).isEmpty())
        assertEquals(key.today, completedWeeks(key.today.plusDays(1)).currentEnd)
    }
}
