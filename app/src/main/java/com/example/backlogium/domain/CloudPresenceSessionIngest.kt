package com.example.backlogium.domain

/**
 * Expands reconstructed cloud intervals into observations for [PresenceSessionDeriver].
 *
 * Cloud presence is a second observer of the existing presence mechanism, not a second session
 * writer or detector.
 */
object CloudPresenceSessionIngest {
    /** Expand cloud intervals into observations for the existing presence deriver. */
    fun observations(
        intervals: List<CloudPresenceInterval>,
        gameSources: Map<Long, GameSource>,
        gapToleranceMillis: Long = DEFAULT_GAP_TOLERANCE_MILLIS,
        alreadyObservedThrough: Map<Long, Long> = emptyMap(),
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

            val previous = alreadyObservedThrough[interval.appId]
            if (previous == null) {
                output.addContinuousObservations(
                    appId = interval.appId,
                    startAt = interval.startAt,
                    endAt = confirmedEnd,
                    gapToleranceMillis = gapToleranceMillis,
                )
            } else if (confirmedEnd > previous) {
                val resumedAt = maxOf(interval.startAt, previous)
                output.addContinuousObservations(
                    appId = interval.appId,
                    startAt = resumedAt,
                    endAt = confirmedEnd,
                    gapToleranceMillis = gapToleranceMillis,
                )
            }
        }

        val last = ordered.last()
        if (!last.ongoing) {
            val closeAt = last.confirmedEnd(gapToleranceMillis)
                ?.takeIf { it >= last.startAt }
                ?: last.startAt
            val previous = alreadyObservedThrough[last.appId]
            if (previous == null || closeAt >= previous) {
                output += PresenceSessionDeriver.Observation(appId = null, at = closeAt)
            }
        }

        return output.distinct()
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
