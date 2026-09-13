package com.example.backlogium.ui.gapplan

import com.example.backlogium.domain.gapplan.CapacityProvenance
import com.example.backlogium.domain.gapplan.GapPlanCoverage
import com.example.backlogium.domain.gapplan.GapPlanFact
import com.example.backlogium.domain.gapplan.GapPlanIntent
import com.example.backlogium.domain.gapplan.GapPlanRequestError
import com.example.backlogium.domain.gapplan.PlanIntensity
import com.example.backlogium.ui.util.UiFormat

/**
 * Where a game stands with Steam's reviewers, in the three bands worth colouring.
 *
 * Derived from the positive-to-total ratio, never from the review description's words. Steam's own
 * bands are ratio bands, and the description is localized text — matching "Positive" against it is
 * the same mistake the participation-category decision already rejected, and would silently
 * mis-colour every response the moment the endpoint answers in another language.
 */
enum class ReviewStanding {
    POSITIVE,
    MIXED,
    NEGATIVE,
}

/**
 * Every user-facing string the gap-plan surface shows, as pure functions.
 *
 * Kept out of the composables so the wording can be asserted directly — several of these are the
 * *only* place a missing fact is disclosed, and a test that could only reach them through a
 * rendered tree would be easy to leave un-asserted.
 *
 * The copy here is deliberately terse. An earlier version stated every figure as its own sentence,
 * which was individually defensible and collectively unreadable: the three recommendations did not
 * fit one screen, and a surface the player skims past discloses nothing at all whatever it says.
 * Anything that exists in proportion to something else is now drawn rather than narrated.
 */
object GapPlanPresentation {

    fun intensityName(intensity: PlanIntensity): String = when (intensity) {
        PlanIntensity.RELAXED -> "Relaxed"
        PlanIntensity.BALANCED -> "Balanced"
        PlanIntensity.FULL -> "Full"
    }

    /** The tier's share, as a bare percent. The bar beside it carries what that means. */
    fun intensityShare(intensity: PlanIntensity): String = "${intensity.percent}%"

    fun intentLabel(intent: GapPlanIntent): String = when (intent) {
        GapPlanIntent.STORY -> "Story"
        GapPlanIntent.COMPLETIONIST -> "Completionist"
    }

    fun intentBasisLabel(intent: GapPlanIntent): String = when (intent) {
        GapPlanIntent.STORY -> "Main Story"
        GapPlanIntent.COMPLETIONIST -> "Completionist"
    }

    /**
     * The request, as one line, once a result exists and the form has collapsed.
     *
     * Everything the player chose, in the order they chose it, so reopening the form to change one
     * input is a decision they can make without reopening it first.
     */
    fun requestSummary(result: GapPlanResultUi): String =
        "${result.anticipatedTitle} · ${result.targetDate} · ${intentLabel(result.intent)}"

    /** The whole forecast, stated once. The per-tier bars are measured against it. */
    fun fullCapacity(fullCapacityMinutes: Int): String = UiFormat.minutes(fullCapacityMinutes)

    /**
     * Where a manual budget came from, kept because presenting one as a Personal Pace forecast
     * would attribute a confidence the data cannot support. The reliable case says nothing: a
     * forecast is the default, and labelling the default is noise.
     */
    fun capacityCaveat(provenance: CapacityProvenance): String? = when (provenance) {
        CapacityProvenance.PERSONAL_PACE -> null
        CapacityProvenance.MANUAL -> "Your estimate, not a forecast"
    }

    /** This tier's share and the pick's length, as the bar's caption. */
    fun pickAgainstShare(pick: GapPlanPickUi): String {
        val share = UiFormat.minutes(pick.budgetMinutes)
        val game = pick.game ?: return share
        return "${UiFormat.minutes(game.remainingMinutes)} of $share"
    }

    /** Store genres, or null when none are cached — never an empty line or an invented genre. */
    fun genreLine(game: GapPlanGameUi): String? =
        game.genreLabels.takeIf { it.isNotEmpty() }?.joinToString(", ")

    /** The lead-in on every card. The feature offers games; it does not describe a gap. */
    fun recommendationHook(): String = "You might like"

    fun emptyPickMessage(): String = "Nothing fits this much time"

    /**
     * What the cards cannot show, in the fewest words that keep the distinction that matters: a
     * game with no cached length is *unknown*, not short. Nothing else here needs saying — a
     * suggestion with no rating is still a valid suggestion, because nothing was ranked.
     */
    fun coverageDisclosures(coverage: GapPlanCoverage): List<String> = buildList {
        if (coverage.missingSelectedEstimate > 0) {
            add("${coverage.missingSelectedEstimate} games skipped — length unknown, not short")
        }
        if (coverage.withCachedReviews == 0 && coverage.visibleGames > 0) {
            add("No ratings cached yet")
        }
    }

    /**
     * Why a rebuild changed nothing.
     *
     * The state exists because a control that appears ignored is worse than no control — the exact
     * failure the single rebuild control was introduced to fix.
     */
    fun rebuildDidNotVaryMessage(): String = "No other games of these lengths to offer"

    /**
     * That the three were not judged, in one line.
     *
     * Cut from a paragraph, not dropped: it is the honest posture the whole selection design rests
     * on, and without it the top card reads as the recommended one.
     */
    fun selectionExplanation(): String = "Picked at random from what fits"

    /** A pick's remaining work. Icon-led at the call site, so the value stands alone here. */
    fun remaining(game: GapPlanGameUi): String = UiFormat.minutes(game.remainingMinutes)

    /**
     * Where this game stands with reviewers.
     *
     * Steam's bands, as ratios: positive from 70%, mixed from 40%, negative below. A summary with
     * no reviews at all has no standing to report rather than a default one.
     */
    fun reviewStanding(fact: GapPlanFact.Reviews): ReviewStanding? {
        if (fact.total <= 0) return null
        val ratio = fact.positive.toDouble() / fact.total.toDouble()
        return when {
            ratio >= POSITIVE_RATIO -> ReviewStanding.POSITIVE
            ratio >= MIXED_RATIO -> ReviewStanding.MIXED
            else -> ReviewStanding.NEGATIVE
        }
    }

    /** Steam's own words plus the volume behind them, compacted. */
    fun reviewLabel(fact: GapPlanFact.Reviews): String =
        "${fact.description} · ${UiFormat.compactCount(fact.total)}"

    fun playerCountLabel(fact: GapPlanFact.PlayingNow): String =
        UiFormat.compactCount(fact.players)

    /** Played against the estimate, as the progress bar's caption. */
    fun progressLabel(fact: GapPlanFact.Progress): String =
        "${UiFormat.minutes(fact.playedMinutes)} in"

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
        "Not enough tracked history to forecast your time — roughly how many hours will you have?"

    fun reliablePaceExplanation(): String = "Time forecast from your recent activity"

    fun saveFailureMessage(): String = "Could not create the collection — try again"

    /** Steam's band boundaries, named so the colouring is not three magic numbers. */
    private const val POSITIVE_RATIO = 0.70
    private const val MIXED_RATIO = 0.40
}
