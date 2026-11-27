package com.example.backlogium.ui.analytics

import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.domain.DatedCreditedTotal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AdvancedPlayInsightsTest {
    private val date = LocalDate.of(2026, 11, 27)
    private fun totals(vararg pairs: Pair<Long, Long>) = listOf(DatedCreditedTotal(date, pairs.sumOf { it.second }, pairs.toMap()))
    private val action = GameGenre("1", "Action")
    private val puzzle = GameGenre("2", "Puzzle")

    @Test fun genresDivideDistinctTagsWithoutRoundingOrLosingUnknownMinutes() {
        val mix = deriveGenreMix(totals(1L to 61L, 2L to 30L), mapOf(1L to listOf(action, puzzle, action)))
        assertEquals(91.0, mix.shares.sumOf { it.minutes }, 0.000001)
        assertEquals(100.0, mix.shares.sumOf { it.percent }, 0.000001)
        assertEquals(30.5, mix.shares.first { it.genre == action }.minutes, 0.0)
        assertEquals(30.0, mix.shares.first { it.genre == null }.minutes, 0.0)
        assertEquals(1, mix.coveredGames)
        assertFalse(mix.supportsDrift)
        assertTrue(deriveGenreMix(emptyList(), emptyMap()).shares.isEmpty())
    }

    @Test fun driftRequiresComparableBoundsThreeTitlesAndHalfKnownMinutesOnBothSides() {
        val genres = (1L..3L).associateWith { listOf(action) }
        val eligible = deriveGenreMix(totals(1L to 10L, 2L to 10L, 3L to 10L, 4L to 30L), genres)
        assertTrue(eligible.supportsDrift)
        val poor = deriveGenreMix(totals(1L to 10L, 2L to 10L, 3L to 10L, 4L to 31L), genres)
        assertFalse(poor.supportsDrift)
        assertTrue(deriveGenreDrift(eligible, poor, true).isEmpty())
        assertTrue(deriveGenreDrift(eligible, eligible, false).isEmpty())
        val previous = deriveGenreMix(totals(1L to 10L, 2L to 10L, 3L to 10L), (1L..3L).associateWith { listOf(puzzle) })
        val drift = deriveGenreDrift(eligible, previous, true)
        assertEquals(50.0, drift.first { it.genre == action }.percentagePoints, 0.0)
        assertEquals(-100.0, drift.first { it.genre == puzzle }.percentagePoints, 0.0)
        assertEquals(0.0, drift.sumOf { it.percentagePoints }, 0.0)
    }

    @Test fun matrixKeepsOvernightMinutesAtStartAndReprojectsLocalZone() {
        val session = PlaySession(1, 1, date.atTime(23, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli(), 120, false)
        val utc = deriveClockMatrix(listOf(session), ZoneId.of("UTC"))
        assertEquals(AnalyticsClockCell(DayOfWeek.FRIDAY, TimeOfDayBucket.NIGHT, 120), utc.cells.single())
        assertNull(utc.dominant)
        val local = deriveClockMatrix(listOf(session), ZoneId.of("Asia/Manila"))
        assertEquals(AnalyticsClockCell(DayOfWeek.SATURDAY, TimeOfDayBucket.MORNING, 120), local.cells.single())
        assertTrue(deriveClockMatrix(emptyList(), ZoneId.of("UTC")).cells.isEmpty())
        assertNull(deriveClockMatrix(listOf(session.copy(minutes = 0)), ZoneId.of("UTC")).dominant)
    }
}
