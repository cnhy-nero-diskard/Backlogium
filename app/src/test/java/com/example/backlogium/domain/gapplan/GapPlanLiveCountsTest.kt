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
 * the player just opened — and because a plan that is already complete must never be held hostage
 * to a decoration that cannot answer.
 */
class GapPlanLiveCountsTest {

    @Test fun onlyMultiplayerMembersAreEverLookedUp() {
        val liveCounts = GapPlanLiveCounts(repository())
        val snapshot = snapshot(
            listOf(
                candidate(1, 100, multiplayer = false),
                candidate(2, 100, multiplayer = true),
                candidate(3, 100, multiplayer = false),
            ),
        )

        assertEquals(listOf(2L), liveCounts.lookupTargets(snapshot))
    }

    @Test fun aMemberSharedAcrossVariantsIsLookedUpOnce() {
        val shared = candidate(2, 100, multiplayer = true)
        val liveCounts = GapPlanLiveCounts(repository())
        val snapshot = GapPlanSnapshot(
            request = gapRequest(),
            capacity = capacity(),
            variants = PlanIntensity.entries.map {
                GapPlanVariant(it, budgetMinutes = 1_000, members = listOf(shared))
            },
            coverage = GapPlanCoverage(1, 1, 0, 0, 0, 0),
            eligiblePool = listOf(shared),
        )

        assertEquals(listOf(2L), liveCounts.lookupTargets(snapshot))
    }

    /**
     * Three variants of five cannot exceed fifteen, but the ceiling is enforced rather than
     * inferred from the plan's shape — an invariant that holds by accident is one edit away from
     * not holding.
     */
    @Test fun theFifteenIdCeilingIsEnforcedNotAssumed() {
        val liveCounts = GapPlanLiveCounts(repository())
        val many = (1L..40L).map { candidate(it, 100, multiplayer = true) }
        val snapshot = GapPlanSnapshot(
            request = gapRequest(),
            capacity = capacity(),
            variants = listOf(GapPlanVariant(PlanIntensity.FULL, 100_000, many)),
            coverage = GapPlanCoverage(40, 40, 0, 0, 0, 0),
            eligiblePool = many,
        )

        val targets = liveCounts.lookupTargets(snapshot)
        assertEquals(GapPlanLiveCounts.MAX_LOOKUPS, targets.size)
        // Deterministic, so the cap never silently depends on iteration order.
        assertEquals((1L..15L).toList(), targets)
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

    /** Never more than four requests in flight at once. */
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
     * One window for the whole pass. A slow endpoint must not hold a finished plan open, and the
     * counts that *did* arrive are still real facts worth showing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theWindowBoundsTheWholePassAndKeepsWhatAlreadyAnswered() = runTest {
        val counts = CurrentPlayerCounts { appId ->
            // The first four answer at once; everything after stalls past the window.
            if (appId > 4L) delay(GapPlanLiveCounts.WINDOW_MILLIS * 10)
            appId.toInt()
        }

        val result = GapPlanLiveCounts(counts).fetch((1L..15L).toList())

        assertEquals(setOf(1L, 2L, 3L, 4L), result.keys)
        // The virtual clock advanced by the window, not by the stalled calls.
        assertEquals(GapPlanLiveCounts.WINDOW_MILLIS, testScheduler.currentTime)
    }

    @Test fun theDeclaredBoundsAreTheOnesInTheSpec() {
        assertEquals(15, GapPlanLiveCounts.MAX_LOOKUPS)
        assertEquals(4, GapPlanLiveCounts.MAX_CONCURRENCY)
        assertEquals(8_000L, GapPlanLiveCounts.WINDOW_MILLIS)
    }

    private fun capacity() = GapPlanCapacity(
        fullCapacityMinutes = 1_000,
        provenance = CapacityProvenance.PERSONAL_PACE,
        startDate = TODAY.plusDays(1),
        endDate = TODAY.plusDays(60),
    )

    private fun snapshot(members: List<GapPlanCandidate>) = GapPlanSnapshot(
        request = gapRequest(),
        capacity = capacity(),
        variants = listOf(GapPlanVariant(PlanIntensity.FULL, 1_000, members)),
        coverage = GapPlanCoverage(members.size, members.size, 0, 0, 0, 0),
        eligiblePool = members,
    )

    private fun repository(
        counts: Map<Long, Int> = emptyMap(),
        failing: Set<Long> = emptySet(),
    ) = CurrentPlayerCounts { appId -> if (appId in failing) null else counts[appId] }
}
