package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.domain.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole engine, end to end, from a request and a library to three finalized picks.
 *
 * The claims being checked here are the ones the feature is sold on: three shares from one
 * capacity, picks decided by local state and a seed alone, facts presented rather than ranked, and
 * a library with no enrichment at all still producing usable, honestly-labelled suggestions.
 */
class GapPlanEngineTest {

    @Test fun threePicksAreProducedWithSeventyEightyFiveAndOneHundredPercentShares() {
        val snapshot = generate(games = (1L..8L).map { gapGame(it, mainStoryMinutes = 300) })

        val full = snapshot.capacity.fullCapacityMinutes
        assertEquals(
            listOf(PlanIntensity.RELAXED, PlanIntensity.BALANCED, PlanIntensity.FULL),
            snapshot.picks.map { it.intensity },
        )
        assertEquals(full * 70 / 100, snapshot.pick(PlanIntensity.RELAXED)!!.budgetMinutes)
        assertEquals(full * 85 / 100, snapshot.pick(PlanIntensity.BALANCED)!!.budgetMinutes)
        assertEquals(full, snapshot.pick(PlanIntensity.FULL)!!.budgetMinutes)
        snapshot.picks.forEach { pick ->
            assertTrue(pick.plannedMinutes <= pick.budgetMinutes)
            assertEquals(pick.budgetMinutes - pick.plannedMinutes, pick.unusedMinutes)
        }
    }

    @Test fun theThreePicksAreDistinctGames() {
        val snapshot = generate(
            games = (1L..12L).map { gapGame(it, mainStoryMinutes = it.toInt() * 400) },
        )

        val picked = snapshot.pickedAppIds
        assertEquals(3, picked.size)
        assertEquals(3, picked.toSet().size)
    }

    /**
     * The withheld share stays recoverable from the snapshot. Both figures are needed: a Relaxed
     * card showing only its own share would present that reduced figure as all the time the player
     * has.
     */
    @Test fun aRelaxedTierWithholdsCapacityThatRemainsVisibleOnTheSnapshot() {
        val snapshot = generate(games = (1L..8L).map { gapGame(it, mainStoryMinutes = 300) })
        val relaxed = snapshot.pick(PlanIntensity.RELAXED)!!

        assertTrue(relaxed.budgetMinutes < snapshot.capacity.fullCapacityMinutes)
        val withheld = snapshot.capacity.fullCapacityMinutes - relaxed.budgetMinutes
        assertTrue("the 30% held back must be recoverable from the snapshot", withheld > 0)
    }

    /**
     * The property the whole "live counts decorate only" decision rests on: the engine never sees
     * a network fact, so there is nothing for one to change. An offline run and an enriched run are
     * the same run, right down to which games are offered.
     */
    @Test fun picksAreAFunctionOfLocalStateAndTheSeedAlone() {
        val games = (1L..12L).map { gapGame(it, mainStoryMinutes = 200 * it.toInt()) }
        val enriched = generate(
            games = games,
            reviews = games.associate {
                it.appId to GameReviewSummary.Available("Very Positive", 900, 100, 1_000)
            },
            seed = 31L,
        )
        val bare = generate(games = games, reviews = emptyMap(), seed = 31L)

        // Reviews no longer participate in selection at all, so the two runs must agree on every
        // pick. They differ only in what the cards can say.
        assertEquals(enriched.pickedAppIds, bare.pickedAppIds)
        assertTrue(
            enriched.picks.mapNotNull { it.game }
                .all { game -> game.facts.any { it is GapPlanFact.Reviews } },
        )
        assertTrue(
            bare.picks.mapNotNull { it.game }
                .all { game -> game.facts.none { it is GapPlanFact.Reviews } },
        )
    }

    /** The same inputs and the same seed, twice. This is what lets a shown result hold still. */
    @Test fun identicalInputsAndSeedReproduceTheSamePicks() {
        val games = (1L..20L).map { gapGame(it, mainStoryMinutes = it.toInt() * 200) }

        assertEquals(
            generate(games = games, seed = 777L).pickedAppIds,
            generate(games = games, seed = 777L).pickedAppIds,
        )
    }

    /** And the seed travels with the result, so the generation can be reproduced from it. */
    @Test fun theSeedIsRetainedOnTheSnapshot() {
        assertEquals(4_242L, generate(games = listOf(gapGame(1)), seed = 4_242L).seed)
    }

    @Test fun anExactFallbackRetainsEnoughStateToReplayItsPicks() {
        val games = (1L..20L).map { gapGame(it, mainStoryMinutes = it.toInt() * 200) }
        val request = gapRequest()
        val inputs = inputs(games)
        val shown = GapPlanEngine.generate(request, inputs, seed = 1L).getOrThrow()
        val fallback = GapPlanEngine.generateDifferentFrom(
            request = request,
            inputs = inputs,
            seed = 2L,
            previousPickedAppIds = shown.pickedAppIds,
        )?.getOrThrow() ?: error("the fixture must have a reachable alternate")

        assertNotEquals(shown.pickedAppIds, fallback.pickedAppIds)
        assertEquals(shown.pickedAppIds, fallback.rerollExclusionAppIds)
        assertEquals(fallback, GapPlanEngine.replay(fallback, inputs).getOrThrow())
    }

    /**
     * The degradation the design accepts and discloses: with no reviews and no genres the cards are
     * sparse. Selection is unaffected — nothing is ranked — so the suggestions are exactly as valid
     * as they would be for a fully enriched library.
     */
    @Test fun aLibraryWithNoReviewsOrGenresStillOffersPicksAndSaysWhatIsMissing() {
        val snapshot = generate(
            games = (1L..5L).map { gapGame(it, mainStoryMinutes = 300, genreIds = emptyList()) },
            reviews = emptyMap(),
        )

        val full = snapshot.pick(PlanIntensity.FULL)!!
        assertTrue(full.game != null)
        assertEquals(0, snapshot.coverage.withCachedReviews)
        assertEquals(0, snapshot.coverage.withKnownGenres)
        snapshot.picks.mapNotNull { it.game }.forEach { game ->
            // Nothing claims a rating, a genre, or a preference it does not have.
            assertTrue(game.genreLabels.isEmpty())
            assertTrue(game.facts.none { it is GapPlanFact.Reviews })
            assertTrue(game.facts.none { it is GapPlanFact.GenreAffinity })
        }
    }

    /** An enriched pick carries Steam's own words and volume, and its Store genres as labels. */
    @Test fun anEnrichedPickCarriesItsReviewSummaryAndItsGenreLabels() {
        val snapshot = generate(
            games = listOf(gapGame(1, mainStoryMinutes = 600, genreIds = listOf("1"))),
            reviews = mapOf(
                1L to GameReviewSummary.Available("Very Positive", 90, 10, 100),
            ),
        )

        val game = snapshot.pick(PlanIntensity.FULL)!!.game!!
        assertEquals(listOf("Action"), game.genreLabels)
        assertTrue(game.facts.contains(GapPlanFact.Reviews("Very Positive", 90, 100)))
    }

    @Test fun aFamilySharedSuggestionRemainsIdentifiableAndCountsOnlyItsRemainingTime() {
        val snapshot = generate(
            games = listOf(
                gapGame(1, source = GameSource.FAMILY_SHARED, manualSharedMinutes = 60, mainStoryMinutes = 600),
            ),
        )

        val game = snapshot.pick(PlanIntensity.FULL)!!.game!!
        assertEquals(GameSource.FAMILY_SHARED, game.source)
        assertEquals(540, game.remainingMinutes)
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
        assertEquals(listOf(1L), snapshot.pickedAppIds)
    }

    @Test fun anEmptyLibraryProducesThreeEmptyPicksRatherThanAFailure() {
        val snapshot = generate(games = emptyList())

        assertEquals(3, snapshot.picks.size)
        assertTrue(snapshot.picks.all { it.isEmpty })
        assertFalse(snapshot.canVary)
        assertEquals(0, snapshot.coverage.visibleGames)
    }

    /** A learning profile fails the request rather than quietly inventing a capacity. */
    @Test fun generationFailsWhenALearningProfileHasNoManualBudget() {
        val result = GapPlanEngine.generate(
            gapRequest(),
            inputs(games = listOf(gapGame(1))).copy(paceProfile = learningPace()),
            seed = 1L,
        )

        assertEquals(
            GapPlanRequestError.MISSING_MANUAL_BUDGET,
            (result.exceptionOrNull() as GapPlanRequestException).error,
        )
    }

    @Test fun aManualBudgetProducesSuggestionsLabelledAsManuallyBudgeted() {
        val result = GapPlanEngine.generate(
            gapRequest(manualTotalHours = 20),
            inputs(games = (1L..5L).map { gapGame(it, mainStoryMinutes = 300) })
                .copy(paceProfile = learningPace()),
            seed = 1L,
        ).getOrThrow()

        assertEquals(CapacityProvenance.MANUAL, result.capacity.provenance)
        assertEquals(1_200, result.capacity.fullCapacityMinutes)
        assertTrue(result.pick(PlanIntensity.FULL)!!.game != null)
    }

    /**
     * The date is read at generation time, not captured earlier. A process that crossed local
     * midnight with a stale date would forecast one extra day of capacity it no longer has.
     */
    @Test fun theForecastWindowFollowsTheDatePassedAtGenerationTime() {
        val request = gapRequest(targetDate = TODAY.plusDays(30))
        val before = GapPlanEngine.generate(request, inputs(listOf(gapGame(1))), seed = 1L).getOrThrow()
        val afterRollover = GapPlanEngine.generate(
            request,
            inputs(listOf(gapGame(1))).copy(today = TODAY.plusDays(1)),
            seed = 1L,
        ).getOrThrow()

        assertEquals(TODAY.plusDays(1), before.capacity.startDate)
        assertEquals(TODAY.plusDays(2), afterRollover.capacity.startDate)
        assertTrue(
            "one fewer day in the window must forecast less capacity",
            afterRollover.capacity.fullCapacityMinutes < before.capacity.fullCapacityMinutes,
        )
    }

    /** The reroll signal survives out of the engine, so the surface can explain a no-op rebuild. */
    @Test fun theSnapshotReportsWhetherThePoolCouldHaveVaried() {
        val varied = generate(games = (1L..30L).map { gapGame(it, mainStoryMinutes = it.toInt() * 200) })
        assertTrue(varied.canVary)

        assertFalse(generate(games = listOf(gapGame(1, mainStoryMinutes = 600))).canVary)
    }

    private fun generate(
        games: List<GapPlanGame>,
        reviews: Map<Long, GameReviewSummary> = games.associate {
            it.appId to GameReviewSummary.Available("Positive", 90, 10, 100)
        },
        seed: Long = 1L,
    ): GapPlanSnapshot = GapPlanEngine
        .generate(gapRequest(), inputs(games, reviews), seed)
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
