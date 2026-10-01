package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresenceHistoricalRangeRulesTest {
    @Test
    fun recent31DaysPreservesTheRollingInstantInsteadOfRoundingToMidnight() {
        val throughAt = instant("2025-03-10T06:30:00.123Z")
        val expectedStartAt = throughAt - THIRTY_ONE_DAYS_MILLIS

        val selection = CloudPresenceHistoricalRangeRules.recent31Days(
            throughAt = throughAt,
            zone = ZoneId.of("America/Los_Angeles"),
        )

        assertEquals(CloudPresenceHistoricalStartChoice.RECENT_31_DAYS, selection.choice)
        assertEquals(expectedStartAt, selection.selectedStartAt)
        assertEquals(expectedStartAt, selection.effectiveStartAt)
        assertEquals(throughAt, selection.throughAt)
    }

    @Test
    fun customDateStartsAtMidnightInTheSelectedZone() {
        val zone = ZoneId.of("America/Los_Angeles")
        val date = LocalDate.parse("2025-06-01")
        val nowAt = instant("2025-06-02T18:00:00Z")
        val throughAt = instant("2025-06-02T17:00:00Z")
        val expectedStartAt = date.atStartOfDay(zone).toInstant().toEpochMilli()

        val result = CloudPresenceHistoricalRangeRules.customLocalDate(
            selectedDate = date,
            nowAt = nowAt,
            throughAt = throughAt,
            zone = zone,
            earliestObservedAt = instant("2025-05-20T12:00:00Z"),
        )

        assertTrue(result is CloudPresenceHistoricalRangeResolution.Ready)
        val selection = (result as CloudPresenceHistoricalRangeResolution.Ready).selection
        assertEquals(expectedStartAt, selection.selectedStartAt)
        assertEquals(expectedStartAt, selection.effectiveStartAt)
        assertEquals(zone.id, selection.zoneId)
    }

    @Test
    fun customDateOnEarliestEvidenceDayClipsToTheRetainedInstant() {
        val zone = ZoneId.of("America/Los_Angeles")
        val selectedDate = LocalDate.parse("2025-04-10")
        val earliest = instant("2025-04-10T16:30:00Z") // 09:30 local time.

        val result = CloudPresenceHistoricalRangeRules.customLocalDate(
            selectedDate = selectedDate,
            nowAt = instant("2025-04-20T12:00:00Z"),
            throughAt = instant("2025-04-20T12:00:00Z"),
            zone = zone,
            earliestObservedAt = earliest,
        )

        assertTrue(result is CloudPresenceHistoricalRangeResolution.Ready)
        val selection = (result as CloudPresenceHistoricalRangeResolution.Ready).selection
        assertEquals(
            selectedDate.atStartOfDay(zone).toInstant().toEpochMilli(),
            selection.selectedStartAt,
        )
        assertEquals(earliest, selection.effectiveStartAt)
    }

    @Test
    fun customDateRejectsMissingEvidenceTodayFutureAndBeforeEarliestDay() {
        val zone = ZoneId.of("UTC")
        val nowAt = instant("2025-04-20T12:00:00Z")
        val throughAt = instant("2025-04-20T12:00:00Z")
        fun resolve(date: String, earliest: Long? = instant("2025-04-10T00:00:00Z")) =
            CloudPresenceHistoricalRangeRules.customLocalDate(
                selectedDate = LocalDate.parse(date),
                nowAt = nowAt,
                throughAt = throughAt,
                zone = zone,
                earliestObservedAt = earliest,
            )

        assertEquals(
            CloudPresenceHistoricalRangeResolution.NoAvailableEvidence,
            resolve("2025-04-09", earliest = null),
        )
        assertEquals(
            CloudPresenceHistoricalRangeResolution.CustomDateNotInPast,
            resolve("2025-04-20"),
        )
        assertEquals(
            CloudPresenceHistoricalRangeResolution.CustomDateNotInPast,
            resolve("2025-04-21"),
        )
        assertEquals(
            CloudPresenceHistoricalRangeResolution.BeforeEarliestEvidence,
            resolve("2025-04-09"),
        )
    }

    @Test
    fun customDateCannotStartAfterTheFrozenEnd() {
        val zone = ZoneId.of("UTC")

        val result = CloudPresenceHistoricalRangeRules.customLocalDate(
            selectedDate = LocalDate.parse("2025-04-19"),
            nowAt = instant("2025-04-20T12:00:00Z"),
            throughAt = instant("2025-04-18T12:00:00Z"),
            zone = zone,
            earliestObservedAt = instant("2025-04-01T00:00:00Z"),
        )

        assertEquals(CloudPresenceHistoricalRangeResolution.OutsideFixedRange, result)
    }

    @Test
    fun customMidnightRejectsDaylightSavingGapsAndOverlaps() {
        val gapZone = ZoneId.of("America/Sao_Paulo")
        val gapDate = LocalDate.parse("2018-11-04")
        val gapMidnight = gapDate.atStartOfDay()
        assertTrue(gapZone.rules.getValidOffsets(gapMidnight).isEmpty())

        val overlapZone = ZoneId.of("America/Havana")
        val overlapDate = LocalDate.parse("2020-11-01")
        val overlapMidnight = overlapDate.atStartOfDay()
        assertEquals(2, overlapZone.rules.getValidOffsets(overlapMidnight).size)

        assertEquals(
            CloudPresenceHistoricalRangeResolution.InvalidLocalMidnight,
            resolveCustomDate(gapDate, gapZone, instant("2018-11-04T03:30:00Z"), instant("2018-11-05T12:00:00Z")),
        )
        assertEquals(
            CloudPresenceHistoricalRangeResolution.InvalidLocalMidnight,
            resolveCustomDate(
                overlapDate,
                overlapZone,
                instant("2020-11-01T05:30:00Z"),
                instant("2020-11-02T12:00:00Z"),
            ),
        )
    }

    @Test
    fun preDataCutoffRejectsDaylightSavingGapsAndOverlaps() {
        val zone = ZoneId.of("America/New_York")
        val selection = selection(
            fromAt = instant("2024-03-01T00:00:00Z"),
            throughAt = instant("2024-12-01T00:00:00Z"),
        )
        val gap = LocalDateTime.parse("2024-03-10T02:30:00")
        val overlap = LocalDateTime.parse("2024-11-03T01:30:00")
        assertTrue(zone.rules.getValidOffsets(gap).isEmpty())
        assertEquals(2, zone.rules.getValidOffsets(overlap).size)

        assertEquals(
            CloudPresencePreDataCutoffResolution.InvalidLocalDateTime,
            CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(gap, zone, selection),
        )
        assertEquals(
            CloudPresencePreDataCutoffResolution.InvalidLocalDateTime,
            CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(overlap, zone, selection),
        )
    }

    @Test
    fun preDataCutoffRequiresExplicitConfirmationAndNeverInfersFromSessionHistory() {
        val zone = ZoneId.of("UTC")
        val selection = selection(
            fromAt = instant("2025-01-01T00:00:00Z"),
            throughAt = instant("2025-02-01T00:00:00Z"),
        )

        assertEquals(
            CloudPresencePreDataCutoffResolution.NotConfirmed,
            CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(null, zone, selection),
        )
    }

    @Test
    fun preDataCutoffMustBeAfterStartAndNoLaterThanTheFixedEnd() {
        val zone = ZoneId.of("UTC")
        val fromAt = instant("2025-01-01T00:00:00Z")
        val throughAt = instant("2025-02-01T00:00:00Z")
        val selection = selection(fromAt, throughAt)

        assertEquals(
            CloudPresencePreDataCutoffResolution.OutsideSelectedRange,
            CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(
                LocalDateTime.parse("2025-01-01T00:00:00"), zone, selection,
            ),
        )
        assertEquals(
            CloudPresencePreDataCutoffResolution.OutsideSelectedRange,
            CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(
                LocalDateTime.parse("2025-02-01T00:00:00.001"), zone, selection,
            ),
        )
        assertEquals(
            CloudPresencePreDataCutoffResolution.Confirmed(throughAt),
            CloudPresenceHistoricalRangeRules.confirmedPreDataCutoff(
                LocalDateTime.parse("2025-02-01T00:00:00"), zone, selection,
            ),
        )
    }

    private fun resolveCustomDate(
        date: LocalDate,
        zone: ZoneId,
        earliestAt: Long,
        throughAt: Long,
    ): CloudPresenceHistoricalRangeResolution = CloudPresenceHistoricalRangeRules.customLocalDate(
        selectedDate = date,
        nowAt = throughAt,
        throughAt = throughAt,
        zone = zone,
        earliestObservedAt = earliestAt,
    )

    private fun selection(fromAt: Long, throughAt: Long) = CloudPresenceHistoricalSelection(
        choice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
        selectedStartAt = fromAt,
        effectiveStartAt = fromAt,
        throughAt = throughAt,
        zoneId = "UTC",
    )

    private fun instant(value: String): Long = Instant.parse(value).toEpochMilli()

    private companion object {
        const val THIRTY_ONE_DAYS_MILLIS = 31L * 24L * 60L * 60L * 1000L
    }
}
