package com.example.backlogium.domain.gapplan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bundle composition, where the pruning rule decides the answer rather than merely speeding it up.
 *
 * The objective is non-monotone on purpose — mean quality rewards small sets, utilization rewards
 * large ones — so a partial combination's value bounds nothing about its extensions. That makes
 * the frontier width, the processing order, and the tie-break chain part of the contract. These
 * tests pin all three.
 */
class GapPlanComposerTest {

    @Test fun aBundleNeverExceedsItsBudget() {
        val chosen = GapPlanComposer.compose(
            candidates = (1L..10L).map { candidate(it, remainingMinutes = 400) },
            budgetMinutes = 1_000,
        )

        assertTrue(chosen.sumOf { it.remainingMinutes } <= 1_000)
        assertEquals(2, chosen.size)
    }

    @Test fun aBundleNeverExceedsFiveGamesHoweverManyFit() {
        val chosen = GapPlanComposer.compose(
            // Forty short games and a budget that fits eight of them: filling it is worth
            // something, so the cap is what stops the plan, not the arithmetic.
            candidates = (1L..40L).map { candidate(it, remainingMinutes = 10) },
            budgetMinutes = 80,
        )

        assertEquals(GapPlanVariant.MAX_MEMBERS, chosen.size)
        assertEquals(chosen.map { it.appId }.distinct().size, chosen.size)
    }

    /**
     * A consequence of scoring a one-game bundle's diversity as neutral, recorded deliberately
     * rather than discovered later: when the budget dwarfs everything that fits, utilization is
     * negligible either way, and one game beats five identical-genre ones on the diversity term
     * alone.
     *
     * This is the intended reading of "unknown or unpairable is neutral". Scoring a lone game as
     * maximally diverse would claim variety a single game cannot have, and scoring it zero would
     * make a one-game plan permanently the worst option even when nothing else fits.
     */
    @Test fun whenTheBudgetDwarfsEveryCandidateASingleGameCanOutscoreFiveOfOneGenre() {
        val chosen = GapPlanComposer.compose(
            candidates = (1L..40L).map { candidate(it, remainingMinutes = 10, genreIds = listOf("a")) },
            budgetMinutes = 10_000,
        )

        assertEquals(1, chosen.size)
        // Give those same games distinct genres and the plan fills up instead.
        val varied = GapPlanComposer.compose(
            candidates = (1L..40L).map { candidate(it, remainingMinutes = 10, genreIds = listOf("g$it")) },
            budgetMinutes = 10_000,
        )
        assertEquals(GapPlanVariant.MAX_MEMBERS, varied.size)
    }

    /** One game is a real plan; an arbitrary minimum count would be worse than a short answer. */
    @Test fun oneGameIsAValidPlan() {
        val chosen = GapPlanComposer.compose(
            candidates = listOf(candidate(1, remainingMinutes = 900)),
            budgetMinutes = 1_000,
        )

        assertEquals(listOf(1L), chosen.map { it.appId })
    }

    /** Nothing fitting is an answer too: the variant says so rather than stretching its budget. */
    @Test fun anEmptyPlanIsProducedWhenNothingFits() {
        assertEquals(
            emptyList<GapPlanCandidate>(),
            GapPlanComposer.compose(listOf(candidate(1, 5_000)), budgetMinutes = 1_000),
        )
        assertEquals(
            emptyList<GapPlanCandidate>(),
            GapPlanComposer.compose(emptyList(), budgetMinutes = 1_000),
        )
        assertEquals(
            emptyList<GapPlanCandidate>(),
            GapPlanComposer.compose(listOf(candidate(1, 10)), budgetMinutes = 0),
        )
    }

    /**
     * Adversarial packing: a single high-quality candidate consumes the whole budget, while three
     * slightly weaker ones together fill it and score higher on utilization and variety. A greedy
     * next-best walk takes the first and stops, which is exactly why it was rejected.
     */
    @Test fun aBetterCombinationBeatsTheSingleBestCandidate() {
        val chosen = GapPlanComposer.compose(
            candidates = listOf(
                candidate(1, remainingMinutes = 1_000, quality = 0.90, genreIds = listOf("a")),
                candidate(2, remainingMinutes = 340, quality = 0.86, genreIds = listOf("a")),
                candidate(3, remainingMinutes = 330, quality = 0.86, genreIds = listOf("b")),
                candidate(4, remainingMinutes = 330, quality = 0.86, genreIds = listOf("c")),
            ),
            budgetMinutes = 1_000,
        )

        assertEquals(listOf(2L, 3L, 4L), chosen.map { it.appId }.sorted())
    }

    /**
     * Between two otherwise identical combinations, the one covering more genres wins. Diversity
     * is the smallest term, so it decides ties rather than overriding quality or fit.
     */
    @Test fun aMoreVariedCombinationIsPreferredWhenEverythingElseIsEqual() {
        val chosen = GapPlanComposer.compose(
            candidates = listOf(
                candidate(1, remainingMinutes = 500, quality = 0.8, genreIds = listOf("a")),
                candidate(2, remainingMinutes = 500, quality = 0.8, genreIds = listOf("a")),
                candidate(3, remainingMinutes = 500, quality = 0.8, genreIds = listOf("b")),
            ),
            budgetMinutes = 1_000,
        )

        assertEquals(2, chosen.size)
        // Whichever pair is chosen, it must span both genres rather than doubling up on "a".
        assertEquals(setOf("a", "b"), chosen.flatMap { it.genreIds }.toSet())
    }

    /** An unknown genre is not evidence of variety, so it neither rewards nor punishes. */
    @Test fun unknownGenresAreNeutralRatherThanMaximallyDiverse() {
        assertEquals(
            GapPlanComposer.NEUTRAL_DIVERSITY,
            GapPlanComposer.diversity(
                listOf(candidate(1, 10, genreIds = emptyList()), candidate(2, 10, genreIds = emptyList())),
            ),
            1e-9,
        )
        // A single game has no pair, so its diversity is neutral rather than perfect.
        assertEquals(
            GapPlanComposer.NEUTRAL_DIVERSITY,
            GapPlanComposer.diversity(listOf(candidate(1, 10))),
            1e-9,
        )
        // Two fully distinct known genres are maximally diverse; two identical ones are not.
        assertEquals(
            1.0,
            GapPlanComposer.diversity(
                listOf(candidate(1, 10, genreIds = listOf("a")), candidate(2, 10, genreIds = listOf("b"))),
            ),
            1e-9,
        )
        assertEquals(
            0.0,
            GapPlanComposer.diversity(
                listOf(candidate(1, 10, genreIds = listOf("a")), candidate(2, 10, genreIds = listOf("a"))),
            ),
            1e-9,
        )
    }

    /** Utilization is measured against the variant's own budget, never the request's capacity. */
    @Test fun utilizationIsRelativeToThisVariantsBudget() {
        assertEquals(0.5, GapPlanComposer.utilization(500, 1_000), 1e-9)
        assertEquals(1.0, GapPlanComposer.utilization(1_000, 1_000), 1e-9)
        // Clamped: an over-budget bundle is rejected before scoring, but the term cannot exceed 1.
        assertEquals(1.0, GapPlanComposer.utilization(2_000, 1_000), 1e-9)
        assertEquals(0.0, GapPlanComposer.utilization(500, 0), 1e-9)
    }

    /**
     * When every derived value ties, app id is the last word. Without a total order the beam would
     * keep whichever pair the pool happened to be built in, and "identical inputs produce identical
     * plans" would be luck rather than a property.
     */
    @Test fun appIdBreaksAFullyTiedChoice() {
        val chosen = GapPlanComposer.compose(
            candidates = listOf(
                candidate(9, remainingMinutes = 600, quality = 0.5),
                candidate(3, remainingMinutes = 600, quality = 0.5),
                candidate(7, remainingMinutes = 600, quality = 0.5),
            ),
            budgetMinutes = 1_200,
        )

        assertEquals(listOf(3L, 7L), chosen.map { it.appId }.sorted())
    }

    /**
     * The reproducibility property, stated as a test: shuffling the pool cannot change the answer,
     * because the composer imposes its own total order before it does anything else.
     */
    @Test fun repeatRunsOverAShuffledPoolProduceIdenticalMembership() {
        val pool = (1L..30L).map { appId ->
            candidate(
                appId,
                remainingMinutes = (100 + (appId * 37 % 500)).toInt(),
                quality = ((appId * 13) % 100) / 100.0,
                genreIds = listOf("g${appId % 4}"),
            )
        }
        val expected = GapPlanComposer.compose(pool, budgetMinutes = 1_500).map { it.appId }

        repeat(12) { seed ->
            val shuffled = pool.shuffled(kotlin.random.Random(seed.toLong()))
            assertEquals(expected, GapPlanComposer.compose(shuffled, budgetMinutes = 1_500).map { it.appId })
        }
        assertTrue(expected.isNotEmpty())
    }

    /** A larger budget is allowed to reach a different, better answer — determinism is not inertia. */
    @Test fun aDifferentBudgetMayProduceADifferentPlan() {
        val pool = listOf(
            candidate(1, remainingMinutes = 900, quality = 0.90, genreIds = listOf("a")),
            candidate(2, remainingMinutes = 800, quality = 0.85, genreIds = listOf("b")),
        )

        assertEquals(listOf(1L), GapPlanComposer.compose(pool, budgetMinutes = 900).map { it.appId })
        assertEquals(
            listOf(1L, 2L),
            GapPlanComposer.compose(pool, budgetMinutes = 1_700).map { it.appId }.sorted(),
        )
        assertNotEquals(
            GapPlanComposer.compose(pool, budgetMinutes = 900).map { it.appId },
            GapPlanComposer.compose(pool, budgetMinutes = 1_700).map { it.appId },
        )
    }

    @Test fun theFrontierWidthAndWeightsAreTheDeclaredOnes() {
        assertEquals(32, GapPlanComposer.FRONTIER_WIDTH)
        assertEquals(0.60, GapPlanComposer.QUALITY_WEIGHT, 0.0)
        assertEquals(0.30, GapPlanComposer.UTILIZATION_WEIGHT, 0.0)
        assertEquals(0.10, GapPlanComposer.DIVERSITY_WEIGHT, 0.0)
        assertEquals(
            1.0,
            GapPlanComposer.QUALITY_WEIGHT + GapPlanComposer.UTILIZATION_WEIGHT +
                GapPlanComposer.DIVERSITY_WEIGHT,
            1e-9,
        )
    }
}
