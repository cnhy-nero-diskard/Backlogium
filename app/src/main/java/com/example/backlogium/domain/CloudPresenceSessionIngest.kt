package com.example.backlogium.domain

/**
 * Expands reconstructed cloud intervals into observations for [PresenceSessionDeriver].
 *
 * Cloud presence is a second observer of the existing presence mechanism, not a second session
 * writer or detector.
 */
object CloudPresenceSessionIngest {
    /** One stored session's covered span, for overlap-aware dedup. */
    data class StoredSessionSpan(val startAt: Long, val endAt: Long)

    /** Expand cloud intervals into observations for the existing presence deriver. */
    fun observations(
        intervals: List<CloudPresenceInterval>,
        gameSources: Map<Long, GameSource>,
        gapToleranceMillis: Long = DEFAULT_GAP_TOLERANCE_MILLIS,
        storedSessions: Map<Long, List<StoredSessionSpan>> = emptyMap(),
    ): List<PresenceSessionDeriver.Observation> {
        require(gapToleranceMillis >= 0L)
        val ordered = intervals.sortedWith(
            compareBy<CloudPresenceInterval> { it.startAt }
                .thenBy { it.endAt ?: Long.MAX_VALUE }
                .thenBy { it.appId },
        )
        if (ordered.isEmpty()) return emptyList()

        val output = mutableListOf<PresenceSessionDeriver.Observation>()
        ordered.forEach { interval ->
            val admitted = gameSources[interval.appId] == GameSource.FAMILY_SHARED
            if (!admitted) {
                output += PresenceSessionDeriver.Observation(appId = null, at = interval.startAt)
                return@forEach
            }
            val confirmedEnd = interval.confirmedEnd(gapToleranceMillis)
                ?.takeIf { it >= interval.startAt }
            if (confirmedEnd == null) {
                output += PresenceSessionDeriver.Observation(appId = null, at = interval.startAt)
                return@forEach
            }

            val coverages = storedSessions[interval.appId].orEmpty()
            val uncovered = uncoveredSegments(interval.startAt, confirmedEnd, coverages)
            uncovered.forEachIndexed { index, (startAt, endAt) ->
                if (index > 0 && uncovered[index - 1].second < startAt) {
                    // A stored span removed between these fragments must break the derived
                    // session: without a null boundary the deriver bridges the covered hole
                    // whenever it is within its gap tolerance and re-credits stored time.
                    output += PresenceSessionDeriver.Observation(
                        appId = null,
                        at = uncovered[index - 1].second,
                    )
                }
                output.addContinuousObservations(
                    appId = interval.appId,
                    startAt = startAt,
                    endAt = endAt,
                    gapToleranceMillis = gapToleranceMillis,
                )
            }
        }

        val last = ordered.last()
        if (!last.ongoing) {
            val closeAt = last.confirmedEnd(gapToleranceMillis)
                ?.takeIf { it >= last.startAt }
                ?: last.startAt
            val maxStoredEnd = storedSessions[last.appId]?.maxOfOrNull { it.endAt }
            val hasUncoveredGameObservation = output.any { it.appId != null }
            if (hasUncoveredGameObservation || maxStoredEnd == null || closeAt >= maxStoredEnd) {
                output += PresenceSessionDeriver.Observation(appId = null, at = closeAt)
            }
        }

        return output.distinct()
    }

    /**
     * Trim only the portions actually covered by stored sessions, keeping older cloud-only gaps
     * even when a newer local session exists. A high-water `max(endAt)` per game would discard a
     * missed interval whose end is before that newer session despite no stored session overlapping
     * it, defeating recovery on the first bounded-history read.
     */
    private fun uncoveredSegments(
        startAt: Long,
        endAt: Long,
        coverages: List<StoredSessionSpan>,
    ): List<Pair<Long, Long>> {
        if (coverages.isEmpty()) return listOf(startAt to endAt)
        val sorted = coverages.sortedBy { it.startAt }
        val result = mutableListOf<Pair<Long, Long>>()
        var cursor = startAt
        var overlapped = false
        for (coverage in sorted) {
            if (coverage.endAt < cursor || coverage.startAt > endAt) continue
            overlapped = true
            if (coverage.startAt > cursor) {
                val gapEnd = minOf(coverage.startAt, endAt)
                if (cursor < gapEnd) result += cursor to gapEnd
            }
            if (coverage.endAt >= cursor) cursor = coverage.endAt
            if (cursor >= endAt) break
        }
        if (cursor < endAt) {
            result += cursor to endAt
        } else if (cursor == endAt && startAt == endAt && !overlapped) {
            result += startAt to endAt
        }
        return result
    }

    /** Keep a cloud-proven continuous span within the deriver's silence tolerance. */
    private fun MutableList<PresenceSessionDeriver.Observation>.addContinuousObservations(
        appId: Long,
        startAt: Long,
        endAt: Long,
        gapToleranceMillis: Long,
    ) {
        add(PresenceSessionDeriver.Observation(appId, startAt))
        var at = startAt
        while (gapToleranceMillis > 0L && endAt - at > gapToleranceMillis) {
            at += gapToleranceMillis
            add(PresenceSessionDeriver.Observation(appId, at))
        }
        if (endAt > at) add(PresenceSessionDeriver.Observation(appId, endAt))
    }

    private fun CloudPresenceInterval.confirmedEnd(gapToleranceMillis: Long): Long? {
        val lapseFrom = coverageLapseFrom
        val lapseRecoveredAt = coverageLapseRecoveredAt
        if ((lapseFrom == null) != (lapseRecoveredAt == null)) return null
        if (lapseFrom != null && lapseRecoveredAt != null) {
            val lapse = lapseRecoveredAt - lapseFrom
            if (lapse < 0L || lapse > gapToleranceMillis) return null
        }

        val end = endAt ?: return startAt.takeIf { ongoing }
        return when (coverage) {
            CloudCoverageState.CONTINUOUS -> end
            CloudCoverageState.OBSERVED_UNTIL -> observedUntil
                ?.coerceIn(startAt, end)
            CloudCoverageState.UNKNOWN -> null
        }
    }
    const val DEFAULT_GAP_TOLERANCE_MILLIS = PresenceSessionDeriver.DEFAULT_GAP_TOLERANCE_MILLIS
}
