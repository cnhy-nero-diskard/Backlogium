package com.example.backlogium.ui.diagnostics

import com.example.backlogium.data.repo.CloudPresenceSnapshot
import com.example.backlogium.data.repo.PlaySession
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceInterval
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresenceDiagnosticsProjectionTest {
    @Test
    fun hiddenIntervalsAreExcludedAndReturnWhenUnhiddenWithoutNewSnapshot() {
        val snapshot = sampleSnapshot()
        val localSessions = listOf(
            PlaySession(
                id = 1L,
                appId = 10L,
                startAt = millis("2026-09-16T00:00:00Z"),
                minutes = 20,
                open = false,
                endAt = millis("2026-09-16T00:20:00Z"),
            ),
            PlaySession(
                id = 2L,
                appId = 20L,
                startAt = millis("2026-09-15T02:00:00Z"),
                minutes = 10,
                open = false,
                endAt = millis("2026-09-15T02:10:00Z"),
            ),
        )

        val hidden = projectCloudDiagnostics(
            snapshot = snapshot,
            localSessions = localSessions,
            hiddenAppIds = setOf(20L),
            zone = ZoneId.of("UTC"),
        )
        assertEquals(listOf(10L), hidden.intervals.map { it.appId })
        assertEquals(listOf(10L), hidden.dateDisagreements.map { it.appId })
        assertEquals("2026-09-15", hidden.dateDisagreements.single().cloudDate)
        assertEquals("2026-09-16", hidden.dateDisagreements.single().localDate)

        val visibleAgain = projectCloudDiagnostics(
            snapshot = snapshot,
            localSessions = localSessions,
            hiddenAppIds = emptySet(),
            zone = ZoneId.of("UTC"),
        )
        assertEquals(listOf(10L, 20L), visibleAgain.intervals.map { it.appId })
        assertTrue(visibleAgain.intervals.any { it.appId == 20L })
    }

    @Test
    fun unrelatedSameAppSessionOnAnotherDayProducesNoDisagreement() {
        val snapshot = CloudPresenceSnapshot(
            windowStart = millis("2026-09-15T00:00:00Z"),
            windowEnd = millis("2026-09-17T00:00:00Z"),
            readAt = millis("2026-09-17T00:01:00Z"),
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 10L,
                    gameName = "Portal",
                    startAt = millis("2026-09-15T10:00:00Z"),
                    endAt = millis("2026-09-15T10:30:00Z"),
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = millis("2026-09-15T10:30:00Z"),
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 2,
            nextPosition = null,
            hasMore = false,
        )
        val localSessions = listOf(
            PlaySession(
                id = 1L,
                appId = 10L,
                startAt = millis("2026-09-15T10:05:00Z"),
                minutes = 20,
                open = false,
                endAt = millis("2026-09-15T10:25:00Z"),
            ),
            // Same game, another day inside the same snapshot window: unrelated play that
            // must not generate a false date disagreement against the 09-15 cloud interval.
            PlaySession(
                id = 2L,
                appId = 10L,
                startAt = millis("2026-09-16T01:00:00Z"),
                minutes = 20,
                open = false,
                endAt = millis("2026-09-16T01:20:00Z"),
            ),
        )

        val state = projectCloudDiagnostics(
            snapshot = snapshot,
            localSessions = localSessions,
            hiddenAppIds = emptySet(),
            zone = ZoneId.of("UTC"),
        )

        assertTrue(state.dateDisagreements.isEmpty())
    }

    @Test
    fun sessionEndingInsideCloudIntervalPairsByOverlap() {
        val snapshot = CloudPresenceSnapshot(
            windowStart = millis("2026-09-15T00:00:00Z"),
            windowEnd = millis("2026-09-17T00:00:00Z"),
            readAt = millis("2026-09-17T00:01:00Z"),
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 10L,
                    gameName = "Portal",
                    startAt = millis("2026-09-16T00:05:00Z"),
                    endAt = millis("2026-09-16T00:25:00Z"),
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = millis("2026-09-16T00:25:00Z"),
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 2,
            nextPosition = null,
            hasMore = false,
        )
        // Local session starts the previous day but its recorded end reaches into the cloud
        // interval: pairing uses the actual endAt, so the same play is compared and the
        // 09-15 vs 09-16 attribution difference is reported.
        val localSessions = listOf(
            PlaySession(
                id = 1L,
                appId = 10L,
                startAt = millis("2026-09-15T23:50:00Z"),
                minutes = 20,
                open = false,
                endAt = millis("2026-09-16T00:10:00Z"),
            ),
        )

        val state = projectCloudDiagnostics(
            snapshot = snapshot,
            localSessions = localSessions,
            hiddenAppIds = emptySet(),
            zone = ZoneId.of("UTC"),
        )

        assertEquals(1, state.dateDisagreements.size)
        assertEquals("2026-09-16", state.dateDisagreements.single().cloudDate)
        assertEquals("2026-09-15", state.dateDisagreements.single().localDate)
    }

    private companion object {
        fun sampleSnapshot() = CloudPresenceSnapshot(
            windowStart = millis("2026-09-15T00:00:00Z"),
            windowEnd = millis("2026-09-17T00:00:00Z"),
            readAt = millis("2026-09-17T00:01:00Z"),
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 10L,
                    gameName = "Portal",
                    startAt = millis("2026-09-15T23:50:00Z"),
                    endAt = millis("2026-09-16T00:10:00Z"),
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = millis("2026-09-16T00:10:00Z"),
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
                CloudPresenceInterval(
                    appId = 20L,
                    gameName = "Hidden Game",
                    startAt = millis("2026-09-15T02:00:00Z"),
                    endAt = millis("2026-09-15T02:10:00Z"),
                    ongoing = false,
                    coverage = CloudCoverageState.UNKNOWN,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = true,
                ),
            ),
            current = null,
            observationCount = 3,
            nextPosition = null,
            hasMore = false,
        )

        fun millis(value: String): Long = java.time.Instant.parse(value).toEpochMilli()
    }
}
