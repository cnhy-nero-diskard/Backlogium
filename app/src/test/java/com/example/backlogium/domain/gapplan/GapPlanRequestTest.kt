package com.example.backlogium.domain.gapplan

import com.example.backlogium.domain.CollectionTimeBasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Request validation, capacity resolution, and budget arithmetic — everything a plan is decided
 * *before* a single game is considered.
 */
class GapPlanRequestTest {

    @Test fun aCompleteRequestValidates() {
        assertNull(gapRequest().validate(TODAY))
    }

    @Test fun aBlankTitleIsRejected() {
        assertEquals(
            GapPlanRequestError.MISSING_TITLE,
            gapRequest(title = "   ").validate(TODAY),
        )
    }

    @Test fun todayAndEarlierAreNotAGap() {
        assertEquals(
            GapPlanRequestError.TARGET_DATE_NOT_FUTURE,
            gapRequest(targetDate = TODAY).validate(TODAY),
        )
        assertEquals(
            GapPlanRequestError.TARGET_DATE_NOT_FUTURE,
            gapRequest(targetDate = TODAY.minusDays(1)).validate(TODAY),
        )
    }

    /**
     * The horizon is the pace engine's own, not a new policy: the forecast walks the range one day
     * at a time, so an unbounded target date is an unbounded loop *and* a meaningless plan.
     */
    @Test fun theHorizonIsExactly1095DaysAndItsBoundaryIsInclusive() {
        assertEquals(1_095L, GapPlanRequest.HORIZON_DAYS)
        assertNull(gapRequest(targetDate = TODAY.plusDays(1_095)).validate(TODAY))
        assertEquals(
            GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON,
            gapRequest(targetDate = TODAY.plusDays(1_096)).validate(TODAY),
        )
    }

    @Test fun intentMapsToTheHltbBasisThePlanWillBeMeasuredIn() {
        assertEquals(CollectionTimeBasis.MAIN_STORY, GapPlanIntent.STORY.timeBasis())
        assertEquals(CollectionTimeBasis.COMPLETIONIST, GapPlanIntent.COMPLETIONIST.timeBasis())
    }

    @Test fun theThreeBudgetsAre70_85_and100PercentOfFullCapacity() {
        assertEquals(4_200, PlanIntensity.RELAXED.budgetMinutes(6_000))
        assertEquals(5_100, PlanIntensity.BALANCED.budgetMinutes(6_000))
        assertEquals(6_000, PlanIntensity.FULL.budgetMinutes(6_000))
    }

    /**
     * Integer arithmetic, not `floor(minutes * 0.70)`. 0.70 has no exact binary representation, so
     * the double form can land a hair under a boundary and floor one minute low — which would make
     * an identical rerun produce a different plan, breaking a property the engine promises.
     */
    @Test fun budgetRoundingFloorsExactlyAndNeverDriftsOnABoundary() {
        assertEquals(7, PlanIntensity.RELAXED.budgetMinutes(11))
        assertEquals(9, PlanIntensity.BALANCED.budgetMinutes(11))
        assertEquals(0, PlanIntensity.RELAXED.budgetMinutes(1))
        assertEquals(0, PlanIntensity.RELAXED.budgetMinutes(0))
        // A negative capacity cannot arise, but must not become a negative budget if it did.
        assertEquals(0, PlanIntensity.FULL.budgetMinutes(-100))
        // Exact multiples of 10 land on the integer their percentage names, every time.
        for (capacity in 0..2_000 step 10) {
            assertEquals(capacity * 70 / 100, PlanIntensity.RELAXED.budgetMinutes(capacity))
        }
    }

    /** Capacity runs from *tomorrow*: today is partly spent and must not be promised again. */
    @Test fun reliablePaceForecastsFromTomorrowThroughTheTargetDateInclusive() {
        val request = gapRequest(targetDate = TODAY.plusDays(10))

        val capacity = request.resolveCapacity(reliablePace(), TODAY).getOrThrow()

        assertEquals(CapacityProvenance.PERSONAL_PACE, capacity.provenance)
        assertEquals(TODAY.plusDays(1), capacity.startDate)
        assertEquals(TODAY.plusDays(10), capacity.endDate)
        val expected = reliablePace()
            .forecast(TODAY.plusDays(1), TODAY.plusDays(10))
            .expectedGamingMinutes
            .toInt()
        assertEquals(expected, capacity.fullCapacityMinutes)
    }

    @Test fun aLearningProfileRequiresAPositiveManualBudget() {
        val error = gapRequest().resolveCapacity(learningPace(), TODAY).exceptionOrNull()
        assertEquals(
            GapPlanRequestError.MISSING_MANUAL_BUDGET,
            (error as GapPlanRequestException).error,
        )

        val zero = gapRequest(manualTotalHours = 0).resolveCapacity(learningPace(), TODAY)
        assertEquals(
            GapPlanRequestError.MISSING_MANUAL_BUDGET,
            (zero.exceptionOrNull() as GapPlanRequestException).error,
        )
    }

    /** A manual budget is the player's own guess and is labelled as one, never as a pace claim. */
    @Test fun aManualBudgetBecomesTheFullCapacityAndIsLabelledManual() {
        val capacity = gapRequest(manualTotalHours = 40)
            .resolveCapacity(learningPace(), TODAY)
            .getOrThrow()

        assertEquals(2_400, capacity.fullCapacityMinutes)
        assertEquals(CapacityProvenance.MANUAL, capacity.provenance)
    }

    /** An invalid request fails before any capacity is computed, whatever the profile says. */
    @Test fun validationPrecedesCapacityResolution() {
        val error = gapRequest(targetDate = TODAY)
            .resolveCapacity(reliablePace(), TODAY)
            .exceptionOrNull()

        assertEquals(
            GapPlanRequestError.TARGET_DATE_NOT_FUTURE,
            (error as GapPlanRequestException).error,
        )
    }
}
