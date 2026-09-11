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

/**
 * The words the surface actually says.
 *
 * Several of these sentences are the *only* place a missing fact is disclosed, so they are asserted
 * directly rather than only through a rendered tree — a disclosure that quietly stopped being
 * produced would otherwise fail no test at all.
 */
class GapPlanPresentationTest {

    /**
     * The full forecast is stated separately from any tier's share. Without it a Relaxed card
     * showing only its own figure would present that reduced share as all the time the player has,
     * concealing exactly the time the intensity deliberately withheld.
     */
    @Test fun theFullForecastAndTheTiersWithheldShareAreBothStated() {
        val relaxed = pickUi(PlanIntensity.RELAXED, budget = 4_200, remaining = 4_000)

        assertEquals(
            "You have about 100h before then.",
            GapPlanPresentation.fullCapacityLine(6_000),
        )
        assertEquals("Planning around 70h of it", GapPlanPresentation.pickShareLine(relaxed))
        assertEquals(
            "Holding back 30h of your forecast.",
            GapPlanPresentation.withheldLine(relaxed, fullCapacityMinutes = 6_000),
        )
    }

    /** The Full tier withholds nothing, so it says nothing about withholding. */
    @Test fun aFullTierHasNoWithheldLine() {
        assertNull(
            GapPlanPresentation.withheldLine(
                pickUi(PlanIntensity.FULL, budget = 6_000, remaining = 5_000),
                fullCapacityMinutes = 6_000,
            ),
        )
    }

    /** A pick's remaining time is stated once, in its own line. */
    @Test fun aPicksRemainingTimeIsStatedAsItsOwnLine() {
        assertEquals(
            "10h left",
            GapPlanPresentation.remainingLine(gameUi(remaining = 600)),
        )
    }

    /** Genres are joined into one line, and omitted entirely when none are cached. */
    @Test fun genresAreJoinedOrOmittedRatherThanRenderedEmpty() {
        assertEquals(
            "Action · RPG",
            GapPlanPresentation.genreLine(gameUi(genres = listOf("Action", "RPG"))),
        )
        assertNull(GapPlanPresentation.genreLine(gameUi(genres = emptyList())))
    }

    /**
     * A manual budget is the player's own estimate. Presenting it as a Personal Pace forecast
     * would attribute a confidence the data cannot support.
     */
    @Test fun capacityProvenanceIsNamedAndTheTwoSourcesReadDifferently() {
        val forecast = GapPlanPresentation.capacitySource(CapacityProvenance.PERSONAL_PACE)
        val manual = GapPlanPresentation.capacitySource(CapacityProvenance.MANUAL)

        assertTrue(forecast.contains("tracked activity"))
        assertTrue(manual.contains("hours you entered"))
        assertTrue("a manual budget must not read as a forecast", manual.contains("not a Personal Pace"))
    }

    /** Missing lengths are disclosed as unknown, never implied to be short. */
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
        assertTrue(disclosures.single().contains("3 of your games"))
        assertTrue(disclosures.single().contains("unknown, not short"))
    }

    /**
     * A library the enrichment has not reached still gets valid suggestions — nothing is ranked, so
     * selection is unaffected — but its cards will be sparse, and the disclosure says so.
     *
     * The wording matters here and is the reason this is asserted rather than eyeballed. It used to
     * say ratings "did not influence this plan", which was true of one run and false as a general
     * claim: ratings never influence a plan now. Saying the cards cannot *show* them is the only
     * version that stays true when the cache fills up.
     */
    @Test fun anUnenrichedLibraryDisclosesWhatItsCardsCannotShow() {
        val disclosures = GapPlanPresentation.coverageDisclosures(
            GapPlanCoverage(
                visibleGames = 10,
                withSelectedEstimate = 10,
                missingSelectedEstimate = 0,
                alreadyComplete = 0,
                withCachedReviews = 0,
                withKnownGenres = 0,
            ),
        )

        assertEquals(2, disclosures.size)
        assertTrue(disclosures.any { it.contains("No Steam ratings") })
        assertTrue(disclosures.any { it.contains("No Store genres") })
        assertTrue(
            "a disclosure must not claim a signal influenced the picks",
            disclosures.none { it.contains("influence") },
        )
    }

    @Test fun aFullyCoveredLibraryDisclosesNothing() {
        assertEquals(
            emptyList<String>(),
            GapPlanPresentation.coverageDisclosures(
                GapPlanCoverage(10, 10, 0, 0, 10, 10),
            ),
        )
    }

    /** Every label states a fact. There is no composite score to render, by construction. */
    @Test fun everyFactRendersAsACheckableStatement() {
        assertEquals(
            "Very Positive (2,775,966 reviews)",
            GapPlanPresentation.factLabel(
                GapPlanFact.Reviews("Very Positive", positive = 2_234_895, total = 2_775_966),
            ),
        )
        assertEquals(
            "You have been playing Action",
            GapPlanPresentation.factLabel(GapPlanFact.GenreAffinity("Action")),
        )
        assertEquals(
            "5h of 10h played",
            GapPlanPresentation.factLabel(GapPlanFact.Progress(300, 600)),
        )
        assertEquals(
            "4,321 playing now",
            GapPlanPresentation.factLabel(GapPlanFact.PlayingNow(4_321)),
        )
    }

    /**
     * A tier targets its share rather than merely fitting under it, and the copy has to say so.
     * "Up to 70%" would describe the design this replaced, under which all three tiers could offer
     * the same short game.
     */
    @Test fun theThreeIntensitiesAreNamedAndTheirTargetShareIsStated() {
        assertEquals("Relaxed", GapPlanPresentation.intensityName(PlanIntensity.RELAXED))
        assertEquals("Balanced", GapPlanPresentation.intensityName(PlanIntensity.BALANCED))
        assertEquals("Full", GapPlanPresentation.intensityName(PlanIntensity.FULL))
        assertEquals(
            "Around 70% of your forecast time",
            GapPlanPresentation.intensityRule(PlanIntensity.RELAXED),
        )
        assertEquals(
            "Around 100% of your forecast time",
            GapPlanPresentation.intensityRule(PlanIntensity.FULL),
        )
    }

    /** The surface says the picks were not judged, so no card reads as the recommended one. */
    @Test fun theSelectionExplanationSaysThePicksWereNotRanked() {
        val explanation = GapPlanPresentation.selectionExplanation()

        assertTrue(explanation.contains("at random"))
        assertTrue(explanation.contains("judge them"))
    }

    /** An unchanged rebuild explains itself rather than looking like an ignored control. */
    @Test fun anUnchangedRebuildExplainsWhyNothingMoved() {
        val message = GapPlanPresentation.rebuildDidNotVaryMessage()

        assertTrue(message.contains("not enough games"))
        assertTrue("it must suggest a way out", message.contains("Widen"))
    }

    /** The basis the plan was measured in is the basis the collection will carry. */
    @Test fun theIntentNamesTheHltbBasisTheCollectionWillUse() {
        assertEquals("Main Story", GapPlanPresentation.intentBasisLabel(GapPlanIntent.STORY))
        assertEquals("Completionist", GapPlanPresentation.intentBasisLabel(GapPlanIntent.COMPLETIONIST))
    }

    @Test fun everyValidationErrorNamesSomethingThePlayerCanFix() {
        GapPlanRequestError.entries.forEach { error ->
            val message = GapPlanPresentation.validationMessage(error)
            assertTrue("$error produced an empty message", message.isNotBlank())
        }
        assertTrue(
            GapPlanPresentation.validationMessage(GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON)
                .contains("three years"),
        )
    }

    /** The manual-hours ask explains itself rather than only stating that it is required. */
    @Test fun theManualBudgetExplanationSaysWhyItIsBeingAsked() {
        val explanation = GapPlanPresentation.manualBudgetExplanation()

        assertTrue(explanation.contains("not enough tracked play history"))
        assertTrue(explanation.contains("how many hours"))
    }

    /** A failed save says the plan survived, because that is the part the player needs to know. */
    @Test fun theSaveFailureMessageSaysThePlanIsStillThere() {
        assertTrue(GapPlanPresentation.saveFailureMessage().contains("still here"))
    }

    @Test fun anEmptyTierExplainsItselfRatherThanRenderingBlank() {
        val message = GapPlanPresentation.emptyPickMessage()

        assertTrue(message.contains("known length"))
        assertTrue(message.isNotBlank())
    }

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
}
