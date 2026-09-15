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
            PlaySession(1L, 10L, millis("2026-09-16T01:00:00Z"), 20, false),
            PlaySession(2L, 20L, millis("2026-09-15T02:00:00Z"), 10, false),
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

    private companion object {
        fun sampleSnapshot() = CloudPresenceSnapshot(
            windowStart = millis("2026-09-15T00:00:00Z"),
            windowEnd = millis("2026-09-17T00:00:00Z"),
            readAt = millis("2026-09-17T00:01:00Z"),
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 10L,
                    gameName = "Portal",
                    startAt = millis("2026-09-15T00:00:00Z"),
                    endAt = millis("2026-09-15T00:30:00Z"),
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = millis("2026-09-15T00:30:00Z"),
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
