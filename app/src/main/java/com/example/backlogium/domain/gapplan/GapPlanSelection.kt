package com.example.backlogium.domain.gapplan

import kotlin.random.Random

/**
 * Draws one game per tier: uniformly at random, among the candidates nearest that tier's share of
 * capacity, with the three picks distinct.
 *
 * This replaces a bundle composer — a weighted `candidateQuality`, a `bundleValue` objective over
 * mean quality and budget utilization and pairwise genre diversity, and a fixed-width beam search
 * with a 32-entry frontier, a declared processing order, and a documented tie-break chain. All of
 * it existed to pick five games well and reproducibly. One game per tier needs no combination
 * search, and an unweighted draw needs no ranking, so none of it survives. The removal is recorded
 * in the change's design rather than left as a surprising absence.
 *
 * Two properties are load-bearing and both are testable from a JVM:
 *
 * - **Targeting, not capping.** A tier aims at its share rather than merely fitting under it. With
 *   a ceiling alone, a two-hour game satisfies Relaxed and Full equally and the three tiers could
 *   offer the same length, which would make choosing between them meaningless.
 * - **Reproducible from inputs plus seed.** The pool is ordered by app id before anything is drawn
 *   and the tiers are visited in a fixed order, so the same inputs and the same seed yield the same
 *   three picks. That is what lets a shown result hold still while a player decides whether to
 *   commit a month to it, while a new seed still genuinely rerolls.
 */
object GapPlanSelection {

    /**
     * How far below a tier's share still counts as "near" it, as a percent of that share.
     *
     * Wide enough that a reasonable library gives a tier several candidates to draw between —
     * which is the whole point, since a band of one makes the reroll control a no-op — and narrow
     * enough that the three bands cannot overlap. At 10%, Relaxed draws within `[0.63, 0.70]` of
     * capacity, Balanced within `[0.765, 0.85]`, and Full within `[0.90, 1.00]`, so whenever the
     * pool spans the request's range the picks come out in strictly descending length without a
     * separate ordering rule to enforce it.
     *
     * The band is measured against the tier's share rather than against the longest fitting game,
     * so it does not silently narrow on a library whose games are all far shorter than the gap.
     */
    const val NEARNESS_BAND_PERCENT = 10

    /** The three picks, and whether any tier had more than one candidate to draw between. */
    data class Draw(
        val picks: List<GapPlanPick>,
        val canVary: Boolean,
    )

    /**
     * Draws all three tiers from one pool.
     *
     * Tiers are visited **widest share first**. Full has the largest ceiling and should get first
     * claim on the longest fitting game; going the other way would let Relaxed take a game Full
     * needed and leave the top tier picking from what the bottom one rejected. Each pick is then
     * withdrawn from the pool, which is what makes the three distinct — and it is exactly the
     * "next-nearest eligible candidate" behaviour the spec asks for when one game is nearest for
     * more than one tier.
     */
    fun draw(
        pool: List<GapPlanCandidate>,
        fullCapacityMinutes: Int,
        seed: Long,
    ): Draw {
        val random = Random(seed)
        // Ordered once, by app id, so the index the generator produces means the same thing on
        // every run regardless of how the pool happened to be assembled.
        val remaining = pool.sortedBy { it.appId }.toMutableList()
        val drawn = mutableMapOf<PlanIntensity, GapPlanPick>()
        var canVary = false

        for (intensity in TIER_ORDER) {
            val budget = intensity.budgetMinutes(fullCapacityMinutes)
            val near = nearest(remaining, budget)
            // A tier with one option is forced whatever the seed is; one with several is what
            // makes rebuilding able to produce a different set at all.
            if (near.size > 1) canVary = true
            val chosen = if (near.isEmpty()) null else near[random.nextInt(near.size)]
            if (chosen != null) remaining.remove(chosen)
            drawn[intensity] = GapPlanPick(
                intensity = intensity,
                budgetMinutes = budget,
                game = chosen,
            )
        }

        // Presented in ascending intensity, which is the order the player reads them in, not the
        // order they were drawn in.
        return Draw(
            picks = PlanIntensity.entries.map { drawn.getValue(it) },
            canVary = canVary,
        )
    }

    /**
     * The candidates near [budgetMinutes]: those that fit it, within [NEARNESS_BAND_PERCENT] of it
     * of the longest one that does.
     *
     * Anchoring the band's top on the longest *fitting* candidate rather than on the share itself
     * keeps the set non-empty whenever anything fits. A band anchored on the share would return
     * nothing for a library whose games are all well under the gap, which is a real and ordinary
     * case — a short backlog and a distant release date — and answering it with "no game fits"
     * would be false.
     */
    internal fun nearest(
        pool: List<GapPlanCandidate>,
        budgetMinutes: Int,
    ): List<GapPlanCandidate> {
        if (budgetMinutes <= 0) return emptyList()
        val fitting = pool.filter { it.remainingMinutes <= budgetMinutes }
        val longest = fitting.maxOfOrNull { it.remainingMinutes } ?: return emptyList()
        val tolerance = (budgetMinutes.toLong() * NEARNESS_BAND_PERCENT / 100).toInt()
        return fitting.filter { it.remainingMinutes >= longest - tolerance }
    }

    /** Widest share first. Fixed, because the draw order is part of what makes a seed meaningful. */
    private val TIER_ORDER: List<PlanIntensity> =
        PlanIntensity.entries.sortedByDescending { it.percent }
}
