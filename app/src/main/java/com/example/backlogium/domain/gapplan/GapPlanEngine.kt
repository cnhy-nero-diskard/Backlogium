package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.PersonalPaceProfile
import java.time.LocalDate

/**
 * Everything one generation reads, gathered before any of it runs.
 *
 * A single snapshot of inputs rather than a set of live flows: the engine must be able to say that
 * two variants were built from the same facts, which is not true if each one re-reads a stream
 * that can emit between them.
 */
data class GapPlanInputs(
    val games: List<GapPlanGame>,
    /** Completed sessions in the affinity window, reduced to game-and-date. */
    val playedDates: List<PlayedDate>,
    val reviewsByAppId: Map<Long, GameReviewSummary>,
    /** Store genre id to its label, for naming a genre reason the player can recognise. */
    val genreLabels: Map<String, String>,
    val paceProfile: PersonalPaceProfile,
    /**
     * Freshly read at generation time, never captured at construction. A long-lived process that
     * crossed local midnight with a stale date would forecast from the wrong "tomorrow".
     */
    val today: LocalDate,
)

/**
 * The pure gap-plan engine.
 *
 * No Room, no Retrofit, no Android, no clock. Everything it needs arrives as plain values, which
 * is what lets the whole of eligibility, scoring, composition, and explanation be exercised
 * exhaustively from a JVM test — including the cases a device can only reach by accident, like an
 * empty library or a pool where every candidate ties.
 */
object GapPlanEngine {

    /**
     * Generates all three variants from local state alone.
     *
     * Membership is a function of the inputs and nothing else. No network fact participates, so an
     * offline device, a timed-out enrichment, and a fully enriched run produce exactly the same
     * games — which is the property that lets a player commit a month to the result.
     */
    fun generate(request: GapPlanRequest, inputs: GapPlanInputs): Result<GapPlanSnapshot> {
        val capacity = request.resolveCapacity(inputs.paceProfile, inputs.today)
            .getOrElse { return Result.failure(it) }

        val fullBudget = PlanIntensity.FULL.budgetMinutes(capacity.fullCapacityMinutes)
        val eligibility = deriveEligibility(
            games = inputs.games,
            request = request,
            fullBudgetMinutes = fullBudget,
            hasCachedReview = { appId -> inputs.reviewsByAppId.containsKey(appId) },
        )

        val weights = GenreAffinity.weights(
            playedDates = inputs.playedDates,
            genreIdsByApp = inputs.games.associate { it.appId to it.genreIds },
            today = inputs.today,
        )
        val pool = eligibility.eligible.map { it.toCandidate(inputs, weights) }

        val variants = PlanIntensity.entries.map { intensity ->
            val budget = intensity.budgetMinutes(capacity.fullCapacityMinutes)
            GapPlanVariant(
                intensity = intensity,
                budgetMinutes = budget,
                members = GapPlanComposer.compose(pool, budget),
            )
        }

        return Result.success(
            GapPlanSnapshot(
                request = request,
                capacity = capacity,
                variants = variants,
                coverage = eligibility.coverage,
                eligiblePool = pool.sortedWith(GapPlanComposer.PROCESSING_ORDER),
            ),
        )
    }

    /**
     * Builds one candidate, retaining each component's value alongside the reason it produced.
     *
     * Score and reason come from the same call for each component deliberately. Deriving the
     * number in one place and the explanation in another is how a plan ends up ranking a game for
     * a reason it does not show, or showing one it did not use.
     */
    private fun EligibleGame.toCandidate(
        inputs: GapPlanInputs,
        weights: Map<String, Double>,
    ): GapPlanCandidate {
        val (reviewScore, reviewReason) =
            ReviewQuality.scoreAndReason(inputs.reviewsByAppId[game.appId])
        val (affinityScore, affinityReason) =
            GenreAffinity.scoreAndReason(game.genreIds, weights, inputs.genreLabels)
        val (momentumScore, momentumReason) =
            CompletionMomentum.scoreAndReason(playedMinutes, estimateMinutes)

        return GapPlanCandidate(
            appId = game.appId,
            name = game.name,
            source = game.source,
            remainingMinutes = remainingMinutes,
            estimateMinutes = estimateMinutes,
            playedMinutes = playedMinutes,
            genreIds = game.genreIds,
            multiplayer = game.multiplayer,
            reviewQuality = reviewScore,
            genreAffinity = affinityScore,
            completionMomentum = momentumScore,
            // Fit is always available and always stated, so a game recommended on duration and
            // library facts alone still explains itself rather than appearing unjustified.
            reasons = listOfNotNull(
                GapPlanReason.Fit(remainingMinutes),
                reviewReason,
                affinityReason,
                momentumReason,
                GapPlanReason.FamilyShared.takeIf { game.source == GameSource.FAMILY_SHARED },
            ),
        )
    }
}
