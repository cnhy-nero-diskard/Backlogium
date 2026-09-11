package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanReason
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.ui.util.UiFormat

/**
 * Every user-facing string the gap-plan surface shows, as pure functions.
 *
 * Kept out of the composables so the wording can be asserted directly — several of these sentences
 * are the *only* place a missing signal is disclosed, and a test that could only reach them
 * through a rendered tree would be easy to leave un-asserted.
 */
object GapPlanPresentation {

    fun intensityName(intensity: PlanIntensity): String = when (intensity) {
        PlanIntensity.RELAXED -> "Relaxed"
        PlanIntensity.BALANCED -> "Balanced"
        PlanIntensity.FULL -> "Full"
    }

    /** States the intensity as a share of capacity, so the choice is legible rather than branded. */
    fun intensityRule(intensity: PlanIntensity): String =
        "${intensity.percent}% of your forecast time"

    fun intentLabel(intent: GapPlanIntent): String = when (intent) {
        GapPlanIntent.STORY -> "Story"
        GapPlanIntent.COMPLETIONIST -> "Completionist"
    }

    fun intentBasisLabel(intent: GapPlanIntent): String = when (intent) {
        GapPlanIntent.STORY -> "Main Story"
        GapPlanIntent.COMPLETIONIST -> "Completionist"
    }

    /**
     * Names where the capacity figure came from. A Personal Pace forecast is a claim the app can
     * make from tracked history; a manual budget is the player's own estimate. Presenting the
     * second as the first would attribute a confidence the data cannot support.
     */
    fun capacitySource(provenance: CapacityProvenance): String = when (provenance) {
        CapacityProvenance.PERSONAL_PACE -> "Forecast from your recent tracked activity"
        CapacityProvenance.MANUAL -> "Based on the hours you entered, not a Personal Pace forecast"
    }

    /**
     * The full forecast, stated once for all three variants.
     *
     * Without this line a Relaxed card showing "4,200 available, 300 left" would present its
     * reduced budget as all the time the player has — concealing exactly the time the intensity
     * deliberately withheld, which is the opposite of what the choice is for.
     */
    fun fullCapacityLine(fullCapacityMinutes: Int): String =
        "You have about ${UiFormat.minutes(fullCapacityMinutes)} before then."

    /** A variant's own three figures, always together so none can be read as the whole picture. */
    fun variantBudgetLine(variant: GapPlanVariantUi): String =
        "${UiFormat.minutes(variant.budgetMinutes)} budget · " +
            "${UiFormat.minutes(variant.plannedMinutes)} planned · " +
            "${UiFormat.minutes(variant.reserveMinutes)} reserve"

    /** The share of the forecast this variant is deliberately not planning against. */
    fun withheldLine(variant: GapPlanVariantUi, fullCapacityMinutes: Int): String? {
        val withheld = fullCapacityMinutes - variant.budgetMinutes
        if (withheld <= 0) return null
        return "Holding back ${UiFormat.minutes(withheld)} of your forecast."
    }

    fun emptyVariantMessage(): String =
        "No game with a known length fits this plan's time. Try a longer gap or a fuller plan."

    /**
     * What the ranking could not see. Stated rather than implied: a plan built without ratings or
     * genres is still useful, but it should not look better informed than it was.
     */
    fun coverageDisclosures(coverage: GapPlanCoverage): List<String> = buildList {
        if (coverage.missingSelectedEstimate > 0) {
            add(
                "${coverage.missingSelectedEstimate} of your games have no completion length yet " +
                    "and were left out — they are unknown, not short.",
            )
        }
        if (coverage.withCachedReviews == 0 && coverage.visibleGames > 0) {
            add("No Steam ratings are cached yet, so ratings did not influence this plan.")
        }
        if (coverage.withKnownGenres == 0 && coverage.visibleGames > 0) {
            add("No Store genres are cached yet, so genre variety did not influence this plan.")
        }
    }

    /** One reason chip. Each states a fact; none renders a score. */
    fun reasonLabel(reason: GapPlanReason): String = when (reason) {
        is GapPlanReason.ReviewQuality ->
            "${reason.description} (${UiFormat.count(reason.total)} reviews)"
        is GapPlanReason.GenreAffinity -> "You have been playing ${reason.genreLabel}"
        is GapPlanReason.Progress ->
            "${UiFormat.minutes(reason.playedMinutes)} of ${UiFormat.minutes(reason.estimateMinutes)} played"
        is GapPlanReason.Fit -> "${UiFormat.minutes(reason.remainingMinutes)} left"
        GapPlanReason.FamilyShared -> "Family shared"
        is GapPlanReason.PlayingNow -> "${UiFormat.count(reason.players)} playing now"
    }

    fun validationMessage(error: GapPlanRequestError): String = when (error) {
        GapPlanRequestError.MISSING_TITLE -> "Name the game or update you are waiting for."
        GapPlanRequestError.TARGET_DATE_NOT_FUTURE -> "Choose a date after today."
        GapPlanRequestError.TARGET_DATE_BEYOND_HORIZON ->
            "Choose a date within about three years — a forecast further out would not mean much."
        GapPlanRequestError.MISSING_MANUAL_BUDGET ->
            "Enter how many hours you expect to have."
    }

    /** Why a one-off budget is being asked for, rather than just that it is required. */
    fun manualBudgetExplanation(): String =
        "There is not enough tracked play history yet to forecast your time, so enter roughly how " +
            "many hours you expect to have before then."

    fun reliablePaceExplanation(): String =
        "Your available time is forecast from recent tracked activity."

    fun saveFailureMessage(): String =
        "The collection could not be created. Your plan is still here — try again."
}
