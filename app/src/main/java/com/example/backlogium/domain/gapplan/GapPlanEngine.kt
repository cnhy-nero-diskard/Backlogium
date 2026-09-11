package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import com.example.backlogium.domain.PersonalPaceProfile
import java.time.LocalDate

/**
 * Everything one generation reads, gathered before any of it runs.
 *
 * A single snapshot of inputs rather than a set of live flows: the engine must be able to say that
 * all three picks were drawn from the same facts, which is not true if each tier re-reads a stream
 * that can emit between them.
 */
data class GapPlanInputs(
    val games: List<GapPlanGame>,
    /** Completed sessions in the affinity window, reduced to game-and-date. */
    val playedDates: List<PlayedDate>,
    val reviewsByAppId: Map<Long, GameReviewSummary>,
    /** Store genre id to its label, for naming the genres a card shows. */
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
 * No Room, no Retrofit, no Android, no clock, and no random source of its own. Everything it needs
 * arrives as plain values — the seed included — which is what lets eligibility, capacity, the draw,
 * and the presented facts be exercised exhaustively from a JVM test, including the cases a device
 * can only reach by accident: an empty library, a pool where every candidate ties, a tier nothing
 * fits.
 */
object GapPlanEngine {

    /**
     * Draws one pick per tier from local state alone.
     *
     * The picks are a function of the inputs and [seed], and of nothing else. No network fact
     * participates, so an offline device, a timed-out enrichment, and a fully enriched run all
     * offer exactly the same three games — which is the property that lets a player commit a month
     * to what they are looking at.
     *
     * [seed] is supplied by the caller rather than drawn here. That is what makes a reroll a real
     * one: the previous design's membership was fully determined by the inputs, so with nothing
     * else varying the rebuild control could only ever return what was already on screen.
     */
    fun generate(
        request: GapPlanRequest,
        inputs: GapPlanInputs,
        seed: Long,
    ): Result<GapPlanSnapshot> {
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
        val draw = GapPlanSelection.draw(
            pool = pool,
            fullCapacityMinutes = capacity.fullCapacityMinutes,
            seed = seed,
        )

        return Result.success(
            GapPlanSnapshot(
                request = request,
                capacity = capacity,
                seed = seed,
                picks = draw.picks,
                coverage = eligibility.coverage,
                canVary = draw.canVary,
            ),
        )
    }

    /**
     * Builds one candidate: the work figures the draw reads, and the facts its card will state.
     *
     * Nothing derived here influences whether this candidate is chosen. The facts are attached at
     * the same point the numbers are so a card cannot end up describing a different game's state
     * than the one the tier offers.
     */
    private fun EligibleGame.toCandidate(
        inputs: GapPlanInputs,
        weights: Map<String, Double>,
    ): GapPlanCandidate = GapPlanCandidate(
        appId = game.appId,
        name = game.name,
        source = game.source,
        remainingMinutes = remainingMinutes,
        estimateMinutes = estimateMinutes,
        playedMinutes = playedMinutes,
        // Resolved to labels here rather than in the UI: the labels come from the same library
        // join the rest of the app reads, so a pick names a genre in the app's own words.
        genreLabels = game.genreIds.mapNotNull(inputs.genreLabels::get),
        multiplayer = game.multiplayer,
        facts = listOfNotNull(
            ReviewQuality.factFor(inputs.reviewsByAppId[game.appId]),
            GenreAffinity.factFor(game.genreIds, weights, inputs.genreLabels),
            CompletionMomentum.factFor(playedMinutes, estimateMinutes),
        ),
    )
}
