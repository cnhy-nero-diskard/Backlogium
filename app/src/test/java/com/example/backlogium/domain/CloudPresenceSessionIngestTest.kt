package com.example.backlogium.domain

import com.example.backlogium.domain.SessionDiffer.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresenceSessionIngestTest {
    private val shared = 440L
    private val owned = 620L
    private val unknown = 730L

    @Test
    fun admitsOnlyFamilySharedIntervals() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(shared, endAt = 10L),
                interval(owned, startAt = 10L, endAt = 20L),
                interval(unknown, startAt = 20L, endAt = 30L),
            ),
            gameSources = mapOf(
                shared to GameSource.FAMILY_SHARED,
                owned to GameSource.STEAM_OWNED,
            ),
        )

        assertEquals(
            listOf(
                PresenceSessionDeriver.Observation(shared, 0L),
                PresenceSessionDeriver.Observation(shared, 10L),
                PresenceSessionDeriver.Observation(null, 10L),
                PresenceSessionDeriver.Observation(null, 20L),
                PresenceSessionDeriver.Observation(null, 30L),
            ),
            observations,
        )
        assertTrue(observations.none { it.appId == owned || it.appId == unknown })

        val result = fold(observations)
        assertTrue(result.actions.none { action -> action.appId == owned || action.appId == unknown })
    }

    @Test
    fun continuousAndObservedUntilIntervalsUseOnlyConfirmedBounds() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(shared, endAt = 10L, coverage = CloudCoverageState.CONTINUOUS),
                interval(
                    shared,
                    startAt = 20L,
                    endAt = 40L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 30L,
                ),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
        )

        assertEquals(
            listOf(
                PresenceSessionDeriver.Observation(shared, 0L),
                PresenceSessionDeriver.Observation(shared, 10L),
                PresenceSessionDeriver.Observation(shared, 20L),
                PresenceSessionDeriver.Observation(shared, 30L),
                PresenceSessionDeriver.Observation(null, 30L),
            ),
            observations,
        )
    }

    @Test
    fun unknownCoverageAndInteriorGapBeyondToleranceProduceNoGameObservation() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(shared, endAt = 10L, coverage = CloudCoverageState.UNKNOWN),
                interval(
                    shared,
                    startAt = 20L,
                    endAt = 40L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 40L,
                    coverageLapseFrom = 0L,
                    coverageLapseRecoveredAt = 20L,
                ),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            gapToleranceMillis = 10L,
        )

        assertTrue(observations.all { it.appId == null })
        assertTrue(fold(observations).actions.isEmpty())
    }

    @Test
    fun playEntirelyInsideAnUnobservedGapDoesNotOpenASession() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(shared, endAt = 10L, coverage = CloudCoverageState.UNKNOWN),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
        )

        assertTrue(fold(observations).actions.isEmpty())
    }

    @Test
    fun nearbyIntervalsFoldIntoOnePresenceSession() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(shared, startAt = 0L, endAt = 5L * MINUTE),
                interval(shared, startAt = 8L * MINUTE, endAt = 12L * MINUTE, ongoing = true),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
        )

        val actions = fold(observations).actions
        assertEquals(1, actions.count { it is SessionAction.Open })
        assertEquals(0, actions.count { it is SessionAction.Close })
        assertTrue(actions.any { it is SessionAction.Extend })
    }

    private fun fold(
        observations: List<PresenceSessionDeriver.Observation>,
    ): PresenceSessionDeriver.DerivationResult {
        var openSession: PresenceSessionDeriver.OpenSession? = null
        val actions = mutableListOf<SessionAction>()
        observations.forEach { observation ->
            val result = PresenceSessionDeriver().derive(observation, openSession)
            actions += result.actions
            openSession = result.openSession
        }
        return PresenceSessionDeriver.DerivationResult(actions, openSession)
    }

    private fun interval(
        appId: Long,
        startAt: Long = 0L,
        endAt: Long? = 10L,
        ongoing: Boolean = false,
        coverage: CloudCoverageState = CloudCoverageState.CONTINUOUS,
        observedUntil: Long? = null,
        coverageLapseFrom: Long? = null,
        coverageLapseRecoveredAt: Long? = null,
    ) = CloudPresenceInterval(
        appId = appId,
        gameName = null,
        startAt = startAt,
        endAt = endAt,
        ongoing = ongoing,
        coverage = coverage,
        observedUntil = observedUntil,
        coverageLapseFrom = coverageLapseFrom,
        coverageLapseRecoveredAt = coverageLapseRecoveredAt,
        mayHaveStartedBefore = false,
    )

    private companion object {
        const val MINUTE = 60_000L
    }
}
