package com.example.backlogium.domain.gapplan

/**
 * Composes one variant's membership from the eligible pool.
 *
 * The objective is deliberately non-monotone: mean candidate quality rewards small sets while
 * budget utilization rewards large ones, so a partial combination's value bounds nothing about the
 * value of any combination extending it. That means the pruning rule is not an implementation
 * detail — it decides the answer — and every part of it is fixed and documented here rather than
 * left to whatever order a collection happened to arrive in.
 *
 * A greedy next-best-game walk was rejected: an early medium-length pick can block a later
 * combination with better total quality, fit, and variety. An exhaustive search was rejected too —
 * `n choose 5` is unbounded for a large library. A fixed-width beam with a fixed processing order
 * is the compromise that makes "identical inputs produce identical plans" a testable property
 * rather than an aspiration.
 */
object GapPlanComposer {

    /** Partial combinations retained after each candidate is considered. */
    const val FRONTIER_WIDTH = 32

    const val QUALITY_WEIGHT = 0.60
    const val UTILIZATION_WEIGHT = 0.30
    const val DIVERSITY_WEIGHT = 0.10

    /**
     * Diversity for a bundle that cannot have a meaningful one — fewer than two members, or no
     * pair whose genres are both known. Neutral rather than maximally diverse: an unknown genre is
     * not evidence of variety, and scoring it as 1.0 would reward exactly the games the enrichment
     * has not reached yet.
     */
    const val NEUTRAL_DIVERSITY = 0.5

    /**
     * The order candidates are considered in: quality first, then the cheapest, then app id.
     *
     * The app-id term is what makes the whole thing reproducible. Without a total order, two
     * candidates equal on every derived value would be processed in whatever order the pool
     * happened to be built in, and the beam would keep a different pair on different runs.
     */
    val PROCESSING_ORDER: Comparator<GapPlanCandidate> =
        compareByDescending<GapPlanCandidate> { it.quality }
            .thenBy { it.remainingMinutes }
            .thenBy { it.appId }

    /**
     * Compares finished bundles, and partial ones — the frontier is keyed on exactly the score a
     * finished bundle would receive, so a partial combination is never ranked by a different
     * measure than the one that will eventually judge it.
     *
     * After the objective: more planned minutes wins (the time was there to use), then fewer
     * members (a focused plan over a padded one), then the lexicographically smaller app-id list,
     * which is a total order and therefore the last word.
     */
    private fun comparator(budgetMinutes: Int): Comparator<Bundle> =
        compareByDescending<Bundle> { it.value(budgetMinutes) }
            .thenByDescending { it.plannedMinutes }
            .thenBy { it.members.size }
            .thenBy(APP_ID_LEXICOGRAPHIC) { it.sortedAppIds }

    /**
     * The best bundle of one to five candidates fitting [budgetMinutes].
     *
     * Returns an empty list when nothing fits, which is a real answer — the variant then says so
     * rather than stretching its budget or borrowing from another variant.
     */
    fun compose(
        candidates: List<GapPlanCandidate>,
        budgetMinutes: Int,
        maxMembers: Int = GapPlanVariant.MAX_MEMBERS,
    ): List<GapPlanCandidate> {
        if (budgetMinutes <= 0 || candidates.isEmpty() || maxMembers <= 0) return emptyList()
        val ordered = candidates.sortedWith(PROCESSING_ORDER)
        val compare = comparator(budgetMinutes)

        var frontier = listOf(Bundle.EMPTY)
        var best = Bundle.EMPTY

        for (candidate in ordered) {
            if (candidate.remainingMinutes > budgetMinutes) continue
            val extended = mutableListOf<Bundle>()
            for (partial in frontier) {
                if (partial.members.size >= maxMembers) continue
                // Uniqueness is structural: each candidate is offered to the frontier exactly
                // once, so no combination can contain the same game twice.
                if (partial.plannedMinutes + candidate.remainingMinutes > budgetMinutes) continue
                val next = partial.plus(candidate)
                extended += next
                if (compare.compare(next, best) < 0) best = next
            }
            if (extended.isEmpty()) continue
            frontier = (frontier + extended).sortedWith(compare).take(FRONTIER_WIDTH)
        }

        return best.members
    }

    /**
     * How much of *this variant's own* budget a bundle uses. Measuring against the request's full
     * capacity instead would make every Relaxed plan look like an under-use of time the intensity
     * deliberately withheld, and push the search to overfill it.
     */
    internal fun utilization(plannedMinutes: Int, budgetMinutes: Int): Double =
        if (budgetMinutes <= 0) 0.0
        else (plannedMinutes.toDouble() / budgetMinutes.toDouble()).coerceIn(0.0, 1.0)

    /**
     * Mean pairwise genre distinctness, over the pairs where both games' genres are known.
     *
     * Jaccard overlap inverted: two games sharing every genre contribute 0.0, two sharing none
     * contribute 1.0. Pairs involving an unknown-genre game are skipped rather than scored, so
     * missing data neither rewards nor punishes — if no pair qualifies, the whole term is neutral.
     */
    internal fun diversity(members: List<GapPlanCandidate>): Double {
        if (members.size < 2) return NEUTRAL_DIVERSITY
        var total = 0.0
        var pairs = 0
        for (i in members.indices) {
            val first = members[i].genreIds.toSet()
            if (first.isEmpty()) continue
            for (j in i + 1 until members.size) {
                val second = members[j].genreIds.toSet()
                if (second.isEmpty()) continue
                val union = first.size + second.size - first.count { it in second }
                val overlap = if (union == 0) 0.0 else first.count { it in second }.toDouble() / union
                total += 1.0 - overlap
                pairs++
            }
        }
        return if (pairs == 0) NEUTRAL_DIVERSITY else total / pairs
    }

    /**
     * A combination under construction. Immutable, so a frontier entry can be extended without any
     * chance of mutating the entry another extension is still reading.
     */
    private data class Bundle(
        val members: List<GapPlanCandidate>,
        val plannedMinutes: Int,
        val qualityTotal: Double,
    ) {
        val sortedAppIds: List<Long> get() = members.map { it.appId }.sorted()

        fun plus(candidate: GapPlanCandidate) = Bundle(
            members = members + candidate,
            plannedMinutes = plannedMinutes + candidate.remainingMinutes,
            qualityTotal = qualityTotal + candidate.quality,
        )

        /**
         * The bundle objective.
         *
         * Mean candidate quality rather than summed: a sum would give five mediocre one-hour games
         * an automatic advantage over two excellent long ones, which is the opposite of what a
         * focused five-game plan is for. Utilization still rewards actually using the window.
         */
        fun value(budgetMinutes: Int): Double {
            if (members.isEmpty()) return 0.0
            return QUALITY_WEIGHT * (qualityTotal / members.size) +
                UTILIZATION_WEIGHT * utilization(plannedMinutes, budgetMinutes) +
                DIVERSITY_WEIGHT * diversity(members)
        }

        companion object {
            val EMPTY = Bundle(emptyList(), 0, 0.0)
        }
    }

    /** Element-wise, then shorter-first — a total order over app-id lists. */
    private val APP_ID_LEXICOGRAPHIC: Comparator<List<Long>> = Comparator { left, right ->
        val shared = minOf(left.size, right.size)
        for (index in 0 until shared) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return@Comparator comparison
        }
        left.size.compareTo(right.size)
    }
}
