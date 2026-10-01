package com.example.backlogium.domain

import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class CloudPresencePreDataAllocation(
    val sessions: List<Session>,
    val transferredMinutesByAppId: Map<Long, Int>,
    val remainingImportedMinutesByAppId: Map<Long, Int>,
)

/**
 * Converts only safe, whole-minute presence slots into dates for already-imported owned-game play.
 * Steam's frozen per-game imported balance authorizes quantity; cloud evidence supplies timing only.
 */
object CloudPresencePreDataAllocationRule {
    const val MINUTE_MILLIS = 60_000L

    fun allocate(
        intervals: List<CloudPresenceInterval>,
        ownedAppIds: Set<Long>,
        importedMinutesByAppId: Map<Long, Int>,
        /** Ledger sessions not being replaced by this operation. */
        existingSessions: List<Session>,
        /** Replacement rows from existing-session re-filing, reserved before transfer allocation. */
        refiledSessions: List<Session>,
        selection: CloudPresenceHistoricalSelection,
        cutoffAt: Long,
        gapToleranceMillis: Long = CloudPresencePlaytimePlacement.DEFAULT_GAP_TOLERANCE_MILLIS,
    ): CloudPresencePreDataAllocation {
        require(gapToleranceMillis >= 0L) { "gapToleranceMillis must not be negative" }
        require(cutoffAt > selection.effectiveStartAt && cutoffAt <= selection.throughAt) {
            "cutoffAt must be after the selected start and no later than its fixed end"
        }
        val zone = ZoneId.of(selection.zoneId)

        val remaining = importedMinutesByAppId.mapValues { (_, minutes) -> minutes.coerceAtLeast(0) }
            .toMutableMap()
        val eligibleBalances = remaining.filter { (appId, minutes) ->
            appId in ownedAppIds && minutes > 0
        }
        if (eligibleBalances.isEmpty()) {
            return CloudPresencePreDataAllocation(
                sessions = emptyList(),
                transferredMinutesByAppId = emptyMap(),
                remainingImportedMinutesByAppId = remaining,
            )
        }

        val occupied = existingSessions + refiledSessions
        val candidatesByStartAt = mutableMapOf<Long, MutableSet<Long>>()
        intervals.asSequence()
            .filter { it.appId in eligibleBalances }
            .flatMap { interval ->
                interval.confirmedSpans(
                    rangeStartAt = selection.effectiveStartAt,
                    cutoffAt = cutoffAt,
                    throughAt = selection.throughAt,
                    gapToleranceMillis = gapToleranceMillis,
                ).asSequence().flatMap { span ->
                    fullMinuteSlots(interval.appId, span, zone).asSequence()
                }
            }
            .filterNot { slot -> occupied.any { it.overlaps(slot) } }
            .forEach { slot ->
                candidatesByStartAt.getOrPut(slot.startAt) { mutableSetOf() }.add(slot.appId)
            }

        val selectedSlots = mutableListOf<MinuteSlot>()
        val orderedStarts = candidatesByStartAt.keys.sortedDescending()
        for (startAt in orderedStarts) {
            val appId = candidatesByStartAt.getValue(startAt)
                .asSequence()
                .filter { (remaining[it] ?: 0) > 0 }
                .minOrNull()
                ?: continue
            val slot = MinuteSlot(appId, startAt, startAt + MINUTE_MILLIS)
            selectedSlots += slot
            remaining[appId] = checkNotNull(remaining[appId]) - 1
        }

        val transferred = selectedSlots.groupingBy(MinuteSlot::appId)
            .eachCount()
        val sessions = groupIntoSessions(selectedSlots, zone)
        return CloudPresencePreDataAllocation(
            sessions = sessions,
            transferredMinutesByAppId = transferred,
            remainingImportedMinutesByAppId = remaining,
        )
    }

    private fun CloudPresenceInterval.confirmedSpans(
        rangeStartAt: Long,
        cutoffAt: Long,
        throughAt: Long,
        gapToleranceMillis: Long,
    ): List<TimeSpan> {
        val rawEndAt = endAt ?: return emptyList()
        if (rawEndAt <= startAt) return emptyList()
        val observedEndAt = when (coverage) {
            CloudCoverageState.CONTINUOUS -> rawEndAt
            CloudCoverageState.OBSERVED_UNTIL -> observedUntil ?: return emptyList()
            CloudCoverageState.UNKNOWN -> return emptyList()
            CloudCoverageState.LEGACY_TRANSITIONS -> {
                if (ongoing || observedUntil != null ||
                    coverageLapseFrom != null || coverageLapseRecoveredAt != null
                ) return emptyList()
                rawEndAt
            }
        }.coerceAtMost(rawEndAt)
        val start = maxOf(startAt, rangeStartAt)
        val end = minOf(observedEndAt, cutoffAt, throughAt)
        if (end <= start) return emptyList()

        val lapseFrom = coverageLapseFrom
        val lapseRecoveredAt = coverageLapseRecoveredAt
        if (lapseFrom == null && lapseRecoveredAt == null) return listOf(TimeSpan(start, end))
        if (lapseFrom == null || lapseRecoveredAt == null || lapseRecoveredAt < lapseFrom) {
            return emptyList()
        }
        val lapseLength = lapseRecoveredAt - lapseFrom
        if (lapseLength < 0L || lapseLength > gapToleranceMillis) return emptyList()

        return buildList {
            val beforeEnd = minOf(end, lapseFrom)
            if (beforeEnd > start) add(TimeSpan(start, beforeEnd))
            val afterStart = maxOf(start, lapseRecoveredAt)
            if (end > afterStart) add(TimeSpan(afterStart, end))
        }
    }

    private fun fullMinuteSlots(appId: Long, span: TimeSpan, zone: ZoneId): List<MinuteSlot> {
        if (span.endAt <= span.startAt) return emptyList()
        val slots = mutableListOf<MinuteSlot>()
        var date = Instant.ofEpochMilli(span.startAt).atZone(zone).toLocalDate()
        while (true) {
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilliOrNull() ?: break
            val nextDate = runCatching { date.plusDays(1) }.getOrNull() ?: break
            val dayEnd = nextDate.atStartOfDay(zone).toInstant().toEpochMilliOrNull() ?: break
            val clippedStart = maxOf(span.startAt, dayStart)
            val clippedEnd = minOf(span.endAt, dayEnd)
            if (clippedEnd > clippedStart) {
                val offsetFromDayStart = clippedStart - dayStart
                val firstWholeMinuteOffset =
                    ((offsetFromDayStart + MINUTE_MILLIS - 1L) / MINUTE_MILLIS) * MINUTE_MILLIS
                var slotStart = runCatching {
                    Math.addExact(dayStart, firstWholeMinuteOffset)
                }.getOrNull()
                while (slotStart != null) {
                    val slotEnd = runCatching {
                        Math.addExact(slotStart, MINUTE_MILLIS)
                    }.getOrNull() ?: break
                    if (slotEnd > clippedEnd) break
                    slots += MinuteSlot(
                        appId = appId,
                        startAt = slotStart,
                        endAt = slotEnd,
                    )
                    slotStart = slotEnd
                }
            }
            if (dayEnd >= span.endAt) break
            date = nextDate
        }
        return slots
    }

    private fun groupIntoSessions(slots: List<MinuteSlot>, zone: ZoneId): List<Session> {
        val ordered = slots.sortedWith(compareBy<MinuteSlot> { it.appId }.thenBy { it.startAt })
        val grouped = mutableListOf<SlotSession>()
        ordered.forEach { slot ->
            val date = Instant.ofEpochMilli(slot.startAt).atZone(zone).toLocalDate()
            val previous = grouped.lastOrNull()
            if (previous != null && previous.appId == slot.appId && previous.date == date &&
                previous.endAt == slot.startAt
            ) {
                previous.endAt = slot.endAt
                previous.minutes++
            } else {
                grouped += SlotSession(slot.appId, date, slot.startAt, slot.endAt, minutes = 1)
            }
        }
        return grouped.map { group ->
            Session(
                appId = group.appId,
                startAt = group.startAt,
                endAt = group.endAt,
                minutes = group.minutes,
                open = false,
                recoveredSharedPlay = RecoveredSharedPlayState.NONE,
                timingInformedSteamPlay = TimingInformedSteamPlayState.FULL,
            )
        }
    }

    private fun Session.overlaps(slot: MinuteSlot): Boolean {
        val occupiedEnd = endAt ?: Long.MAX_VALUE
        return startAt < slot.endAt && occupiedEnd > slot.startAt
    }

    private fun Instant.toEpochMilliOrNull(): Long? = runCatching { toEpochMilli() }.getOrNull()

    private data class TimeSpan(val startAt: Long, val endAt: Long)

    private data class MinuteSlot(val appId: Long, val startAt: Long, val endAt: Long)

    private data class SlotSession(
        val appId: Long,
        val date: LocalDate,
        val startAt: Long,
        var endAt: Long,
        var minutes: Int,
    )
}
