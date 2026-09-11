package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.ui.util.UiFormat

/**
 * Every user-facing string the gap-plan surface shows, as pure functions.
 *
 * Kept out of the composables so the wording can be asserted directly — several of these sentences
 * are the *only* place a missing fact is disclosed, and a test that could only reach them through a
 * rendered tree would be easy to leave un-asserted.
 */
object GapPlanPresentation {

    fun intensityName(intensity: PlanIntensity): String = when (intensity) {
        PlanIntensity.RELAXED -> "Relaxed"
        PlanIntensity.BALANCED -> "Balanced"
        PlanIntensity.FULL -> "Full"
    }

    /**
     * States the intensity as a share of capacity it aims at, not one it merely stays under.
     *
     * "Around" rather than "up to" is the whole distinction between this design and the one it
     * replaced: a tier targets its share, so a Relaxed pick is a genuinely shorter commitment
     * rather than any short game that happens to fit.
     */
    fun intensityRule(intensity: PlanIntensity): String =
        "Around ${intensity.percent}% of your forecast time"

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
     * The full forecast, stated once for all three tiers.
     *
     * Without this line a Relaxed card showing only its own smaller share would present that
     * figure as all the time the player has — concealing exactly the time the intensity
     * deliberately withheld, which is the opposite of what the choice is for.
     */
    fun fullCapacityLine(fullCapacityMinutes: Int): String =
        "You have about ${UiFormat.minutes(fullCapacityMinutes)} before then."

    /** This tier's own share of the forecast, which its pick is drawn to approach. */
    fun pickShareLine(pick: GapPlanPickUi): String =
        "Planning around ${UiFormat.minutes(pick.budgetMinutes)} of it"

    /** The share of the forecast this tier is deliberately not planning against. */
    fun withheldLine(pick: GapPlanPickUi, fullCapacityMinutes: Int): String? {
        val withheld = fullCapacityMinutes - pick.budgetMinutes
        if (withheld <= 0) return null
        return "Holding back ${UiFormat.minutes(withheld)} of your forecast."
    }

    /** A pick's remaining work, stated once, in the most prominent place on its card. */
    fun remainingLine(game: GapPlanGameUi): String = "${UiFormat.minutes(game.remainingMinutes)} left"

    /** Store genres, or null when none are cached — never an empty line or an invented genre. */
    fun genreLine(game: GapPlanGameUi): String? =
        game.genreLabels.takeIf { it.isNotEmpty() }?.joinToString(" · ")

    fun emptyPickMessage(): String =
        "No game with a known length fits this much time. Try a longer gap or a fuller plan."

    /**
     * What the cards will not be able to show. Stated rather than implied: a suggestion with no
     * rating and no genres is still a perfectly valid suggestion, because nothing was ranked — but
     * its card will be sparse, and saying so is better than looking under-built.
     */
    fun coverageDisclosures(coverage: GapPlanCoverage): List<String> = buildList {
        if (coverage.missingSelectedEstimate > 0) {
            add(
                "${coverage.missingSelectedEstimate} of your games have no completion length yet " +
                    "and were left out — they are unknown, not short.",
            )
        }
        if (coverage.withCachedReviews == 0 && coverage.visibleGames > 0) {
            add("No Steam ratings are cached yet, so these cards cannot show them.")
        }
        if (coverage.withKnownGenres == 0 && coverage.visibleGames > 0) {
            add("No Store genres are cached yet, so these cards cannot show them.")
        }
    }

    /**
     * Why a rebuild changed nothing.
     *
     * The state exists at all because a control that appears ignored is worse than no control. The
     * previous surface had exactly that problem for a different reason — picks were fully
     * determined by the inputs, so rebuilding could only return them — and the fix is not to hide
     * the control but to explain the one case where it still cannot help.
     */
    fun rebuildDidNotVaryMessage(): String =
        "There are not enough games of the right lengths to offer a different set. Widen the " +
            "included games, or try a different date."

    /** One fact label. Each states something checkable; none renders a score. */
    fun factLabel(fact: GapPlanFact): String = when (fact) {
        is GapPlanFact.Reviews ->
            "${fact.description} (${UiFormat.count(fact.total)} reviews)"
        is GapPlanFact.GenreAffinity -> "You have been playing ${fact.genreLabel}"
        is GapPlanFact.Progress ->
            "${UiFormat.minutes(fact.playedMinutes)} of ${UiFormat.minutes(fact.estimateMinutes)} played"
        is GapPlanFact.PlayingNow -> "${UiFormat.count(fact.players)} playing now"
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

    /**
     * How the picks were chosen, said plainly on the result.
     *
     * The app is offering three fitting games and showing what is known about each, rather than
     * claiming to have judged them. A player who is not told that would reasonably assume the top
     * card is the recommended one.
     */
    fun selectionExplanation(): String =
        "Three games that fit, picked at random from what your library offers. The facts are here " +
            "so you can judge them — rebuild for a different set."

    fun saveFailureMessage(): String =
        "The collection could not be created. Your plan is still here — try again."
}
