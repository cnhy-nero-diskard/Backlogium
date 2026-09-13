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
     * Draws all three tiers from one pool, then orders them by commitment.
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
            // makes rebuilding able to produce a different set at all. Necessary, not sufficient:
            // a choice that the ordering below undoes changes nothing the player sees, which is
            // why the caller compares actual picks rather than trusting this flag.
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
            picks = orderByCommitment(PlanIntensity.entries.map { drawn.getValue(it) }),
            canVary = canVary,
        )
    }

    /**
     * Finds a reachable draw whose visible app ids differ from [previousPickedAppIds], or returns
     * null when every reachable draw is the same.
     *
     * The repeated result is one event in the ordinary draw distribution. Its probability can be
     * calculated by following only choices whose app ids occur in [previousPickedAppIds]: choosing
     * anything else makes repetition impossible. The fallback then samples the complement of that
     * event, weighting each choice by its ordinary path probability. This is exact without visiting
     * every complete branch, and the work is bounded by the three tiers plus scans of the pool.
     */
    internal fun drawDifferent(
        pool: List<GapPlanCandidate>,
        fullCapacityMinutes: Int,
        seed: Long,
        previousPickedAppIds: List<Long>,
    ): Draw? {
        val previousIds = previousPickedAppIds.toSet()
        val random = Random(seed)
        val orderedPool = pool.sortedBy { it.appId }

        fun completeDraw(drawn: Map<PlanIntensity, GapPlanPick>, canVary: Boolean): Draw =
            Draw(
                picks = orderByCommitment(PlanIntensity.entries.map { drawn.getValue(it) }),
                canVary = canVary,
            )

        /** Probability that the suffix ends in a result other than the previous visible result. */
        fun nonRepeatProbability(
            tierIndex: Int,
            remaining: List<GapPlanCandidate>,
            drawn: Map<PlanIntensity, GapPlanPick>,
        ): Double {
            if (tierIndex == TIER_ORDER.size) {
                val visibleIds = completeDraw(drawn, canVary = false).picks
                    .mapNotNull { it.game?.appId }
                return if (visibleIds == previousPickedAppIds) 0.0 else 1.0
            }

            val intensity = TIER_ORDER[tierIndex]
            val budget = intensity.budgetMinutes(fullCapacityMinutes)
            val near = nearest(remaining, budget)
            if (near.isEmpty()) {
                val pick = GapPlanPick(intensity = intensity, budgetMinutes = budget, game = null)
                return nonRepeatProbability(
                    tierIndex = tierIndex + 1,
                    remaining = remaining,
                    drawn = drawn + (intensity to pick),
                )
            }

            // A branch outside the previous ids is already a non-repeat. Only the remaining
            // branches need recursive traversal to determine their non-repeat probability.
            var probability = 0.0
            for (chosen in near) {
                if (chosen.appId !in previousIds) {
                    probability += 1.0
                } else {
                    val pick = GapPlanPick(intensity, budget, chosen)
                    probability += nonRepeatProbability(
                        tierIndex = tierIndex + 1,
                        remaining = remaining - chosen,
                        drawn = drawn + (intensity to pick),
                    )
                }
            }
            return probability / near.size.toDouble()
        }

        if (nonRepeatProbability(0, orderedPool, emptyMap()) <= 0.0) return null

        fun sample(
            tierIndex: Int,
            remaining: List<GapPlanCandidate>,
            drawn: Map<PlanIntensity, GapPlanPick>,
            canVary: Boolean,
        ): Draw? {
            if (tierIndex == TIER_ORDER.size) {
                return completeDraw(drawn, canVary)
            }

            val intensity = TIER_ORDER[tierIndex]
            val budget = intensity.budgetMinutes(fullCapacityMinutes)
            val near = nearest(remaining, budget)
            if (near.isEmpty()) {
                val pick = GapPlanPick(intensity = intensity, budgetMinutes = budget, game = null)
                return sample(
                    tierIndex = tierIndex + 1,
                    remaining = remaining,
                    drawn = drawn + (intensity to pick),
                    canVary = canVary,
                )
            }

            val weights = DoubleArray(near.size)
            var totalWeight = 0.0
            for (index in near.indices) {
                val chosen = near[index]
                val nonRepeatProbability = if (chosen.appId in previousIds) {
                    val pick = GapPlanPick(intensity, budget, chosen)
                    nonRepeatProbability(
                        tierIndex = tierIndex + 1,
                        remaining = remaining - chosen,
                        drawn = drawn + (intensity to pick),
                    )
                } else {
                    1.0
                }
                val weight = nonRepeatProbability / near.size.toDouble()
                weights[index] = weight
                totalWeight += weight
            }
            if (totalWeight <= 0.0) return null

            var ticket = random.nextDouble() * totalWeight
            var chosen = near.last()
            for (index in near.indices) {
                ticket -= weights[index]
                if (ticket < 0.0) {
                    chosen = near[index]
                    break
                }
            }

            val pick = GapPlanPick(
                intensity = intensity,
                budgetMinutes = budget,
                game = chosen,
            )
            return sample(
                tierIndex = tierIndex + 1,
                remaining = remaining - chosen,
                drawn = drawn + (intensity to pick),
                canVary = canVary || near.size > 1,
            )
        }

        return sample(
            tierIndex = 0,
            remaining = orderedPool,
            drawn = emptyMap(),
            canVary = false,
        )
    }

    /**
     * Reassigns the drawn games to their tiers shortest-first, so a lower intensity can never offer
     * a longer commitment than a higher one.
     *
     * Targeting a share guarantees that ordering only when the eligible pool spans the request's
     * capacity. A library with few resolved HLTB lengths does not span it — and then several games
     * sit inside several tiers' nearness bands at once, and the draw is free to put the longer of
     * two clustered games at Relaxed and the shorter at Balanced. That reads as the Relaxed card
     * asking for *more* commitment than Balanced, which is the opposite of what its label promises.
     *
     * Ordering is imposed here rather than inside the draw. Constraining each lower tier to draw
     * only below the tier above would empty a tier where a distinct eligible game existed, which
     * contradicts the distinctness rule; reassignment leaves membership exactly as drawn.
     *
     * **No pick can overrun its new ceiling.** Shares increase with intensity, so pairing ascending
     * lengths with ascending shares is feasible whenever any pairing is: if the i-th shortest game
     * exceeded the i-th smallest share, then that game and every longer one — one more game than
     * there are wider tiers — would fit only the tiers above it, which the draw itself has already
     * ruled out.
     *
     * Tiers with no pick keep none; they are skipped rather than shifting the games between the
     * tiers that do have one.
     */
    private fun orderByCommitment(picks: List<GapPlanPick>): List<GapPlanPick> {
        // Stable, so two games of identical length keep the tiers they were drawn into. Settling
        // that tie on app id instead would make it seed-independent, and a reroll could then never
        // swap them — variation the player can actually see, between two differently named games.
        val byCommitment = picks.mapNotNull { it.game }.sortedBy { it.remainingMinutes }
        if (byCommitment.size < 2) return picks
        var next = 0
        return picks.map { pick ->
            if (pick.game == null) pick else pick.copy(game = byCommitment[next++])
        }
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
