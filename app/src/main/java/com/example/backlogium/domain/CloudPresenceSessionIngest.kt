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
            val confirmedEnd = interval.confirmedEnd(gapToleranceMillis)
                ?.takeIf { it >= interval.startAt }
                ?.takeIf { admitted }
            if (confirmedEnd == null) {
                output += PresenceSessionDeriver.Observation(appId = null, at = interval.startAt)
                return@forEach
            }

            output += PresenceSessionDeriver.Observation(interval.appId, interval.startAt)
            if (confirmedEnd > interval.startAt) {
                output += PresenceSessionDeriver.Observation(interval.appId, confirmedEnd)
            }
        }

        val last = ordered.last()
        if (!last.ongoing) {
            val closeAt = last.confirmedEnd(gapToleranceMillis)
                ?.takeIf { it >= last.startAt }
                ?: last.startAt
            output += PresenceSessionDeriver.Observation(appId = null, at = closeAt)
        }

        return output.distinct()
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
