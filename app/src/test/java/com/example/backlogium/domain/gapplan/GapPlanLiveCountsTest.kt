package com.example.backlogium.domain.gapplan

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bounds on post-finalization enrichment.
 *
 * Each of these exists because the alternative is an unthrottled burst against Steam from a screen
 * the player just opened — and because a result that is already complete must never be held
 * hostage to a decoration that cannot answer.
 */
class GapPlanLiveCountsTest {

    @Test fun onlyMultiplayerPicksAreEverLookedUp() {
        val liveCounts = GapPlanLiveCounts(repository())
        val snapshot = snapshotOf(
            pick(PlanIntensity.RELAXED, candidate(1, 100, multiplayer = false)),
            pick(PlanIntensity.BALANCED, candidate(2, 200, multiplayer = true)),
            pick(PlanIntensity.FULL, candidate(3, 300, multiplayer = false)),
        )

        assertEquals(listOf(2L), liveCounts.lookupTargets(snapshot))
    }

    /**
     * A wholly single-player result issues no request at all — not one that is issued and then
     * discarded.
     */
    @Test fun aWhollySinglePlayerResultIssuesNoLookupWhatsoever() = runTest {
        val requested = AtomicInteger()
        val liveCounts = GapPlanLiveCounts(
            CurrentPlayerCounts { appId ->
                requested.incrementAndGet()
                appId.toInt()
            },
        )
        val snapshot = snapshotOf(
            pick(PlanIntensity.RELAXED, candidate(1, 100)),
            pick(PlanIntensity.BALANCED, candidate(2, 200)),
            pick(PlanIntensity.FULL, candidate(3, 300)),
        )

        val targets = liveCounts.lookupTargets(snapshot)
        assertEquals(emptyList<Long>(), targets)
        assertEquals(emptyMap<Long, Int>(), liveCounts.fetch(targets))
        assertEquals(0, requested.get())
    }

    @Test fun anEmptyTierContributesNoTarget() {
        val liveCounts = GapPlanLiveCounts(repository())
        val snapshot = snapshotOf(
            pick(PlanIntensity.RELAXED, null),
            pick(PlanIntensity.FULL, candidate(2, 200, multiplayer = true)),
        )

        assertEquals(listOf(2L), liveCounts.lookupTargets(snapshot))
    }

    /**
     * A result holds one pick per tier, so three is the whole ceiling — but it is enforced rather
     * than inferred from the result's shape. An invariant that holds by accident is one edit away
     * from not holding.
     */
    @Test fun theThreeIdCeilingIsEnforcedNotAssumed() {
        val liveCounts = GapPlanLiveCounts(repository())
        val many = (1L..40L).map { candidate(it, 100, multiplayer = true) }
        val snapshot = snapshotOf(
            *many.map { pick(PlanIntensity.FULL, it) }.toTypedArray(),
        )

        val targets = liveCounts.lookupTargets(snapshot)
        assertEquals(GapPlanLiveCounts.MAX_LOOKUPS, targets.size)
        // Deterministic, so the cap never silently depends on iteration order.
        assertEquals(listOf(1L, 2L, 3L), targets)
    }

    @Test fun aSuccessfulZeroIsRetainedAndAFailureIsAbsentRatherThanZero() = runTest {
        val liveCounts = GapPlanLiveCounts(
            repository(counts = mapOf(1L to 0, 2L to 500), failing = setOf(3L)),
        )

        val result = liveCounts.fetch(listOf(1L, 2L, 3L))

        assertEquals(0, result[1L])
        assertEquals(500, result[2L])
        assertFalse("a failed lookup must be absent, never zero", result.containsKey(3L))
    }

    @Test fun noLookupIsIssuedForAnEmptyTargetList() = runTest {
        val requested = AtomicInteger()
        val counts = CurrentPlayerCounts { appId ->
            requested.incrementAndGet()
            appId.toInt()
        }

        assertEquals(emptyMap<Long, Int>(), GapPlanLiveCounts(counts).fetch(emptyList()))
        assertEquals(0, requested.get())
    }

    /**
     * Never more than four requests in flight at once.
     *
     * Exercised over more ids than a result can hold, because the bound belongs to the fetch and
     * must not become untestable just because the caller's ceiling happens to be smaller than it.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun concurrencyIsBoundedToFour() = runTest {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val counts = CurrentPlayerCounts { appId ->
            val now = inFlight.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            delay(10)
            inFlight.decrementAndGet()
            appId.toInt()
        }

        GapPlanLiveCounts(counts).fetch((1L..15L).toList())

        assertTrue("peak was ${peak.get()}", peak.get() <= GapPlanLiveCounts.MAX_CONCURRENCY)
        assertEquals(GapPlanLiveCounts.MAX_CONCURRENCY, peak.get())
    }

    /**
     * One window for the whole pass. A slow endpoint must not hold a finished result open, and the
     * counts that *did* arrive are still real facts worth showing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theWindowBoundsTheWholePassAndKeepsWhatAlreadyAnswered() = runTest {
        val counts = CurrentPlayerCounts { appId ->
            // The first two answer at once; everything after stalls past the window.
            if (appId > 2L) delay(GapPlanLiveCounts.WINDOW_MILLIS * 10)
            appId.toInt()
        }

        val result = GapPlanLiveCounts(counts).fetch(listOf(1L, 2L, 3L))

        assertEquals(setOf(1L, 2L), result.keys)
        // The virtual clock advanced by the window, not by the stalled calls.
        assertEquals(GapPlanLiveCounts.WINDOW_MILLIS, testScheduler.currentTime)
    }

    @Test fun theDeclaredBoundsAreTheOnesInTheSpec() {
        assertEquals(3, GapPlanLiveCounts.MAX_LOOKUPS)
        assertEquals(4, GapPlanLiveCounts.MAX_CONCURRENCY)
        assertEquals(8_000L, GapPlanLiveCounts.WINDOW_MILLIS)
    }

    private fun repository(
        counts: Map<Long, Int> = emptyMap(),
        failing: Set<Long> = emptySet(),
    ) = CurrentPlayerCounts { appId -> if (appId in failing) null else counts[appId] }
}
