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
    @Test fun recordsUseOnlyDatedPositiveEvidenceWithStableTiesAndContext() {
        val days = listOf(
            DatedCreditedTotal(date, 60, mapOf(2L to 30L, 1L to 30L)),
            DatedCreditedTotal(date.minusDays(1), 60, mapOf(1L to 60L)),
            DatedCreditedTotal(date.plusDays(1), 10, mapOf(1L to 10L, 3L to 0L)),
        )
        val earlier = date.minusDays(32)
        val sessions = listOf(PlaySession(2, 2, 20, 60, false), PlaySession(1, 1, 10, 60, false), PlaySession(3, 1, 1, 999, true))
        val unlock = com.example.backlogium.data.repo.DatedAchievementEvidence(1, "a", "A", 10, 1.0)
        val records = deriveRecords(days, sessions,
            mapOf(1L to com.example.backlogium.data.repo.RecordedActivityBounds(earlier, earlier)),
            listOf(unlock.copy(apiName = "b"), unlock, unlock.copy(rarityPercent = null)))
        assertEquals(1L, records.longestSession!!.id)
        assertEquals(AnalyticsDayRecord(date.minusDays(1), 60), records.busiestDay)
        assertEquals(AnalyticsDayRecord(date, 2), records.mostVariedDay)
        assertEquals(3L, records.gameRuns.first { it.appId == 1L }.days)
        assertEquals(30L, records.longestReturn!!.quietDates)
        assertEquals(earlier, records.longestReturn!!.previous)
        assertEquals(unlock, records.rarestUnlock)
        assertEquals(AnalyticsRecords(), deriveRecords(emptyList(), emptyList(), emptyMap(), emptyList()))
        assertNull(deriveRecords(listOf(DatedCreditedTotal(date, 1, mapOf(1L to 1L))), emptyList(),
            mapOf(1L to com.example.backlogium.data.repo.RecordedActivityBounds(date.minusDays(30), date.minusDays(30))), emptyList()).longestReturn)
    }

    @Test fun achievementMomentumUsesBoundedDatesAndRequiresThreeUnlockDatesOnEachSide() {
        val bounds = AnalyticsWindowBounds(date.minusDays(2), date)
        val prior = AnalyticsWindowBounds(date.minusDays(5), date.minusDays(3))
        val comparison = AnalyticsComparison(bounds, prior, AnalyticsPeriodFacts(0, 0, 0), AnalyticsPeriodFacts(0, 0, 0))
        val unlocks = (0L..5L).map {
            com.example.backlogium.data.repo.DatedAchievementEvidence(1, "$it", "Unlock", date.minusDays(it).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), if (it == 0L) null else 1.0)
        }
        val evidence = com.example.backlogium.data.repo.AchievementEvidenceSnapshot(unlocks, unsupportedUnlocks = 2, unsupportedCompletions = 3)
        val activity = deriveAchievementActivity(evidence, bounds, comparison, ZoneId.of("UTC"))
        assertEquals(3, activity.unlocks.size)
        assertEquals(3, activity.previousCount)
        assertEquals(1, activity.missingRarity)
        assertEquals(2, activity.rarity.values.sum())
        assertEquals(2, activity.unsupportedUnlocks)
        assertEquals(3, activity.unsupportedCompletions)
        assertNull(deriveAchievementActivity(evidence.copy(unlocks = unlocks.dropLast(1)), bounds, comparison, ZoneId.of("UTC")).previousCount)
        assertNull(deriveAchievementActivity(evidence, bounds, null, ZoneId.of("UTC")).previousCount)
        assertTrue(deriveAchievementActivity(evidence, null, null, ZoneId.of("UTC")).unlocks.isEmpty())
    }

}
