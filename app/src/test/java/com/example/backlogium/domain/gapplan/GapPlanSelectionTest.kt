package com.example.backlogium.domain.gapplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw: one game per tier, uniform among the candidates near that tier's share, all distinct.
 *
 * These are the claims the reversal away from ranking rests on. Targeting rather than capping is
 * what makes the three tiers mean different things; seeding is what makes the rebuild control do
 * something; and distinctness is what stops the same game filling all three cards.
 */
class GapPlanSelectionTest {

    /**
     * The failure the previous design had, stated as a test.
     *
     * Under a ceiling alone every tier could return the same very short game, because "fits within
     * 70%" and "fits within 100%" are both satisfied by it. Targeting each tier at its own share
     * produces three genuinely different lengths of commitment.
     */
    @Test fun tiersOfferProgressivelyLongerGamesWhenThePoolSpansTheRange() {
        // 100 candidates evenly spread across the whole capacity, so every band is populated.
        val pool = (1L..100L).map { candidate(it, remainingMinutes = it.toInt() * 100) }

        val picks = GapPlanSelection.draw(pool, fullCapacityMinutes = 10_000, seed = 7L).picks
        val relaxed = picks.single { it.intensity == PlanIntensity.RELAXED }.game!!
        val balanced = picks.single { it.intensity == PlanIntensity.BALANCED }.game!!
        val full = picks.single { it.intensity == PlanIntensity.FULL }.game!!

        assertTrue(
            "Relaxed (${relaxed.remainingMinutes}) must be shorter than " +
                "Balanced (${balanced.remainingMinutes})",
            relaxed.remainingMinutes < balanced.remainingMinutes,
        )
        assertTrue(
            "Balanced (${balanced.remainingMinutes}) must be shorter than " +
                "Full (${full.remainingMinutes})",
            balanced.remainingMinutes < full.remainingMinutes,
        )
    }

    /**
     * The case targeting alone does not cover, and the reason ordering is imposed separately.
     *
     * Three games clustered far below every share — a real shape for a library where few games have
     * a resolved length. All of them sit inside several tiers' nearness bands at once, so the draw
     * is free to hand the longer of two near-identical games to Relaxed and the shorter to Balanced.
     * These are the figures that exposed it on a real library: 91h 55m, 93h 44m and 142h 29m against
     * a 302h 54m forecast.
     */
    @Test fun aClusteredPoolIsStillOrderedShortestToLongest() {
        val pool = listOf(
            candidate(1, remainingMinutes = 5_515),
            candidate(2, remainingMinutes = 5_624),
            candidate(3, remainingMinutes = 8_549),
        )

        // Every seed, because the inversion only showed up on some of them.
        (1L..60L).forEach { seed ->
            val picks = GapPlanSelection.draw(pool, fullCapacityMinutes = 18_174, seed = seed).picks
            assertEquals(
                "seed $seed did not order the tiers",
                listOf(5_515, 5_624, 8_549),
                picks.map { it.game?.remainingMinutes },
            )
        }
    }

    /** The ordering is a property of every draw, not of a pool shape that happens to produce it. */
    @Test fun picksAreNeverDecreasingInLengthAcrossTiers() {
        val pools = listOf(
            (1L..100L).map { candidate(it, it.toInt() * 100) },
            (1L..5L).map { candidate(it, 600 + it.toInt()) },
            listOf(candidate(1, 9_500), candidate(2, 9_400), candidate(3, 400)),
            listOf(candidate(1, 100), candidate(2, 100), candidate(3, 100)),
        )

        pools.forEachIndexed { index, pool ->
            (1L..40L).forEach { seed ->
                val lengths = GapPlanSelection.draw(pool, 10_000, seed).picks
                    .mapNotNull { it.game?.remainingMinutes }
                assertEquals(
                    "pool $index seed $seed was not ordered",
                    lengths.sorted(),
                    lengths,
                )
            }
        }
    }

    /**
     * Reassignment cannot hand a tier a game its share cannot hold.
     *
     * Shares rise with intensity, so pairing ascending lengths with ascending shares is feasible
     * whenever any pairing is — but that is an argument, and this is the check.
     */
    @Test fun reorderingNeverPushesAPickPastItsTiersShare() {
        val pools = listOf(
            (1L..100L).map { candidate(it, it.toInt() * 100) },
            listOf(candidate(1, 9_900), candidate(2, 8_400), candidate(3, 6_900)),
            listOf(candidate(1, 5_515), candidate(2, 5_624), candidate(3, 8_549)),
        )

        pools.forEach { pool ->
            (1L..40L).forEach { seed ->
                GapPlanSelection.draw(pool, 10_000, seed).picks.forEach { pick ->
                    val game = pick.game ?: return@forEach
                    assertTrue(
                        "${pick.intensity} held ${game.remainingMinutes} against ${pick.budgetMinutes}",
                        game.remainingMinutes <= pick.budgetMinutes,
                    )
                }
            }
        }
    }

    /**
     * Reassignment skips an empty tier rather than closing the gap.
     *
     * Only the lower tiers can ever be the empty ones — Full has the widest ceiling and draws
     * first, so anything that fits Relaxed fits Full. Both shapes are exercised because the
     * reassignment walks the tiers and the games as two sequences of different lengths, and
     * pulling a game down into a hole is exactly the way that goes wrong.
     */
    @Test fun reassignmentSkipsEmptyTiersInsteadOfClosingTheGap() {
        // Two games, both above the 7,000 Relaxed share: Relaxed stays empty.
        val oneHole = GapPlanSelection.draw(
            listOf(candidate(1, 9_500), candidate(2, 8_000)),
            fullCapacityMinutes = 10_000,
            seed = 3L,
        ).picks
        assertNull(oneHole.single { it.intensity == PlanIntensity.RELAXED }.game)
        assertEquals(
            listOf(8_000, 9_500),
            oneHole.mapNotNull { it.game?.remainingMinutes },
        )

        // One game: only Full draws, and the early return must not move it anywhere.
        val twoHoles = GapPlanSelection.draw(
            listOf(candidate(1, 9_500)),
            fullCapacityMinutes = 10_000,
            seed = 3L,
        ).picks
        assertEquals(
            9_500,
            twoHoles.single { it.intensity == PlanIntensity.FULL }.game!!.remainingMinutes,
        )
        assertTrue(twoHoles.filter { it.intensity != PlanIntensity.FULL }.all { it.isEmpty })
    }

    /** Each tier's own ceiling still holds: targeting a share never overruns it. */
    @Test fun noPickExceedsItsTiersShareOfCapacity() {
        val pool = (1L..100L).map { candidate(it, remainingMinutes = it.toInt() * 100) }

        // Every seed, not one lucky one: the ceiling is a bound, not a tendency.
        (1L..50L).forEach { seed ->
            GapPlanSelection.draw(pool, fullCapacityMinutes = 10_000, seed = seed).picks
                .forEach { pick ->
                    val game = pick.game ?: return@forEach
                    assertTrue(
                        "${pick.intensity} pick of ${game.remainingMinutes} exceeded its " +
                            "${pick.budgetMinutes} share",
                        game.remainingMinutes <= pick.budgetMinutes,
                    )
                }
        }
    }

    @Test fun theSharesAreSeventyEightyFiveAndOneHundredPercentOfCapacity() {
        val draw = GapPlanSelection.draw(emptyList(), fullCapacityMinutes = 6_000, seed = 1L)

        assertEquals(4_200, draw.picks.single { it.intensity == PlanIntensity.RELAXED }.budgetMinutes)
        assertEquals(5_100, draw.picks.single { it.intensity == PlanIntensity.BALANCED }.budgetMinutes)
        assertEquals(6_000, draw.picks.single { it.intensity == PlanIntensity.FULL }.budgetMinutes)
    }

    /** Picks are presented in ascending intensity, whatever order they were drawn in. */
    @Test fun picksArePresentedInAscendingIntensity() {
        val draw = GapPlanSelection.draw(
            (1L..20L).map { candidate(it, it.toInt() * 400) },
            fullCapacityMinutes = 10_000,
            seed = 3L,
        )

        assertEquals(
            listOf(PlanIntensity.RELAXED, PlanIntensity.BALANCED, PlanIntensity.FULL),
            draw.picks.map { it.intensity },
        )
    }

    /**
     * The distinctness rule, in the case the spec names: one game is the nearest fit for more than
     * one tier, so it occupies only one of them and the others take their next-nearest candidate.
     */
    @Test fun oneGameNearestForTwoTiersOccupiesOnlyOne() {
        // All four are under the Relaxed share, so all three tiers anchor on the same longest game.
        val pool = listOf(
            candidate(1, 4_000),
            candidate(2, 3_900),
            candidate(3, 3_800),
            candidate(4, 3_700),
        )

        val picked = GapPlanSelection.draw(pool, fullCapacityMinutes = 10_000, seed = 11L)
            .picks.mapNotNull { it.game?.appId }

        assertEquals(3, picked.size)
        assertEquals("the three picks must be distinct games", 3, picked.toSet().size)
    }

    /** An exhausted pool leaves the tiers it cannot fill empty, without failing the others. */
    @Test fun anExhaustedPoolLeavesLaterTiersEmpty() {
        val draw = GapPlanSelection.draw(
            listOf(candidate(1, 5_000)),
            fullCapacityMinutes = 10_000,
            seed = 5L,
        )

        // The one game fits every share, so the widest tier claims it and the rest go without.
        assertEquals(1, draw.picks.count { !it.isEmpty })
        assertEquals(
            5_000,
            draw.picks.single { it.intensity == PlanIntensity.FULL }.game!!.remainingMinutes,
        )
        assertTrue(draw.picks.filter { it.intensity != PlanIntensity.FULL }.all { it.isEmpty })
    }

    @Test fun anEmptyPoolLeavesEveryTierEmptyRatherThanFailing() {
        val draw = GapPlanSelection.draw(emptyList(), fullCapacityMinutes = 10_000, seed = 1L)

        assertEquals(3, draw.picks.size)
        assertTrue(draw.picks.all { it.isEmpty })
        assertFalse("nothing to vary between", draw.canVary)
    }

    /** A tier nothing fits says so; the tiers that can still offer theirs. */
    @Test fun aTierNothingFitsIsEmptyWhileTheOthersStillOffer() {
        // 8,000 minutes fits Full (10,000) but neither Relaxed (7,000) nor Balanced (8,500)… the
        // second does fit, so only Relaxed is starved once Full and Balanced take the two games.
        val draw = GapPlanSelection.draw(
            listOf(candidate(1, 9_500), candidate(2, 8_000)),
            fullCapacityMinutes = 10_000,
            seed = 2L,
        )

        assertEquals(9_500, draw.picks.single { it.intensity == PlanIntensity.FULL }.game!!.remainingMinutes)
        assertEquals(8_000, draw.picks.single { it.intensity == PlanIntensity.BALANCED }.game!!.remainingMinutes)
        assertNull(draw.picks.single { it.intensity == PlanIntensity.RELAXED }.game)
    }

    /** A capacity of zero has no shares to fill, and must not divide by anything. */
    @Test fun zeroCapacityOffersNothing() {
        val draw = GapPlanSelection.draw(
            listOf(candidate(1, 60)),
            fullCapacityMinutes = 0,
            seed = 1L,
        )

        assertTrue(draw.picks.all { it.isEmpty })
        assertTrue(draw.picks.all { it.budgetMinutes == 0 })
    }

    /**
     * The stability half of the reroll property: a shown result must hold still while it is being
     * considered, which is what lets a player commit a month to it.
     */
    @Test fun identicalInputsAndSeedProduceIdenticalPicks() {
        val pool = (1L..40L).map { candidate(it, it.toInt() * 250) }

        val first = GapPlanSelection.draw(pool, 10_000, seed = 4_242L)
        val again = GapPlanSelection.draw(pool, 10_000, seed = 4_242L)

        assertEquals(
            first.picks.map { it.game?.appId },
            again.picks.map { it.game?.appId },
        )
    }

    /** And reproducibility must not depend on how the pool happened to be assembled. */
    @Test fun poolOrderDoesNotAffectTheDraw() {
        val pool = (1L..40L).map { candidate(it, it.toInt() * 250) }

        val ordered = GapPlanSelection.draw(pool, 10_000, seed = 99L)
        val shuffled = GapPlanSelection.draw(pool.reversed(), 10_000, seed = 99L)

        assertEquals(
            ordered.picks.map { it.game?.appId },
            shuffled.picks.map { it.game?.appId },
        )
    }

    /**
     * The other half: a new seed genuinely changes the answer.
     *
     * This is the property the previous design could not have. Membership was fully determined by
     * the inputs, so pressing rebuild with nothing changed could only return what was already on
     * screen — a control that promised a reroll and delivered a repaint.
     */
    @Test fun aDifferentSeedProducesADifferentSetOverAPoolThatAllowsOne() {
        val pool = (1L..40L).map { candidate(it, it.toInt() * 250) }
        val first = GapPlanSelection.draw(pool, 10_000, seed = 1L).picks.mapNotNull { it.game?.appId }

        val varied = (2L..40L).map { seed ->
            GapPlanSelection.draw(pool, 10_000, seed = seed).picks.mapNotNull { it.game?.appId }
        }

        assertTrue(
            "some seed must produce a set other than $first",
            varied.any { it != first },
        )
    }

    /** A pool with exactly one option per tier is forced, and the draw admits it. */
    @Test fun aPoolTooSmallToVaryReportsThatItCannot() {
        // Three widely separated lengths: each lands in exactly one tier's band, alone.
        val draw = GapPlanSelection.draw(
            listOf(candidate(1, 10_000), candidate(2, 8_400), candidate(3, 6_900)),
            fullCapacityMinutes = 10_000,
            seed = 1L,
        )

        assertFalse("each tier had exactly one option", draw.canVary)
        assertEquals(3, draw.picks.count { !it.isEmpty })
    }

    @Test fun aPoolWithAlternativesReportsThatItCanVary() {
        val pool = (1L..40L).map { candidate(it, it.toInt() * 250) }

        assertTrue(GapPlanSelection.draw(pool, 10_000, seed = 1L).canVary)
    }

    /**
     * Selection is uniform: two candidates equally near a tier's share are equally likely,
     * whatever facts are attached to them.
     *
     * Stated as a frequency check over many seeds rather than as a distribution test. The claim
     * being defended is not that the generator is well-distributed — that is the stdlib's job —
     * but that nothing in the candidate biases the draw, so both sides must actually be reached.
     */
    @Test fun aWellReviewedCandidateHoldsNoSelectionAdvantage() {
        val acclaimed = candidate(1, 10_000).copy(
            facts = listOf(GapPlanFact.Reviews("Overwhelmingly Positive", 999_000, 1_000_000)),
        )
        val panned = candidate(2, 10_000).copy(
            facts = listOf(GapPlanFact.Reviews("Overwhelmingly Negative", 200, 10_000)),
        )

        val chosen = (1L..200L).map { seed ->
            GapPlanSelection.draw(listOf(acclaimed, panned), 10_000, seed)
                .picks.single { it.intensity == PlanIntensity.FULL }.game!!.appId
        }

        assertTrue("the acclaimed game must be reachable", chosen.contains(1L))
        assertTrue("the panned game must be equally reachable", chosen.contains(2L))
        // And neither dominates: a ranked selection would have produced one value only.
        assertNotEquals(chosen.count { it == 1L }, chosen.size)
    }

    /**
     * The band is anchored on the longest *fitting* candidate, not on the share itself.
     *
     * A short backlog and a distant release date is an ordinary case, and a band anchored on the
     * share would return nothing for it — answering "no game fits" when several do.
     */
    @Test fun aLibraryFarShorterThanTheGapStillOffersItsLongestGames() {
        val draw = GapPlanSelection.draw(
            listOf(candidate(1, 600), candidate(2, 580), candidate(3, 560)),
            fullCapacityMinutes = 100_000,
            seed = 8L,
        )

        assertEquals(3, draw.picks.count { !it.isEmpty })
    }

    /** The band's width, stated directly, because the tier ordering depends on it. */
    @Test fun theNearnessBandIsTenPercentOfTheTiersShare() {
        assertEquals(10, GapPlanSelection.NEARNESS_BAND_PERCENT)

        val pool = listOf(
            candidate(1, 10_000),
            // Exactly on the band's lower edge, so included.
            candidate(2, 9_000),
            // One minute outside it.
            candidate(3, 8_999),
        )

        assertEquals(
            listOf(1L, 2L),
            GapPlanSelection.nearest(pool, budgetMinutes = 10_000).map { it.appId },
        )
    }
}
