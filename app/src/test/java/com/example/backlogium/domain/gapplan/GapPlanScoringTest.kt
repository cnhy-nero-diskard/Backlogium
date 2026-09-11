package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three candidate-quality components, each of which has one specific way of lying.
 *
 * Reviews can let a nine-vote sample outrank two million. Genre affinity can mistake one
 * unattended marathon for a taste. Momentum can turn every plan into backlog cleanup. And all
 * three can fabricate a judgement out of an absence, which is the failure the neutral constants
 * exist to prevent.
 */
class GapPlanScoringTest {

    @Test fun anEstablishedRatingOutranksATinyPerfectSample() {
        val tinyPerfect = ReviewQuality.wilsonLowerBound(positive = 9, negative = 0)
        val establishedGreat = ReviewQuality.wilsonLowerBound(positive = 950_000, negative = 50_000)

        assertTrue(
            "a 9/9 sample must not outrank 95% across a million reviews",
            establishedGreat > tinyPerfect,
        )
        // And the raw percentages say the opposite, which is exactly why the adjustment exists.
        assertTrue(1.0 > 0.95)
    }

    @Test fun moreEvidenceAtTheSameProportionRaisesConfidence() {
        val small = ReviewQuality.wilsonLowerBound(90, 10)
        val large = ReviewQuality.wilsonLowerBound(900_000, 100_000)

        assertTrue(large > small)
        assertTrue(large < 0.90 + 1e-6)
    }

    /**
     * A missing rating contributes a declared neutral and **no reason at all**. Zero was rejected:
     * it would rank an un-enriched game below one the player's own library facts rate badly, which
     * is a claim the app has no basis for.
     */
    @Test fun aMissingOrUnavailableRatingIsNeutralAndUndisclosed() {
        val (missingScore, missingReason) = ReviewQuality.scoreAndReason(null)
        assertEquals(ReviewQuality.NEUTRAL, missingScore, 0.0)
        assertNull(missingReason)

        val (unavailableScore, unavailableReason) =
            ReviewQuality.scoreAndReason(GameReviewSummary.Unavailable)
        assertEquals(ReviewQuality.NEUTRAL, unavailableScore, 0.0)
        assertNull(unavailableReason)

        assertEquals(0.5, ReviewQuality.NEUTRAL, 0.0)
    }

    @Test fun anAvailableRatingAlwaysCarriesSteamsOwnWordsAndVolume() {
        val (score, reason) = ReviewQuality.scoreAndReason(
            GameReviewSummary.Available("Very Positive", positive = 90, negative = 10, total = 100),
        )

        assertTrue(score > 0.8 && score < 0.9)
        assertEquals(GapPlanReason.ReviewQuality("Very Positive", 90, 100), reason)
    }

    @Test fun anEmptySampleIsNeutralRatherThanZero() {
        assertEquals(ReviewQuality.NEUTRAL, ReviewQuality.wilsonLowerBound(0, 0), 0.0)
        // A genuinely terrible game is still scored, and lands far below the neutral.
        assertTrue(ReviewQuality.wilsonLowerBound(2, 998) < 0.02)
    }

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

    @Test fun noHistoryOrUnknownGenresScoreNeutralAndDiscloseNothing() {
        val noHistory = GenreAffinity.scoreAndReason(listOf("action"), emptyMap(), emptyMap())
        assertEquals(GenreAffinity.NEUTRAL, noHistory.first, 0.0)
        assertNull(noHistory.second)

        val unknownGenres = GenreAffinity.scoreAndReason(emptyList(), mapOf("action" to 1.0), emptyMap())
        assertEquals(GenreAffinity.NEUTRAL, unknownGenres.first, 0.0)
        assertNull(unknownGenres.second)

        // Known genres the player has simply never played are neutral too, not zero: no history
        // with a genre is not evidence against it.
        val unmatched = GenreAffinity.scoreAndReason(listOf("puzzle"), mapOf("action" to 1.0), emptyMap())
        assertEquals(GenreAffinity.NEUTRAL, unmatched.first, 0.0)
        assertNull(unmatched.second)
    }

    /** A multi-genre game is scored on the mean of what matched, and names the strongest match. */
    @Test fun affinityIsTheMeanOfMatchedGenresAndNamesTheStrongest() {
        val (score, reason) = GenreAffinity.scoreAndReason(
            genreIds = listOf("action", "puzzle", "unknown"),
            weights = mapOf("action" to 1.0, "puzzle" to 0.4),
            genreLabels = mapOf("action" to "Action", "puzzle" to "Puzzle"),
        )

        assertEquals(0.7, score, 1e-9)
        assertEquals(GapPlanReason.GenreAffinity("Action"), reason)
    }

    @Test fun anUnplayedGameHasNoMomentumAndSaysNothingAboutProgress() {
        val (score, reason) = CompletionMomentum.scoreAndReason(playedMinutes = 0, estimateMinutes = 600)

        assertEquals(0.0, score, 0.0)
        assertNull(reason)
    }

    @Test fun momentumIsThePlayedFractionAndIsClampedAtOne() {
        val (half, reason) = CompletionMomentum.scoreAndReason(300, 600)
        assertEquals(0.5, half, 1e-9)
        assertEquals(GapPlanReason.Progress(300, 600), reason)

        assertEquals(1.0, CompletionMomentum.scoreAndReason(900, 600).first, 0.0)
    }

    /** Momentum is the smallest component, so progress nudges rather than decides. */
    @Test fun momentumWeighsLessThanReviewsOrGenreAffinity() {
        assertTrue(GapPlanCandidate.MOMENTUM_WEIGHT < GapPlanCandidate.GENRE_WEIGHT)
        assertTrue(GapPlanCandidate.GENRE_WEIGHT < GapPlanCandidate.REVIEW_WEIGHT)
        assertEquals(
            1.0,
            GapPlanCandidate.REVIEW_WEIGHT + GapPlanCandidate.GENRE_WEIGHT +
                GapPlanCandidate.MOMENTUM_WEIGHT,
            1e-9,
        )

        // A fully-played started game cannot overtake an unplayed game with better reviews.
        val startedMediocre = candidate(1, 100).copy(
            reviewQuality = 0.5, genreAffinity = 0.5, completionMomentum = 1.0,
        )
        val unplayedExcellent = candidate(2, 100).copy(
            reviewQuality = 0.95, genreAffinity = 0.8, completionMomentum = 0.0,
        )
        assertTrue(unplayedExcellent.quality > startedMediocre.quality)
    }
}
