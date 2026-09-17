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
        assertTrue(
            fold(
                CloudPresenceSessionIngest.observations(
                    intervals = listOf(interval(owned)),
                    gameSources = mapOf(owned to GameSource.STEAM_OWNED),
                ),
            ).actions.isEmpty(),
        )
        assertTrue(
            fold(
                CloudPresenceSessionIngest.observations(
                    intervals = listOf(interval(unknown)),
                    gameSources = emptyMap(),
                ),
            ).actions.isEmpty(),
        )
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
    fun freshTailAfterInteriorGapIsDiscardedInFull() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(
                    shared,
                    startAt = 0L,
                    endAt = 40L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 40L,
                    coverageLapseFrom = 10L,
                    coverageLapseRecoveredAt = 25L,
                ),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            gapToleranceMillis = 10L,
        )

        assertTrue(observations.all { it.appId == null })
        assertTrue(fold(observations).actions.isEmpty())
    }

    @Test
    fun twoInteriorOutagesDoNotCreditTheUnlocatedSpan() {
        // The phase-1 pair retains the largest outage from
        // A@t0 -> A@t1 -> outage -> A@t10 -> A@t11 -> outage -> A@t18 -> B@t19.
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(
                    shared,
                    startAt = 0L,
                    endAt = 19L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 19L,
                    coverageLapseFrom = 1L,
                    coverageLapseRecoveredAt = 10L,
                ),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            gapToleranceMillis = 5L,
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

    @Test
    fun longContinuousIntervalStaysOnePresenceSession() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(interval(shared, endAt = 30L * MINUTE)),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
        )

        val actions = fold(observations).actions
        assertEquals(1, actions.count { it is SessionAction.Open })
        assertEquals(1, actions.count { it is SessionAction.Close })
        assertEquals(30, actions.sumOf { it.addedMinutes })
    }

    @Test
    fun olderGapIsRetainedDespiteNewerStoredSession() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(interval(shared, startAt = 0L, endAt = 2L * MINUTE)),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            storedSessions = mapOf(
                shared to listOf(
                    CloudPresenceSessionIngest.StoredSessionSpan(
                        startAt = 10L * MINUTE,
                        endAt = 12L * MINUTE,
                    ),
                ),
            ),
        )

        val actions = fold(observations).actions
        assertEquals(1, actions.count { it is SessionAction.Open })
        assertEquals(1, actions.count { it is SessionAction.Close })
        assertEquals(2, actions.sumOf { it.addedMinutes })
    }

    @Test
    fun overlappingPortionIsTrimmedButUncoveredTailRemains() {
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(interval(shared, startAt = 0L, endAt = 4L * MINUTE)),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            storedSessions = mapOf(
                shared to listOf(
                    CloudPresenceSessionIngest.StoredSessionSpan(
                        startAt = 0L,
                        endAt = 2L * MINUTE,
                    ),
                ),
            ),
        )

        // The covered [0, 2min] head contributes nothing new; the [2min, 4min] tail still folds.
        assertTrue(observations.none { it.appId == shared && it.at < 2L * MINUTE })
        assertTrue(observations.any { it.appId == shared && it.at == 2L * MINUTE })
        assertTrue(observations.any { it.appId == shared && it.at == 4L * MINUTE })
    }

    @Test
    fun storedSpanInMiddleOfCloudIntervalBreaksDerivedSession() {
        val gapTolerance = CloudPresenceSessionIngest.DEFAULT_GAP_TOLERANCE_MILLIS
        // The covered 2-minute hole sits well below the deriver tolerance, so without a
        // null boundary the [0, 4min] + [6min, 10min] fragments would bridge into one
        // 0..10min session and re-credit the stored 4-6min portion.
        assertTrue(2L * MINUTE < gapTolerance)
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(interval(shared, startAt = 0L, endAt = 10L * MINUTE)),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            gapToleranceMillis = gapTolerance,
            storedSessions = mapOf(
                shared to listOf(
                    CloudPresenceSessionIngest.StoredSessionSpan(
                        startAt = 4L * MINUTE,
                        endAt = 6L * MINUTE,
                    ),
                ),
            ),
        )

        assertTrue(observations.none { it.appId == shared && it.at > 4L * MINUTE && it.at < 6L * MINUTE })
        assertTrue(observations.any { it.appId == null && it.at == 4L * MINUTE })

        val actions = fold(observations).actions
        assertEquals(2, actions.count { it is SessionAction.Open })
        assertEquals(2, actions.count { it is SessionAction.Close })
        assertEquals(8, actions.sumOf { it.addedMinutes })
    }

    @Test
    fun storedSpanBetweenCloudIntervalsBreaksDerivedSession() {
        val gapTolerance = CloudPresenceSessionIngest.DEFAULT_GAP_TOLERANCE_MILLIS
        // Same double-count as a stored span inside one interval, but the covered 2-minute
        // hole sits between two same-game cloud intervals, so each interval yields a single
        // uncovered fragment and only a cross-interval boundary can break the derived session.
        assertTrue(2L * MINUTE < gapTolerance)
        val observations = CloudPresenceSessionIngest.observations(
            intervals = listOf(
                interval(shared, startAt = 0L, endAt = 4L * MINUTE),
                interval(shared, startAt = 6L * MINUTE, endAt = 10L * MINUTE),
            ),
            gameSources = mapOf(shared to GameSource.FAMILY_SHARED),
            gapToleranceMillis = gapTolerance,
            storedSessions = mapOf(
                shared to listOf(
                    CloudPresenceSessionIngest.StoredSessionSpan(
                        startAt = 4L * MINUTE,
                        endAt = 6L * MINUTE,
                    ),
                ),
            ),
        )

        assertTrue(observations.none { it.appId == shared && it.at > 4L * MINUTE && it.at < 6L * MINUTE })
        assertTrue(observations.any { it.appId == null && it.at == 4L * MINUTE })

        val actions = fold(observations).actions
        assertEquals(2, actions.count { it is SessionAction.Open })
        assertEquals(2, actions.count { it is SessionAction.Close })
        assertEquals(8, actions.sumOf { it.addedMinutes })
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
