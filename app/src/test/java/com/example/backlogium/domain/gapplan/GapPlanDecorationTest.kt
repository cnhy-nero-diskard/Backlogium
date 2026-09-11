package com.example.backlogium.domain.gapplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a live count is allowed to do, and — far more importantly — what it is not.
 *
 * The headline test is [everyLookupSucceedingAndEveryLookupFailingProduceIdenticalMembership].
 * If that ever fails, the same request produces different plans depending on whether the network
 * answered in time, and a snapshot the player is asked to commit a month to stops meaning
 * anything.
 */
class GapPlanDecorationTest {

    private val single = candidate(1, remainingMinutes = 100)
    private val multiA = candidate(2, remainingMinutes = 100, multiplayer = true)
    private val multiB = candidate(3, remainingMinutes = 100, multiplayer = true)
    private val multiC = candidate(4, remainingMinutes = 100, multiplayer = true)

    @Test fun availableCountsOrderMultiplayerRowsHighestFirst() {
        val decorated = decorate(
            members = listOf(multiA, multiB, multiC),
            counts = mapOf(2L to 10, 3L to 900, 4L to 50),
        )

        assertEquals(listOf(3L, 4L, 2L), decorated.map { it.appId })
    }

    /** Single-player rows never move because a neighbour's count arrived. */
    @Test fun singlePlayerRowsKeepTheirPositions() {
        val decorated = decorate(
            members = listOf(multiA, single, multiB),
            counts = mapOf(2L to 10, 3L to 900),
        )

        assertEquals(listOf(3L, 1L, 2L), decorated.map { it.appId })
        // The single-player row is in slot 1 both before and after.
        assertEquals(1L, decorated[1].appId)
    }

    /** An unavailable count is not a zero, and contributes no ordering preference of its own. */
    @Test fun unavailableCountsSortBehindAvailableOnesAndClaimNothing() {
        val decorated = decorate(
            members = listOf(multiA, multiB, multiC),
            counts = mapOf(3L to 0),
        )

        // A successful count of zero is a real fact and outranks two unknowns.
        assertEquals(listOf(3L, 2L, 4L), decorated.map { it.appId })
        assertEquals(
            listOf(GapPlanReason.PlayingNow(0)),
            decorated.first().reasons.filterIsInstance<GapPlanReason.PlayingNow>(),
        )
        assertTrue(decorated[1].reasons.none { it is GapPlanReason.PlayingNow })
        assertTrue(decorated[2].reasons.none { it is GapPlanReason.PlayingNow })
    }

    /**
     * The property the whole "decoration only" decision rests on. Run the same snapshot with every
     * lookup succeeding and with every lookup failing: the games, and the variants they are in,
     * must be identical. Only row order and reason chips may differ.
     */
    @Test fun everyLookupSucceedingAndEveryLookupFailingProduceIdenticalMembership() {
        val snapshot = snapshot(listOf(multiA, single, multiB, multiC))

        val enriched = GapPlanDecoration.apply(snapshot, mapOf(2L to 5, 3L to 900, 4L to 50))
        val offline = GapPlanDecoration.apply(snapshot, emptyMap())

        assertEquals(
            enriched.variants.map { v -> v.members.map { it.appId }.sorted() },
            offline.variants.map { v -> v.members.map { it.appId }.sorted() },
        )
        assertEquals(
            enriched.variants.map { it.intensity },
            offline.variants.map { it.intensity },
        )
        assertEquals(enriched.variants.map { it.plannedMinutes }, offline.variants.map { it.plannedMinutes })
        // The permitted differences, both present.
        assertEquals(
            listOf(3L, 1L, 4L, 2L),
            enriched.variants.first().members.map { it.appId },
        )
        assertEquals(
            listOf(2L, 1L, 3L, 4L),
            offline.variants.first().members.map { it.appId },
        )
        assertTrue(enriched.variants.first().members.any { m -> m.reasons.any { it is GapPlanReason.PlayingNow } })
        assertTrue(offline.variants.first().members.none { m -> m.reasons.any { it is GapPlanReason.PlayingNow } })
    }

    /** Decoration touches membership in no way at all, not even for a count of a non-member. */
    @Test fun aCountForAGameThatIsNotInThePlanAddsNothing() {
        val decorated = decorate(listOf(multiA), counts = mapOf(99L to 5_000))

        assertEquals(listOf(2L), decorated.map { it.appId })
        assertTrue(decorated.single().reasons.none { it is GapPlanReason.PlayingNow })
    }

    /** A second pass replaces the previous count rather than stacking a second chip. */
    @Test fun redecoratingReplacesRatherThanAccumulates() {
        val once = GapPlanDecoration.apply(snapshot(listOf(multiA)), mapOf(2L to 10))
        val twice = GapPlanDecoration.apply(once, mapOf(2L to 20))

        val reasons = twice.variants.first().members.single()
            .reasons.filterIsInstance<GapPlanReason.PlayingNow>()
        assertEquals(listOf(GapPlanReason.PlayingNow(20)), reasons)

        // And a later pass with nothing available clears the stale fact rather than keeping it.
        val cleared = GapPlanDecoration.apply(twice, emptyMap())
        assertTrue(
            cleared.variants.first().members.single()
                .reasons.none { it is GapPlanReason.PlayingNow },
        )
    }

    @Test fun aVariantWithOneMultiplayerMemberIsAnnotatedButNotReordered() {
        val decorated = decorate(listOf(single, multiA), counts = mapOf(2L to 7))

        assertEquals(listOf(1L, 2L), decorated.map { it.appId })
        assertTrue(decorated[1].reasons.contains(GapPlanReason.PlayingNow(7)))
        assertFalse(decorated[0].reasons.any { it is GapPlanReason.PlayingNow })
    }

    private fun decorate(members: List<GapPlanCandidate>, counts: Map<Long, Int>) =
        GapPlanDecoration.apply(snapshot(members), counts).variants.first().members

    private fun snapshot(members: List<GapPlanCandidate>) = GapPlanSnapshot(
        request = gapRequest(),
        capacity = GapPlanCapacity(
            fullCapacityMinutes = 1_000,
            provenance = CapacityProvenance.PERSONAL_PACE,
            startDate = TODAY.plusDays(1),
            endDate = TODAY.plusDays(60),
        ),
        variants = listOf(
            GapPlanVariant(PlanIntensity.FULL, budgetMinutes = 1_000, members = members),
        ),
        coverage = GapPlanCoverage(members.size, members.size, 0, 0, 0, 0),
        eligiblePool = members,
    )
}
