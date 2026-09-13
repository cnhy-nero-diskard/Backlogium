package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The words the surface actually says — and, as much, how few of them there are.
 *
 * Several of these are the *only* place a missing fact is disclosed, so they are asserted directly
 * rather than only through a rendered tree: a disclosure that quietly stopped being produced would
 * otherwise fail no test at all. The length assertions are not fussiness either. An earlier version
 * stated every figure as its own sentence, which was individually defensible and collectively
 * unreadable, and a surface the player skims past discloses nothing whatever it says.
 */
class GapPlanPresentationTest {

    /** The whole forecast is stated once, as a bare duration. The bars carry what it means. */
    @Test fun theFullForecastIsABareDuration() {
        assertEquals("100h", GapPlanPresentation.fullCapacity(6_000))
    }

    /** A tier's share and its pick's length travel together, as the bar's caption. */
    @Test fun aTiersCaptionStatesThePickAgainstItsShare() {
        assertEquals(
            "66h 40m of 70h",
            GapPlanPresentation.pickAgainstShare(pickUi(PlanIntensity.RELAXED, 4_200, 4_000)),
        )
    }

    /** An empty tier has no pick to measure, so its caption is the share alone. */
    @Test fun anEmptyTiersCaptionIsJustItsShare() {
        assertEquals(
            "70h",
            GapPlanPresentation.pickAgainstShare(
                GapPlanPickUi(PlanIntensity.RELAXED, 4_200, 4_200, game = null),
            ),
        )
    }

    /**
     * A forecast is the default, so it says nothing; a manual budget is the player's own estimate
     * and must not read as a forecast. Labelling the default would be noise on a surface that has
     * no room for any.
     */
    @Test fun onlyAManualBudgetCarriesACaveat() {
        assertNull(GapPlanPresentation.capacityCaveat(CapacityProvenance.PERSONAL_PACE))
        val manual = GapPlanPresentation.capacityCaveat(CapacityProvenance.MANUAL)!!
        assertTrue(manual.contains("not a forecast"))
    }

    /** The collapsed request restates every input, so reopening the form is an informed choice. */
    @Test fun theCollapsedSummaryCarriesEveryInput() {
        val summary = GapPlanPresentation.requestSummary(
            GapPlanResultUi(
                anticipatedTitle = "Silksong",
                targetDate = LocalDate.parse("2027-01-15"),
                intent = GapPlanIntent.COMPLETIONIST,
                fullCapacityMinutes = 6_000,
                provenance = CapacityProvenance.PERSONAL_PACE,
                picks = emptyList(),
                coverage = GapPlanCoverage(0, 0, 0, 0, 0, 0),
            ),
        )

        assertEquals("Silksong · 2027-01-15 · Completionist", summary)
    }

    /** Genres are joined into one short line, and omitted entirely when none are cached. */
    @Test fun genresAreJoinedOrOmittedRatherThanRenderedEmpty() {
        assertEquals(
            "Action, RPG",
            GapPlanPresentation.genreLine(gameUi(genres = listOf("Action", "RPG"))),
        )
        assertNull(GapPlanPresentation.genreLine(gameUi(genres = emptyList())))
    }

    // --- Review standing ---------------------------------------------------------------------

    /**
     * Steam's bands, as ratios. The band boundaries are asserted from both sides because they are
     * where a colour changes, and an off-by-one there is invisible until a game lands on it.
     */
    @Test fun reviewStandingFollowsTheRatioAtEveryBandBoundary() {
        assertEquals(ReviewStanding.POSITIVE, standing(positive = 70, total = 100))
        assertEquals(ReviewStanding.MIXED, standing(positive = 69, total = 100))
        assertEquals(ReviewStanding.MIXED, standing(positive = 40, total = 100))
        assertEquals(ReviewStanding.NEGATIVE, standing(positive = 39, total = 100))
    }

    @Test fun anOverwhelminglyPositiveGameIsPositiveAndAPannedOneIsNegative() {
        assertEquals(ReviewStanding.POSITIVE, standing(2_234_895, 2_320_000))
        assertEquals(ReviewStanding.NEGATIVE, standing(200, 10_000))
    }

    /**
     * The standing must not come from the description's words.
     *
     * That string is localized — the reviews endpoint is asked for `language=all` — so a word match
     * would silently mis-colour every non-English response. Two summaries with contradictory
     * descriptions and identical counts must therefore agree, which a word match could not manage.
     */
    @Test fun standingIgnoresTheDescriptionText() {
        val counts = 90 to 100
        val english = GapPlanFact.Reviews("Very Positive", counts.first, counts.second)
        val localized = GapPlanFact.Reviews("Sehr positiv", counts.first, counts.second)
        val misleading = GapPlanFact.Reviews("Overwhelmingly Negative", counts.first, counts.second)

        assertEquals(ReviewStanding.POSITIVE, GapPlanPresentation.reviewStanding(english))
        assertEquals(ReviewStanding.POSITIVE, GapPlanPresentation.reviewStanding(localized))
        assertEquals(ReviewStanding.POSITIVE, GapPlanPresentation.reviewStanding(misleading))
    }

    /** No reviews is no standing, rather than a default one. */
    @Test fun anEmptySampleHasNoStanding() {
        assertNull(GapPlanPresentation.reviewStanding(GapPlanFact.Reviews("No user reviews", 0, 0)))
    }

    // --- Indicator values --------------------------------------------------------------------

    /**
     * Values are abbreviated because they sit in a dense row beside three other indicators. The
     * exact digit count carries no decision here; the width does.
     */
    @Test fun indicatorValuesAreTheirShortestUnambiguousForm() {
        assertEquals(
            "Very Positive · 2.8M",
            GapPlanPresentation.reviewLabel(GapPlanFact.Reviews("Very Positive", 2_234_895, 2_775_966)),
        )
        assertEquals("4.3K", GapPlanPresentation.playerCountLabel(GapPlanFact.PlayingNow(4_321)))
        assertEquals("5h in", GapPlanPresentation.progressLabel(GapPlanFact.Progress(300, 600)))
        assertEquals("10h", GapPlanPresentation.remaining(gameUi(remaining = 600)))
    }

    /** Every card leads with the same hook, so the surface offers rather than describes. */
    @Test fun everyCardLeadsWithARecommendationHook() {
        assertEquals("You might like", GapPlanPresentation.recommendationHook())
    }

    @Test fun theThreeIntensitiesAreNamedAndTheirShareIsABarePercent() {
        assertEquals("Relaxed", GapPlanPresentation.intensityName(PlanIntensity.RELAXED))
        assertEquals("Balanced", GapPlanPresentation.intensityName(PlanIntensity.BALANCED))
        assertEquals("Full", GapPlanPresentation.intensityName(PlanIntensity.FULL))
        assertEquals("70%", GapPlanPresentation.intensityShare(PlanIntensity.RELAXED))
        assertEquals("100%", GapPlanPresentation.intensityShare(PlanIntensity.FULL))
    }

    // --- Disclosures -------------------------------------------------------------------------

    /**
     * Missing lengths are still disclosed as unknown rather than short — that distinction is the
     * whole reason the disclosure exists, and it survives the compression.
     */
    @Test fun missingHltbCoverageIsDisclosedAsUnknownRatherThanShort() {
        val disclosures = GapPlanPresentation.coverageDisclosures(
            GapPlanCoverage(
                visibleGames = 10,
                withSelectedEstimate = 7,
                missingSelectedEstimate = 3,
                alreadyComplete = 0,
                withCachedReviews = 10,
                withKnownGenres = 10,
            ),
        )

        assertEquals(1, disclosures.size)
        assertTrue(disclosures.single().contains("3 games skipped"))
        assertTrue(disclosures.single().contains("unknown, not short"))
    }

    @Test fun anUnenrichedLibraryDisclosesThatNoRatingsAreCached() {
        val disclosures = GapPlanPresentation.coverageDisclosures(
            GapPlanCoverage(10, 10, 0, 0, withCachedReviews = 0, withKnownGenres = 0),
        )

        assertTrue(disclosures.any { it.contains("No ratings cached") })
        assertTrue(
            "a disclosure must not claim a signal influenced the picks",
            disclosures.none { it.contains("influence") },
        )
    }

    @Test fun aFullyCoveredLibraryDisclosesNothing() {
        assertEquals(
            emptyList<String>(),
            GapPlanPresentation.coverageDisclosures(GapPlanCoverage(10, 10, 0, 0, 10, 10)),
        )
    }

    /** Every aside has to fit one line beside an icon; a paragraph would not be read at all. */
    @Test fun everyAsideIsShortEnoughToRenderBesideAnIcon() {
        val asides = GapPlanPresentation.coverageDisclosures(
            GapPlanCoverage(10, 7, 3, 0, 0, 0),
        ) + listOf(
            GapPlanPresentation.selectionExplanation(),
            GapPlanPresentation.rebuildDidNotVaryMessage(),
            GapPlanPresentation.saveFailureMessage(),
            GapPlanPresentation.emptyPickMessage(),
            GapPlanPresentation.reliablePaceExplanation(),
        )

        asides.forEach { aside ->
            assertTrue("too long to sit beside an icon: \"$aside\"", aside.length <= MAX_ASIDE)
        }
    }

    /** The surface says the picks were not judged, so no card reads as the recommended one. */
    @Test fun theSelectionExplanationSaysThePicksWereNotRanked() {
        assertTrue(GapPlanPresentation.selectionExplanation().contains("at random"))
    }

    /** An unchanged rebuild explains itself rather than looking like an ignored control. */
    @Test fun anUnchangedRebuildExplainsWhyNothingMoved() {
        assertTrue(GapPlanPresentation.rebuildDidNotVaryMessage().contains("No other games"))
    }

    /** The basis the plan was measured in is the basis the collection will carry. */
    @Test fun theIntentNamesTheHltbBasisTheCollectionWillUse() {
        assertEquals("Main Story", GapPlanPresentation.intentBasisLabel(GapPlanIntent.STORY))
        assertEquals("Completionist", GapPlanPresentation.intentBasisLabel(GapPlanIntent.COMPLETIONIST))
    }

    @Test fun everyValidationErrorNamesSomethingThePlayerCanFix() {
        GapPlanRequestError.entries.forEach { error ->
            assertTrue(
                "$error produced an empty message",
                GapPlanPresentation.validationMessage(error).isNotBlank(),
            )
        }
        assertTrue(
            GapPlanPresentation.validationMessage(GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON)
                .contains("three years"),
        )
    }

    /** The manual-hours ask still explains itself rather than only stating that it is required. */
    @Test fun theManualBudgetExplanationSaysWhyItIsBeingAsked() {
        val explanation = GapPlanPresentation.manualBudgetExplanation()

        assertTrue(explanation.contains("Not enough tracked history"))
        assertTrue(explanation.contains("how many hours"))
    }

    @Test fun anEmptyTierExplainsItselfRatherThanRenderingBlank() {
        assertTrue(GapPlanPresentation.emptyPickMessage().contains("Nothing fits"))
    }

    private fun standing(positive: Int, total: Int): ReviewStanding? =
        GapPlanPresentation.reviewStanding(GapPlanFact.Reviews("ignored", positive, total))

    private fun pickUi(intensity: PlanIntensity, budget: Int, remaining: Int) = GapPlanPickUi(
        intensity = intensity,
        budgetMinutes = budget,
        unusedMinutes = budget - remaining,
        game = gameUi(remaining = remaining),
    )

    private fun gameUi(
        remaining: Int = 600,
        genres: List<String> = listOf("Action"),
        facts: List<GapPlanFact> = emptyList(),
    ) = GapPlanGameUi(
        appId = 1L,
        name = "Game 1",
        iconUrl = "",
        headerUrl = "",
        remainingMinutes = remaining,
        isFamilyShared = false,
        isMultiplayer = false,
        genreLabels = genres,
        facts = facts,
    )

    private companion object {
        /** Roughly what fits one line beside a 13dp icon at label size on a narrow phone. */
        const val MAX_ASIDE = 56
    }
}
