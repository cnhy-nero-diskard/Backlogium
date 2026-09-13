package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three derivations that used to rank candidates, and now only describe them.
 *
 * Each had one specific way of lying while it was choosing: reviews could let a nine-vote sample
 * outrank two million, genre affinity could mistake one unattended marathon for a taste, and
 * momentum could turn every plan into backlog cleanup. Demoting them to facts does not make those
 * distortions disappear — it makes them *visible*, which is the whole point. A sentence the player
 * can read and dismiss is a different thing from a weight they cannot see.
 *
 * What these tests pin, therefore, is not a score. It is that each derivation still produces a
 * checkable fact when it has one, produces nothing at all when it does not, and cannot reach the
 * selection either way.
 */
class GapPlanFactsTest {

    // --- Reviews -----------------------------------------------------------------------------

    /**
     * A missing rating produces **no fact at all**, not a placeholder.
     *
     * This replaces a declared neutral constant. The neutral existed only to keep an un-enriched
     * game comparable inside a weighted sum; with nothing being compared, a chosen 0.5 would be a
     * claim about the game that the app has no basis for. Silence is the honest form of an absence.
     */
    @Test fun aMissingOrUnavailableRatingProducesNoFact() {
        assertNull(ReviewQuality.factFor(null))
        assertNull(ReviewQuality.factFor(GameReviewSummary.Unavailable))
    }

    @Test fun anAvailableRatingCarriesSteamsOwnWordsAndVolume() {
        val fact = ReviewQuality.factFor(
            GameReviewSummary.Available("Very Positive", positive = 90, negative = 10, total = 100),
        )

        assertEquals(GapPlanFact.Reviews("Very Positive", 90, 100), fact)
    }

    /**
     * A poor rating is stated as plainly as a good one, and neither is converted into anything
     * comparable. There is no number here for a selection to sort on.
     */
    @Test fun aPoorRatingIsStatedRatherThanScored() {
        val fact = ReviewQuality.factFor(
            GameReviewSummary.Available("Overwhelmingly Negative", 200, 9_800, 10_000),
        )!!

        assertEquals("Overwhelmingly Negative", fact.description)
        assertEquals(10_000, fact.total)
    }

    // --- Genre affinity ----------------------------------------------------------------------

    /** Recency decay: the same game played more recently weighs more. */
    @Test fun genreWeightsFavourMoreRecentPlay() {
        val recent = GenreAffinity.weights(
            playedDates = listOf(PlayedDate(1, TODAY.minusDays(1)), PlayedDate(2, TODAY.minusDays(50))),
            genreIdsByApp = mapOf(1L to listOf("action"), 2L to listOf("strategy")),
            today = TODAY,
        )

        assertEquals(1.0, recent.getValue("action"), 1e-9)
        assertTrue(recent.getValue("strategy") < 0.5)
    }

    /**
     * Active dates, not minutes. One unattended weekend marathon must not define the player's
     * taste for the next 56 days, and repeated presence must still count.
     */
    @Test fun aSingleMarathonCannotOutweighRepeatedPlayOfAnotherGenre() {
        val weights = GenreAffinity.weights(
            // The marathon game is played once; the other is played on five separate days.
            playedDates = listOf(PlayedDate(1, TODAY.minusDays(1))) +
                (1L..5L).map { PlayedDate(2, TODAY.minusDays(it)) },
            genreIdsByApp = mapOf(1L to listOf("marathon"), 2L to listOf("varied")),
            today = TODAY,
        )

        assertTrue(weights.getValue("varied") > weights.getValue("marathon"))
        assertEquals(1.0, weights.getValue("varied"), 1e-9)
    }

    /** The same game played twice on one date counts once — the per-date cap. */
    @Test fun oneGameContributesAtMostOncePerDatePerGenre() {
        val twice = GenreAffinity.weights(
            playedDates = listOf(PlayedDate(1, TODAY.minusDays(1)), PlayedDate(1, TODAY.minusDays(1))),
            genreIdsByApp = mapOf(1L to listOf("action")),
            today = TODAY,
        )
        val once = GenreAffinity.weights(
            playedDates = listOf(PlayedDate(1, TODAY.minusDays(1))),
            genreIdsByApp = mapOf(1L to listOf("action")),
            today = TODAY,
        )

        assertEquals(once, twice)
    }

    @Test fun datesOutsideTheWindowAreIgnored() {
        val weights = GenreAffinity.weights(
            playedDates = listOf(
                PlayedDate(1, TODAY.minusDays(GenreAffinity.LOOKBACK_DAYS + 1)),
                // Today itself is excluded: the day is not complete yet.
                PlayedDate(2, TODAY),
            ),
            genreIdsByApp = mapOf(1L to listOf("old"), 2L to listOf("today")),
            today = TODAY,
        )

        assertTrue(weights.isEmpty())
    }

    @Test fun noHistoryOrUnknownGenresProduceNoFact() {
        assertNull(GenreAffinity.factFor(listOf("action"), emptyMap(), emptyMap()))
        assertNull(GenreAffinity.factFor(emptyList(), mapOf("action" to 1.0), emptyMap()))
        // A genre the player has simply never played says nothing either way, and says it silently.
        assertNull(GenreAffinity.factFor(listOf("puzzle"), mapOf("action" to 1.0), emptyMap()))
    }

    /** The fact names the strongest matching genre, in the label the rest of the app shows. */
    @Test fun theAffinityFactNamesTheStrongestMatchedGenre() {
        val fact = GenreAffinity.factFor(
            genreIds = listOf("action", "puzzle", "unknown"),
            weights = mapOf("action" to 1.0, "puzzle" to 0.4),
            genreLabels = mapOf("action" to "Action", "puzzle" to "Puzzle"),
        )

        assertEquals(GapPlanFact.GenreAffinity("Action"), fact)
    }

    /** An unlabelled genre is unnameable, so it produces nothing rather than an id. */
    @Test fun anUnlabelledGenreProducesNoFact() {
        assertNull(GenreAffinity.factFor(listOf("action"), mapOf("action" to 1.0), emptyMap()))
    }

    // --- Progress ----------------------------------------------------------------------------

    @Test fun anUnplayedGameSaysNothingAboutProgress() {
        assertNull(CompletionMomentum.factFor(playedMinutes = 0, estimateMinutes = 600))
    }

    @Test fun aStartedGameStatesThePlayedAndTotalMinutes() {
        assertEquals(
            GapPlanFact.Progress(300, 600),
            CompletionMomentum.factFor(300, 600),
        )
    }

    @Test fun aGameWithNoEstimateSaysNothingAboutProgress() {
        assertNull(CompletionMomentum.factFor(300, 0))
    }

    // --- The reversal itself -----------------------------------------------------------------

    /**
     * The claim the whole reversal rests on: these facts cannot reach the selection.
     *
     * Two candidates identical in length but opposite in every presented fact, drawn many times.
     * A ranked selection would return one of them every time; an unweighted one reaches both. This
     * is the same property [GapPlanSelectionTest] checks for reviews, asserted here over the full
     * fact set so no single signal can be demoted in name only.
     */
    @Test fun noPresentedFactMakesACandidateMoreLikelyToBePicked() {
        val everyFact = candidate(1, 10_000).copy(
            facts = listOf(
                GapPlanFact.Reviews("Overwhelmingly Positive", 999_000, 1_000_000),
                GapPlanFact.GenreAffinity("Action"),
                GapPlanFact.Progress(9_000, 19_000),
            ),
        )
        val noFacts = candidate(2, 10_000)

        val chosen = (1L..200L).map { seed ->
            GapPlanSelection.draw(listOf(everyFact, noFacts), 10_000, seed)
                .picks.single { it.intensity == PlanIntensity.FULL }.game!!.appId
        }

        assertTrue("the fact-rich candidate must be reachable", chosen.contains(1L))
        assertTrue("the fact-free candidate must be equally reachable", chosen.contains(2L))
    }

    /**
     * And a candidate carries no comparable number at all.
     *
     * The weighted `candidateQuality` composite and its three weights are gone, not merely unused:
     * there is nothing on a candidate a future change could start sorting by without first
     * reintroducing the thing this design removed.
     */
    @Test fun aCandidateExposesNoScoreToSortBy() {
        val fields = GapPlanCandidate::class.java.declaredFields.map { it.name }

        assertFalse(fields.any { it.contains("quality", ignoreCase = true) })
        assertFalse(fields.any { it.contains("affinity", ignoreCase = true) })
        assertFalse(fields.any { it.contains("momentum", ignoreCase = true) })
        assertFalse(fields.any { it.contains("score", ignoreCase = true) })
    }
}
