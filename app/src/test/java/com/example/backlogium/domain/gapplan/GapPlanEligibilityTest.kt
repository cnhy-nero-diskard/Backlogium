package com.example.backlogium.domain.gapplan

import com.example.backlogium.domain.GameSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets to be a candidate, and how much work each one represents.
 *
 * The failure this guards against is a game with no completion estimate being treated as zero
 * work: it would look free, fit every budget, and rank above everything the library actually knows
 * about. Missing must stay *unknown* all the way through, and be disclosed instead.
 */
class GapPlanEligibilityTest {

    @Test fun aPartiallyPlayedGameContributesOnlyItsRemainingWork() {
        val result = derive(listOf(gapGame(1, steamPlaytimeMinutes = 300, mainStoryMinutes = 900)))

        assertEquals(600, result.eligible.single().remainingMinutes)
        assertEquals(900, result.eligible.single().estimateMinutes)
        assertEquals(300, result.eligible.single().playedMinutes)
    }

    @Test fun aGameAtOrBeyondItsEstimateIsAlreadyCompleteForThisRequest() {
        val result = derive(
            listOf(
                gapGame(1, steamPlaytimeMinutes = 900, mainStoryMinutes = 900),
                gapGame(2, steamPlaytimeMinutes = 5_000, mainStoryMinutes = 900),
            ),
        )

        assertTrue(result.eligible.isEmpty())
        assertEquals(2, result.coverage.alreadyComplete)
        assertEquals(0, result.coverage.missingSelectedEstimate)
    }

    /**
     * Null *and* a non-positive stored value both mean unknown. A zero-minute completion estimate
     * is not a game that takes no time; it is an estimate that never resolved.
     */
    @Test fun aMissingOrNonPositiveEstimateIsUnknownWorkAndIsReportedAsACoverageGap() {
        val result = derive(
            listOf(
                gapGame(1, mainStoryMinutes = null),
                gapGame(2, mainStoryMinutes = 0),
                gapGame(3, mainStoryMinutes = -30),
                gapGame(4, mainStoryMinutes = 600),
            ),
        )

        assertEquals(listOf(4L), result.eligible.map { it.game.appId })
        assertEquals(3, result.coverage.missingSelectedEstimate)
        assertEquals(1, result.coverage.withSelectedEstimate)
        assertFalse(result.coverage.isComplete)
    }

    /** The request's own toggles narrow this plan; they say nothing about the library. */
    @Test fun theRequestCanExcludeUnplayedOrStartedGamesWithoutChangingLibraryState() {
        val games = listOf(
            gapGame(1, steamPlaytimeMinutes = 0),
            gapGame(2, steamPlaytimeMinutes = 120),
        )

        assertEquals(
            listOf(2L),
            derive(games, gapRequest(includeUnplayed = false)).eligible.map { it.game.appId },
        )
        assertEquals(
            listOf(1L),
            derive(games, gapRequest(includeStarted = false)).eligible.map { it.game.appId },
        )
    }

    /**
     * A family-shared game has no Steam playtime at all, so the owned-game rule would call every
     * shared game unplayed and re-plan its full length forever.
     */
    @Test fun familySharedPlaytimeIsTrackedSessionsPlusTheOwnersOwnEstimate() {
        val result = derive(
            listOf(
                gapGame(
                    1,
                    source = GameSource.FAMILY_SHARED,
                    steamPlaytimeMinutes = 0,
                    trackedMinutes = 100,
                    manualSharedMinutes = 200,
                    mainStoryMinutes = 900,
                ),
            ),
        )

        val eligible = result.eligible.single()
        assertEquals(300, eligible.playedMinutes)
        assertEquals(600, eligible.remainingMinutes)
        assertEquals(GameSource.FAMILY_SHARED, eligible.game.source)
    }

    /**
     * A legacy near-`Int.MAX` manual estimate must not wrap the subtraction negative and reappear
     * as an irresistibly cheap candidate.
     */
    @Test fun anAbsurdlyLargePlaytimeCannotOverflowIntoTinyRemainingWork() {
        val result = derive(
            listOf(
                gapGame(
                    1,
                    source = GameSource.FAMILY_SHARED,
                    trackedMinutes = Int.MAX_VALUE,
                    manualSharedMinutes = Int.MAX_VALUE,
                    mainStoryMinutes = 600,
                ),
            ),
        )

        assertTrue(result.eligible.isEmpty())
        assertEquals(1, result.coverage.alreadyComplete)
    }

    /** One fit ceiling for every variant, so all three work from the same pool. */
    @Test fun aGameTooLongForEvenTheFullBudgetIsExcludedButIsNotACoverageGap() {
        val result = derive(
            listOf(gapGame(1, mainStoryMinutes = 10_000), gapGame(2, mainStoryMinutes = 600)),
            fullBudgetMinutes = 1_000,
        )

        assertEquals(listOf(2L), result.eligible.map { it.game.appId })
        assertEquals(0, result.coverage.missingSelectedEstimate)
        assertEquals(2, result.coverage.withSelectedEstimate)
    }

    @Test fun theCompletionistIntentReadsTheCompletionistLength() {
        val games = listOf(gapGame(1, mainStoryMinutes = 600, completionistMinutes = 1_800))

        assertEquals(600, derive(games).eligible.single().remainingMinutes)
        assertEquals(
            1_800,
            derive(games, gapRequest(intent = GapPlanIntent.COMPLETIONIST), fullBudgetMinutes = 5_000)
                .eligible.single().remainingMinutes,
        )
    }

    /**
     * Eligibility never asks for anything. A game with no resolved estimate contributes to the
     * coverage gap and nothing else — no lookup is issued, implied, or triggered, which is what
     * keeps the explicit-target HLTB contract intact.
     */
    @Test fun derivationIssuesNoLookupAndNeedsNoReviewOrGenreData() {
        val result = deriveEligibility(
            games = listOf(
                gapGame(1, genreIds = emptyList()),
                gapGame(2, mainStoryMinutes = null, genreIds = emptyList()),
            ),
            request = gapRequest(),
            fullBudgetMinutes = 6_000,
            hasCachedReview = { false },
        )

        assertEquals(listOf(1L), result.eligible.map { it.game.appId })
        assertEquals(0, result.coverage.withCachedReviews)
        assertEquals(0, result.coverage.withKnownGenres)
        assertEquals(2, result.coverage.visibleGames)
    }

    private fun derive(
        games: List<GapPlanGame>,
        request: GapPlanRequest = gapRequest(),
        fullBudgetMinutes: Int = 6_000,
    ) = deriveEligibility(
        games = games,
        request = request,
        fullBudgetMinutes = fullBudgetMinutes,
        hasCachedReview = { true },
    )
}
