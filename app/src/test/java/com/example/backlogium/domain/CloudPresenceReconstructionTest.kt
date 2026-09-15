package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresenceReconstructionTest {
    private val game = 440L
    private val otherGame = 620L

    @Test
    fun directGameSwitchProducesEarlierGameIntervalWithoutNotPlayingEntry() {
        val intervals = CloudPresenceReconstruction.reconstruct(
            transitions = listOf(
                transition(at = 0L, appId = game),
                transition(at = 10L * MINUTE, appId = otherGame, previousLastObservedAt = 9L * MINUTE),
            ),
            current = current(at = 11L * MINUTE, appId = otherGame),
        )

        val earlier = intervals.first { it.appId == game }
        assertEquals(0L, earlier.startAt)
        assertEquals(10L * MINUTE, earlier.endAt)
        assertFalse(earlier.ongoing)
    }

    @Test
    fun currentStateKeepsTheFinalGameIntervalOngoing() {
        val intervals = CloudPresenceReconstruction.reconstruct(
            transitions = listOf(transition(at = 0L, appId = game)),
            current = current(at = MINUTE, appId = game),
        )

        assertEquals(1, intervals.size)
        val ongoing = intervals.single()
        assertEquals(game, ongoing.appId)
        assertEquals(0L, ongoing.startAt)
        assertEquals(MINUTE, ongoing.endAt)
        assertTrue(ongoing.ongoing)
    }

    @Test
    fun coverageCarriesContinuousObservedUntilAndUnknownStates() {
        assertEquals(
            CloudCoverageState.CONTINUOUS,
            closedInterval(
                previousLastObservedAt = 9L * MINUTE,
                closingAt = 10L * MINUTE,
            ).coverage,
        )
        assertEquals(
            CloudCoverageState.OBSERVED_UNTIL,
            closedInterval(
                previousLastObservedAt = 0L,
                closingAt = 10L * MINUTE,
            ).coverage,
        )
        assertEquals(
            CloudCoverageState.UNKNOWN,
            closedInterval(previousLastObservedAt = null, closingAt = 10L * MINUTE).coverage,
        )
    }

    @Test
    fun interiorGapPairIsPreservedAndOverridesAFreshTail() {
        val interval = CloudPresenceReconstruction.reconstruct(
            transitions = listOf(
                transition(at = 0L, appId = game),
                transition(at = MINUTE, appId = game, previousLastObservedAt = 0L),
                transition(at = 10L * MINUTE, appId = game, previousLastObservedAt = 9L * MINUTE),
                transition(
                    at = 11L * MINUTE,
                    appId = otherGame,
                    previousLastObservedAt = 10L * MINUTE,
                    previousCoverageLapseFrom = MINUTE,
                    previousCoverageLapseRecoveredAt = 10L * MINUTE,
                ),
            ),
            current = null,
        ).first { it.startAt == 10L * MINUTE }

        assertEquals(CloudCoverageState.OBSERVED_UNTIL, interval.coverage)
        assertEquals(10L * MINUTE, interval.observedUntil)
        assertEquals(MINUTE, interval.coverageLapseFrom)
        assertEquals(10L * MINUTE, interval.coverageLapseRecoveredAt)
    }

    @Test
    fun uncertaintyCarriesForwardToTheFollowingInterval() {
        val intervals = CloudPresenceReconstruction.reconstruct(
            transitions = listOf(
                transition(at = 0L, appId = game),
                transition(
                    at = 10L * MINUTE,
                    appId = otherGame,
                    previousLastObservedAt = 9L * MINUTE,
                    previousCoverageLapseFrom = 2L * MINUTE,
                    previousCoverageLapseRecoveredAt = 8L * MINUTE,
                ),
                transition(
                    at = 20L * MINUTE,
                    appId = 1_000L,
                    previousLastObservedAt = 19L * MINUTE,
                ),
            ),
            current = null,
        )

        val following = intervals.first { it.appId == otherGame }
        assertTrue(following.mayHaveStartedBefore)
        assertEquals(CloudCoverageState.CONTINUOUS, following.coverage)
    }

    @Test
    fun intervalsContainOnlyObservedCloudFacts() {
        val interval = closedInterval(previousLastObservedAt = 9L * MINUTE, closingAt = 10L * MINUTE)

        assertEquals(CloudCoverageState.CONTINUOUS, interval.coverage)
        assertEquals(0L, interval.startAt)
        assertEquals(10L * MINUTE, interval.endAt)
        assertFalse(interval.ongoing)
    }

    @Test
    fun shortRetainedInteriorPairBelowOldToleranceIsStillNotContinuous() {
        // Regression: the reader applies no tolerance to the retained interior-gap pair.
        // A 90-second retained span with a fresh tail must not reconstruct as CONTINUOUS.
        val interval = closedInterval(
            previousLastObservedAt = 10L * MINUTE,
            closingAt = 10L * MINUTE + 30_000L,
            previousCoverageLapseFrom = 0L,
            previousCoverageLapseRecoveredAt = 90_000L,
        )

        assertEquals(CloudCoverageState.OBSERVED_UNTIL, interval.coverage)
        assertEquals(0L, interval.coverageLapseFrom)
        assertEquals(90_000L, interval.coverageLapseRecoveredAt)
    }

    private fun closedInterval(
        previousLastObservedAt: Long?,
        closingAt: Long,
        previousCoverageLapseFrom: Long? = null,
        previousCoverageLapseRecoveredAt: Long? = null,
    ): CloudPresenceInterval {
        return CloudPresenceReconstruction.reconstruct(
            transitions = listOf(
                transition(at = 0L, appId = game),
                transition(
                    at = closingAt,
                    appId = otherGame,
                    previousLastObservedAt = previousLastObservedAt,
                    previousCoverageLapseFrom = previousCoverageLapseFrom,
                    previousCoverageLapseRecoveredAt = previousCoverageLapseRecoveredAt,
                ),
            ),
            current = null,
        ).single()
    }

    private fun transition(
        at: Long,
        appId: Long?,
        previousLastObservedAt: Long? = null,
        previousCoverageLapseFrom: Long? = null,
        previousCoverageLapseRecoveredAt: Long? = null,
    ) = CloudPresenceTransition(
        at = at,
        appId = appId,
        gameName = null,
        personastate = 1,
        previousLastObservedAt = previousLastObservedAt,
        previousCoverageLapseFrom = previousCoverageLapseFrom,
        previousCoverageLapseRecoveredAt = previousCoverageLapseRecoveredAt,
        schemaVersion = 3,
    )

    private fun current(at: Long, appId: Long?, since: Long? = null) = CloudPresenceCurrentState(
        observedAt = at,
        appId = appId,
        gameName = null,
        personastate = 1,
        since = since,
        coverageLapseFrom = null,
        coverageLapseRecoveredAt = null,
        schemaVersion = 3,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
