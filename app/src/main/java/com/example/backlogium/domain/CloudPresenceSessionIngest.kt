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

    /**
     * Expand cloud intervals into observations for the existing presence deriver.
     *
     * @param seededAppId the Room open the ingestor will seed the fold from, if any. A fully
     *   stored ongoing interval with no preceding admitted fragment still proves a switch when
     *   it names a different game than this seed.
     */
    fun observations(
        intervals: List<CloudPresenceInterval>,
        gameSources: Map<Long, GameSource>,
        gapToleranceMillis: Long = DEFAULT_GAP_TOLERANCE_MILLIS,
        storedSessions: Map<Long, List<StoredSessionSpan>> = emptyMap(),
        seededAppId: Long? = null,
    ): List<PresenceSessionDeriver.Observation> {
        require(gapToleranceMillis >= 0L)
        val ordered = intervals.sortedWith(
            compareBy<CloudPresenceInterval> { it.startAt }
                .thenBy { it.endAt ?: Long.MAX_VALUE }
                .thenBy { it.appId },
        )
        if (ordered.isEmpty()) return emptyList()

        val output = mutableListOf<PresenceSessionDeriver.Observation>()
        var previousAppId: Long? = null
        var previousEnd: Long? = null
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
            if (uncovered.isEmpty()) {
                // Fully stored: no new play, but a historical interval still proves a
                // different game was running here. Without a null boundary the deriver
                // bridges the surrounding same-app fragments whenever they are within
                // its gap tolerance and re-credits this stored span on top of itself.
                // A fully stored ongoing interval suppresses that boundary only while it
                // continues the app the fold is already carrying: the cloud still reports
                // that game running, so a null here would close the live session a
                // verification read was meant to confirm. With no preceding admitted
                // fragment there is no switch to prove inside the batch, but the seeded
                // Room open is also preceding state: when it names a different game, the
                // ongoing interval still proves the switch away from it. When the preceding
                // admitted fragment named a different game, the ongoing interval still
                // proves the switch to it, so the boundary must close that predecessor.
                val continuesSameApp = when {
                    previousAppId != null -> previousAppId == interval.appId
                    seededAppId != null -> seededAppId == interval.appId
                    else -> true
                }
                if (!interval.ongoing || !continuesSameApp) {
                    // A seeded switch must survive the ingestor's pre-seed filter, which drops
                    // boundaries older than what the seed already observed: emitting at the
                    // confirmed end keeps a proof that the live session is stale while still
                    // letting an older proof drop as history.
                    val boundaryAt =
                        if (interval.ongoing && previousAppId == null && seededAppId != null &&
                            seededAppId != interval.appId
                        ) {
                            confirmedEnd
                        } else {
                            interval.startAt
                        }
                    output += PresenceSessionDeriver.Observation(
                        appId = null,
                        at = boundaryAt,
                    )
                }
                previousAppId = interval.appId
                previousEnd = confirmedEnd
                return@forEach
            }
            val firstStart = uncovered.firstOrNull()?.first
            val prevEnd = previousEnd
            if (firstStart != null && prevEnd != null && previousAppId == interval.appId &&
                firstStart > prevEnd &&
                coverages.any { maxOf(it.startAt, prevEnd) < minOf(it.endAt, firstStart) }
            ) {
                // Same break as within one interval, but across intervals: the gap between
                // consecutive same-app fragments is already stored, so without a null boundary
                // the deriver bridges it whenever it is within its gap tolerance and re-credits
                // stored time. Gaps with no stored overlap keep the intentional nearby-merge.
                output += PresenceSessionDeriver.Observation(
                    appId = null,
                    at = prevEnd,
                )
            }
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
            if (uncovered.isNotEmpty()) {
                previousAppId = interval.appId
                previousEnd = uncovered.last().second
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
