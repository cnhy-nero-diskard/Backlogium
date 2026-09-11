package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.domain.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole engine, end to end, from a request and a library to three finalized variants.
 *
 * The claims being checked here are the ones the feature is sold on: three different budgets from
 * one capacity, membership decided by local state alone, explanations made of facts, and a library
 * with no enrichment at all still producing a usable, honestly-labelled plan.
 */
class GapPlanEngineTest {

    @Test fun threeVariantsAreProducedWithSeventyEightyFiveAndOneHundredPercentBudgets() {
        val snapshot = generate(games = (1L..8L).map { gapGame(it, mainStoryMinutes = 300) })

        val full = snapshot.capacity.fullCapacityMinutes
        assertEquals(
            listOf(PlanIntensity.RELAXED, PlanIntensity.BALANCED, PlanIntensity.FULL),
            snapshot.variants.map { it.intensity },
        )
        assertEquals(full * 70 / 100, snapshot.variant(PlanIntensity.RELAXED)!!.budgetMinutes)
        assertEquals(full * 85 / 100, snapshot.variant(PlanIntensity.BALANCED)!!.budgetMinutes)
        assertEquals(full, snapshot.variant(PlanIntensity.FULL)!!.budgetMinutes)
        snapshot.variants.forEach { variant ->
            assertTrue(variant.plannedMinutes <= variant.budgetMinutes)
            assertTrue(variant.members.size <= GapPlanVariant.MAX_MEMBERS)
            assertEquals(variant.budgetMinutes - variant.plannedMinutes, variant.reserveMinutes)
        }
    }

    /**
     * The reserve is measured against the variant's own budget, while the request's full capacity
     * stays on the snapshot. Both are needed: a Relaxed card showing only its own small reserve
     * would present its reduced budget as all the time the player has.
     */
    @Test fun aRelaxedVariantWithholdsCapacityThatRemainsVisibleOnTheSnapshot() {
        val snapshot = generate(games = (1L..8L).map { gapGame(it, mainStoryMinutes = 300) })
        val relaxed = snapshot.variant(PlanIntensity.RELAXED)!!

        assertTrue(relaxed.budgetMinutes < snapshot.capacity.fullCapacityMinutes)
        val withheld = snapshot.capacity.fullCapacityMinutes - relaxed.budgetMinutes
        assertTrue("the 30% held back must be recoverable from the snapshot", withheld > 0)
    }

    /**
     * The property the whole "live counts decorate only" decision rests on: the engine never sees
     * a network fact, so there is nothing for one to change. An offline run and an enriched run are
     * the same run.
     */
    @Test fun membershipIsAFunctionOfLocalStateAlone() {
        val games = (1L..6L).map { gapGame(it, mainStoryMinutes = 200 * it.toInt()) }
        val enriched = generate(
            games = games,
            reviews = games.associate {
                it.appId to GameReviewSummary.Available("Very Positive", 900, 100, 1_000)
            },
        )
        val bare = generate(games = games, reviews = emptyMap())

        // Reviews legitimately change ranking, so membership may differ — but each run is
        // reproducible from its own inputs, with no residue from the other.
        assertEquals(
            enriched.variants.map { it.members.map(GapPlanCandidate::appId) },
            generate(
                games = games,
                reviews = games.associate {
                    it.appId to GameReviewSummary.Available("Very Positive", 900, 100, 1_000)
                },
            ).variants.map { it.members.map(GapPlanCandidate::appId) },
        )
        assertEquals(
            bare.variants.map { it.members.map(GapPlanCandidate::appId) },
            generate(games = games, reviews = emptyMap())
                .variants.map { it.members.map(GapPlanCandidate::appId) },
        )
    }

    /**
     * The degradation the design accepts and discloses: with no reviews and no genres, 80% of
     * candidate quality is a pair of constants and ranking falls back to progress and fit. The
     * plan still works, and the coverage figures say what was missing.
     */
    @Test fun aLibraryWithNoReviewsOrGenresStillRanksAndExplainsItself() {
        val snapshot = generate(
            games = (1L..5L).map { gapGame(it, mainStoryMinutes = 300, genreIds = emptyList()) },
            reviews = emptyMap(),
        )

        val full = snapshot.variant(PlanIntensity.FULL)!!
        assertTrue(full.members.isNotEmpty())
        assertEquals(0, snapshot.coverage.withCachedReviews)
        assertEquals(0, snapshot.coverage.withKnownGenres)
        full.members.forEach { member ->
            assertEquals(ReviewQuality.NEUTRAL, member.reviewQuality, 0.0)
            assertEquals(GenreAffinity.NEUTRAL, member.genreAffinity, 0.0)
            // Fit is always stated, so nothing appears recommended for no reason at all.
            assertTrue(member.reasons.any { it is GapPlanReason.Fit })
            // And nothing claims a rating or a preference it does not have.
            assertTrue(member.reasons.none { it is GapPlanReason.ReviewQuality })
            assertTrue(member.reasons.none { it is GapPlanReason.GenreAffinity })
        }
    }

    @Test fun aFamilySharedRecommendationIsAlwaysLabelled() {
        val snapshot = generate(
            games = listOf(
                gapGame(1, source = GameSource.FAMILY_SHARED, manualSharedMinutes = 60, mainStoryMinutes = 600),
            ),
        )

        val member = snapshot.variant(PlanIntensity.FULL)!!.members.single()
        assertEquals(GameSource.FAMILY_SHARED, member.source)
        assertTrue(member.reasons.contains(GapPlanReason.FamilyShared))
        assertEquals(540, member.remainingMinutes)
    }

    @Test fun incompleteHltbCoverageIsReportedRatherThanTreatedAsShort() {
        val snapshot = generate(
            games = listOf(
                gapGame(1, mainStoryMinutes = 600),
                gapGame(2, mainStoryMinutes = null),
                gapGame(3, mainStoryMinutes = null),
            ),
        )

        assertFalse(snapshot.coverage.isComplete)
        assertEquals(2, snapshot.coverage.missingSelectedEstimate)
        assertEquals(listOf(1L), snapshot.variant(PlanIntensity.FULL)!!.members.map { it.appId })
    }

    @Test fun anEmptyLibraryProducesThreeEmptyVariantsRatherThanAFailure() {
        val snapshot = generate(games = emptyList())

        assertEquals(3, snapshot.variants.size)
        assertTrue(snapshot.variants.all { it.isEmpty })
        assertTrue(snapshot.eligiblePool.isEmpty())
        assertEquals(0, snapshot.coverage.visibleGames)
    }

    /** A learning profile fails the request rather than quietly inventing a capacity. */
    @Test fun generationFailsWhenALearningProfileHasNoManualBudget() {
        val result = GapPlanEngine.generate(
            gapRequest(),
            inputs(games = listOf(gapGame(1))).copy(paceProfile = learningPace()),
        )

        assertEquals(
            GapPlanRequestError.MISSING_MANUAL_BUDGET,
            (result.exceptionOrNull() as GapPlanRequestException).error,
        )
    }

    @Test fun aManualBudgetProducesPlansLabelledAsManuallyBudgeted() {
        val result = GapPlanEngine.generate(
            gapRequest(manualTotalHours = 20),
            inputs(games = (1L..5L).map { gapGame(it, mainStoryMinutes = 300) })
                .copy(paceProfile = learningPace()),
        ).getOrThrow()

        assertEquals(CapacityProvenance.MANUAL, result.capacity.provenance)
        assertEquals(1_200, result.capacity.fullCapacityMinutes)
        assertTrue(result.variant(PlanIntensity.FULL)!!.members.isNotEmpty())
    }

    /**
     * The date is read at generation time, not captured earlier. A process that crossed local
     * midnight with a stale date would forecast one extra day of capacity it no longer has.
     */
    @Test fun theForecastWindowFollowsTheDatePassedAtGenerationTime() {
        val request = gapRequest(targetDate = TODAY.plusDays(30))
        val before = GapPlanEngine.generate(request, inputs(listOf(gapGame(1)))).getOrThrow()
        val afterRollover = GapPlanEngine.generate(
            request,
            inputs(listOf(gapGame(1))).copy(today = TODAY.plusDays(1)),
        ).getOrThrow()

        assertEquals(TODAY.plusDays(1), before.capacity.startDate)
        assertEquals(TODAY.plusDays(2), afterRollover.capacity.startDate)
        assertTrue(
            "one fewer day in the window must forecast less capacity",
            afterRollover.capacity.fullCapacityMinutes < before.capacity.fullCapacityMinutes,
        )
    }

    /** The retained pool is what edits are validated against, so it travels with the snapshot. */
    @Test fun theEligiblePoolIsRetainedInADeterministicOrder() {
        val snapshot = generate(games = (1L..6L).map { gapGame(it, mainStoryMinutes = 300) })

        assertEquals(6, snapshot.eligiblePool.size)
        assertEquals(
            snapshot.eligiblePool.sortedWith(GapPlanComposer.PROCESSING_ORDER),
            snapshot.eligiblePool,
        )
    }

    private fun generate(
        games: List<GapPlanGame>,
        reviews: Map<Long, GameReviewSummary> = games.associate {
            it.appId to GameReviewSummary.Available("Positive", 90, 10, 100)
        },
    ): GapPlanSnapshot = GapPlanEngine
        .generate(gapRequest(), inputs(games, reviews))
        .getOrThrow()

    private fun inputs(
        games: List<GapPlanGame>,
        reviews: Map<Long, GameReviewSummary> = emptyMap(),
    ) = GapPlanInputs(
        games = games,
        playedDates = emptyList(),
        reviewsByAppId = reviews,
        genreLabels = mapOf("1" to "Action"),
        paceProfile = reliablePace(),
        today = TODAY,
    )
}
