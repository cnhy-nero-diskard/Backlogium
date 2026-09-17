package com.example.backlogium.domain

import com.example.backlogium.domain.SessionDiffer.SessionAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudPresencePlaytimePlacementTest {
    @Test
    fun largestRemainderAllocatesTheExactDeltaWhenSpansAreLonger() {
        val actions = place(
            minutes = 10,
            periodEndAt = 300L,
            intervals = listOf(
                interval(startAt = 0L, endAt = 100L),
                interval(startAt = 100L, endAt = 300L),
            ),
        )

        assertEquals(listOf(3, 7), openMinutes(actions))
        assertEquals(10, actions.sumOf { it.addedMinutes })
    }

    @Test
    fun allocationRemainsExactWhenSpansAreShorterThanTheDelta() {
        val actions = place(
            minutes = 60,
            periodEndAt = 20L,
            intervals = listOf(
                interval(startAt = 0L, endAt = 1L),
                interval(startAt = 10L, endAt = 11L),
            ),
        )

        assertEquals(listOf(30, 30), openMinutes(actions))
        assertEquals(60, actions.sumOf { it.addedMinutes })
    }

    @Test
    fun anInteriorGapBelowToleranceContributesOnlyItsConfirmedPortions() {
        val actions = place(
            minutes = 10,
            periodEndAt = 30L,
            intervals = listOf(
                interval(
                    startAt = 0L,
                    endAt = 10L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 10L,
                    coverageLapseFrom = 3L,
                    coverageLapseRecoveredAt = 5L,
                ),
                interval(startAt = 20L, endAt = 28L),
            ),
            gapToleranceMillis = 3L,
        )

        assertEquals(listOf(5, 5), openMinutes(actions))
    }

    @Test
    fun anInteriorGapAboveToleranceRejectsTheWholeIntervalIncludingFreshTail() {
        val actions = place(
            minutes = 10,
            intervals = listOf(
                interval(
                    startAt = 0L,
                    endAt = 20L,
                    coverage = CloudCoverageState.OBSERVED_UNTIL,
                    observedUntil = 20L,
                    coverageLapseFrom = 1L,
                    coverageLapseRecoveredAt = 10L,
                ),
            ),
            gapToleranceMillis = 5L,
        )

        assertTrue(actions.isEmpty())
    }

    @Test
    fun finalOngoingIntervalExtendsThePriorOpenSessionInsteadOfOpeningAnother() {
        val prior = SessionDiffer.OpenSession(startAt = 0L, minutes = 5, lastIncreaseAt = 10L)
        val actions = place(
            minutes = 3,
            periodStartAt = 10L,
            periodEndAt = 20L,
            intervals = listOf(interval(startAt = 10L, endAt = 20L, ongoing = true)),
            priorOpenSession = prior,
        )

        assertEquals(1, actions.count { it is SessionAction.Extend })
        assertTrue(actions.none { it is SessionAction.Open })
        assertEquals(3, actions.sumOf { it.addedMinutes })
    }

    @Test
    fun noCoveringIntervalFallsBackWithoutProducingOutOfCoverageActions() {
        val actions = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = GAME,
                diffedMinutes = 10,
                periodStartAt = 100L,
                periodEndAt = 200L,
                intervals = listOf(interval(startAt = 0L, endAt = 50L)),
            ),
        )

        assertTrue(actions == null)
    }

    @Test
    fun zeroIncreaseFallsBackWithoutPlacement() {
        val actions = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = GAME,
                diffedMinutes = 0,
                periodStartAt = 0L,
                periodEndAt = 10L,
                intervals = listOf(interval(startAt = 0L, endAt = 10L)),
            ),
        )

        assertTrue(actions == null)
    }

    @Test
    fun unknownAndZeroSpansFallBackWithoutPlacement() {
        val actions = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = GAME,
                diffedMinutes = 10,
                periodStartAt = 0L,
                periodEndAt = 20L,
                intervals = listOf(
                    interval(
                        startAt = 0L,
                        endAt = 10L,
                        coverage = CloudCoverageState.UNKNOWN,
                    ),
                    interval(startAt = 20L, endAt = 20L),
                ),
            ),
        )

        assertTrue(actions == null)
    }

    @Test
    fun multipleIntervalsWithRejectedInteriorOutagesContributeNoSpan() {
        val actions = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = GAME,
                diffedMinutes = 10,
                periodStartAt = 0L,
                periodEndAt = 20L,
                intervals = listOf(
                    interval(
                        startAt = 0L,
                        endAt = 10L,
                        coverage = CloudCoverageState.OBSERVED_UNTIL,
                        observedUntil = 10L,
                        coverageLapseFrom = 1L,
                        coverageLapseRecoveredAt = 8L,
                    ),
                    interval(
                        startAt = 10L,
                        endAt = 20L,
                        coverage = CloudCoverageState.OBSERVED_UNTIL,
                        observedUntil = 20L,
                        coverageLapseFrom = 11L,
                        coverageLapseRecoveredAt = 18L,
                    ),
                ),
                gapToleranceMillis = 5L,
            ),
        )

        assertTrue(actions == null)
    }

    @Test
    fun clippingPreventsActionsOutsideTheCoveredPeriod() {
        val actions = place(
            minutes = 10,
            periodStartAt = 100L,
            periodEndAt = 200L,
            intervals = listOf(interval(startAt = 50L, endAt = 250L)),
        )

        assertTrue(actions.all { action ->
            action.startAt >= 100L && when (action) {
                is SessionAction.Open -> action.endAt <= 200L
                is SessionAction.Extend -> action.endAt <= 200L
                is SessionAction.Close -> action.endAt <= 200L
            }
        })
    }

    @Test
    fun anEqualConfirmedSpanRetainsTheWholeDelta() {
        val actions = place(
            minutes = 10,
            intervals = listOf(interval(startAt = 0L, endAt = 10L)),
        )

        assertEquals(listOf(10), openMinutes(actions))
    }

    private fun place(
        minutes: Int,
        periodStartAt: Long = 0L,
        periodEndAt: Long = 10L,
        intervals: List<CloudPresenceInterval>,
        priorOpenSession: SessionDiffer.OpenSession? = null,
        gapToleranceMillis: Long = 10L,
    ): List<SessionAction> = CloudPresencePlaytimePlacement.place(
        CloudPresencePlaytimePlacement.Request(
            appId = GAME,
            diffedMinutes = minutes,
            periodStartAt = periodStartAt,
            periodEndAt = periodEndAt,
            intervals = intervals,
            priorOpenSession = priorOpenSession,
            gapToleranceMillis = gapToleranceMillis,
        ),
    ) ?: emptyList()

    private fun openMinutes(actions: List<SessionAction>): List<Int> = actions
        .filterIsInstance<SessionAction.Open>()
        .map { it.minutes }

    private fun interval(
        startAt: Long,
        endAt: Long,
        ongoing: Boolean = false,
        coverage: CloudCoverageState = CloudCoverageState.CONTINUOUS,
        observedUntil: Long? = null,
        coverageLapseFrom: Long? = null,
        coverageLapseRecoveredAt: Long? = null,
    ) = CloudPresenceInterval(
        appId = GAME,
        gameName = "Portal",
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
        const val GAME = 440L
    }
}
