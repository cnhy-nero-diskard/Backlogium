package com.example.backlogium.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

enum class CloudPresenceHistoricalStartChoice {
    RECENT_31_DAYS,
    CUSTOM_LOCAL_DATE,
}

data class CloudPresenceHistoricalSelection(
    val choice: CloudPresenceHistoricalStartChoice,
    /** The rolling instant for the preset, or the selected date's local-midnight instant. */
    val selectedStartAt: Long,
    /** The actual acquisition boundary after clipping a custom date to retained evidence. */
    val effectiveStartAt: Long,
    /** Server `readAt`, frozen when the selection is confirmed. */
    val throughAt: Long,
    val zoneId: String,
)

sealed interface CloudPresenceHistoricalRangeResolution {
    data class Ready(val selection: CloudPresenceHistoricalSelection) : CloudPresenceHistoricalRangeResolution
    data object NoAvailableEvidence : CloudPresenceHistoricalRangeResolution
    data object CustomDateNotInPast : CloudPresenceHistoricalRangeResolution
    data object BeforeEarliestEvidence : CloudPresenceHistoricalRangeResolution
    data object InvalidLocalMidnight : CloudPresenceHistoricalRangeResolution
    data object OutsideFixedRange : CloudPresenceHistoricalRangeResolution
}

sealed interface CloudPresencePreDataCutoffResolution {
    data class Confirmed(val cutoffAt: Long) : CloudPresencePreDataCutoffResolution
    data object NotConfirmed : CloudPresencePreDataCutoffResolution
    data object InvalidLocalDateTime : CloudPresencePreDataCutoffResolution
    data object OutsideSelectedRange : CloudPresencePreDataCutoffResolution
}

/** Pure range/cutoff rules shared by Settings and repository tests. */
object CloudPresenceHistoricalRangeRules {
    private const val RECENT_WINDOW_MILLIS = 31L * 24L * 60L * 60L * 1000L

    /** Preserve the existing rolling first-read boundary; it is not rounded to local midnight. */
    fun recent31Days(throughAt: Long, zone: ZoneId): CloudPresenceHistoricalSelection {
        val startAt = throughAt - RECENT_WINDOW_MILLIS
        return CloudPresenceHistoricalSelection(
            choice = CloudPresenceHistoricalStartChoice.RECENT_31_DAYS,
            selectedStartAt = startAt,
            effectiveStartAt = startAt,
            throughAt = throughAt,
            zoneId = zone.id,
        )
    }

    fun customLocalDate(
        selectedDate: LocalDate,
        nowAt: Long,
        throughAt: Long,
        zone: ZoneId,
        earliestObservedAt: Long?,
    ): CloudPresenceHistoricalRangeResolution {
        val earliest = earliestObservedAt
            ?: return CloudPresenceHistoricalRangeResolution.NoAvailableEvidence
        val today = Instant.ofEpochMilli(nowAt).atZone(zone).toLocalDate()
        if (!selectedDate.isBefore(today)) {
            return CloudPresenceHistoricalRangeResolution.CustomDateNotInPast
        }
        val earliestDate = Instant.ofEpochMilli(earliest).atZone(zone).toLocalDate()
        if (selectedDate.isBefore(earliestDate)) {
            return CloudPresenceHistoricalRangeResolution.BeforeEarliestEvidence
        }
        val selectedStartAt = selectedDate.atStartOfDay().toUniqueInstant(zone)?.toEpochMilliOrNull()
            ?: return CloudPresenceHistoricalRangeResolution.InvalidLocalMidnight
        val effectiveStartAt = maxOf(selectedStartAt, earliest)
        if (effectiveStartAt > throughAt) {
            return CloudPresenceHistoricalRangeResolution.OutsideFixedRange
        }
        return CloudPresenceHistoricalRangeResolution.Ready(
            CloudPresenceHistoricalSelection(
                choice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE,
                selectedStartAt = selectedStartAt,
                effectiveStartAt = effectiveStartAt,
                throughAt = throughAt,
                zoneId = zone.id,
            ),
        )
    }

    /** A missing choice stays missing; this rule never substitutes a first-session timestamp. */
    fun confirmedPreDataCutoff(
        localDateTime: LocalDateTime?,
        zone: ZoneId,
        selection: CloudPresenceHistoricalSelection,
    ): CloudPresencePreDataCutoffResolution {
        localDateTime ?: return CloudPresencePreDataCutoffResolution.NotConfirmed
        val cutoffAt = localDateTime.toUniqueInstant(zone)?.toEpochMilliOrNull()
            ?: return CloudPresencePreDataCutoffResolution.InvalidLocalDateTime
        if (cutoffAt <= selection.effectiveStartAt || cutoffAt > selection.throughAt) {
            return CloudPresencePreDataCutoffResolution.OutsideSelectedRange
        }
        return CloudPresencePreDataCutoffResolution.Confirmed(cutoffAt)
    }

    /** DST gaps and overlaps are rejected instead of silently shifting/choosing an offset. */
    private fun LocalDateTime.toUniqueInstant(zone: ZoneId): Instant? {
        val offsets = zone.rules.getValidOffsets(this)
        if (offsets.size != 1) return null
        val offset: ZoneOffset = offsets.single()
        return toInstant(offset)
    }

    private fun Instant.toEpochMilliOrNull(): Long? =
        runCatching { toEpochMilli() }.getOrNull()
}
