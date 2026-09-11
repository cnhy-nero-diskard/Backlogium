package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanReason
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the surface actually says.
 *
 * Several of these sentences are the *only* place a missing signal is disclosed, so they are
 * asserted directly rather than only through a rendered tree — a disclosure that quietly stopped
 * being produced would otherwise fail no test at all.
 */
class GapPlanPresentationTest {

    /**
     * The full forecast is stated separately from any variant's budget. Without it a Relaxed card
     * showing only its own figures would present its reduced budget as all the time the player
     * has, concealing exactly the time the intensity deliberately withheld.
     */
    @Test fun theFullForecastAndTheVariantsWithheldShareAreBothStated() {
        val relaxed = variant(PlanIntensity.RELAXED, budget = 4_200, planned = 4_000)

        assertEquals(
            "You have about 100h before then.",
            GapPlanPresentation.fullCapacityLine(6_000),
        )
        assertEquals(
            "70h budget · 66h 40m planned · 3h 20m reserve",
            GapPlanPresentation.variantBudgetLine(relaxed),
        )
        assertEquals(
            "Holding back 30h of your forecast.",
            GapPlanPresentation.withheldLine(relaxed, fullCapacityMinutes = 6_000),
        )
    }

    /** The Full variant withholds nothing, so it says nothing about withholding. */
    @Test fun aFullVariantHasNoWithheldLine() {
        assertNull(
            GapPlanPresentation.withheldLine(
                variant(PlanIntensity.FULL, budget = 6_000, planned = 5_000),
                fullCapacityMinutes = 6_000,
            ),
        )
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
     * A library the enrichment has not reached ranks on duration and progress alone. That is the
     * accepted degradation, and the plan says so rather than looking better informed than it was.
     */
    @Test fun anUnenrichedLibraryDisclosesThatRatingsAndGenresDidNotInfluenceThePlan() {
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
    }

    @Test fun aFullyCoveredLibraryDisclosesNothing() {
        assertEquals(
            emptyList<String>(),
            GapPlanPresentation.coverageDisclosures(
                GapPlanCoverage(10, 10, 0, 0, 10, 10),
            ),
        )
    }

    /** Every chip states a fact. There is no composite score to render, by construction. */
    @Test fun everyReasonRendersAsACheckableFact() {
        assertEquals(
            "Very Positive (2,775,966 reviews)",
            GapPlanPresentation.reasonLabel(
                GapPlanReason.ReviewQuality("Very Positive", positive = 2_234_895, total = 2_775_966),
            ),
        )
        assertEquals(
            "You have been playing Action",
            GapPlanPresentation.reasonLabel(GapPlanReason.GenreAffinity("Action")),
        )
        assertEquals(
            "5h of 10h played",
            GapPlanPresentation.reasonLabel(GapPlanReason.Progress(300, 600)),
        )
        assertEquals("10h left", GapPlanPresentation.reasonLabel(GapPlanReason.Fit(600)))
        assertEquals("Family shared", GapPlanPresentation.reasonLabel(GapPlanReason.FamilyShared))
        assertEquals(
            "4,321 playing now",
            GapPlanPresentation.reasonLabel(GapPlanReason.PlayingNow(4_321)),
        )
    }

    @Test fun theThreeIntensitiesAreNamedAndTheirShareOfCapacityIsStated() {
        assertEquals("Relaxed", GapPlanPresentation.intensityName(PlanIntensity.RELAXED))
        assertEquals("Balanced", GapPlanPresentation.intensityName(PlanIntensity.BALANCED))
        assertEquals("Full", GapPlanPresentation.intensityName(PlanIntensity.FULL))
        assertEquals(
            "70% of your forecast time",
            GapPlanPresentation.intensityRule(PlanIntensity.RELAXED),
        )
        assertEquals(
            "100% of your forecast time",
            GapPlanPresentation.intensityRule(PlanIntensity.FULL),
        )
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

    @Test fun anEmptyVariantExplainsItselfRatherThanRenderingBlank() {
        val message = GapPlanPresentation.emptyVariantMessage()

        assertTrue(message.contains("known length"))
        assertTrue(message.isNotBlank())
    }

    private fun variant(intensity: PlanIntensity, budget: Int, planned: Int) = GapPlanVariantUi(
        intensity = intensity,
        budgetMinutes = budget,
        plannedMinutes = planned,
        reserveMinutes = budget - planned,
        members = emptyList(),
    )
}
