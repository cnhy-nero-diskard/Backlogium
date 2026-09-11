package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary
import kotlin.math.sqrt

/**
 * Confidence-adjusted review quality: the lower bound of the 95% Wilson score interval for the
 * positive proportion.
 *
 * A raw percentage cannot compare a game with nine positive reviews to one with two million. The
 * Wilson lower bound answers the question actually being asked — "how good is this game, given how
 * much we know about it" — so a tiny perfect sample lands below an established, slightly less
 * perfect one instead of topping the list.
 */
object ReviewQuality {

    /**
     * The value substituted for a game with no cached review summary.
     *
     * A *chosen* neutral, not an absence, and the consequence is worth stating plainly: on a fresh
     * install, an offline device, or any library the bounded enrichment has not yet reached, every
     * candidate shares this constant, 80% of candidate quality becomes constant once genre
     * affinity is also neutral, and ranking collapses onto completion momentum and the
     * bundle-level terms. That is the intended degradation — a plan built from duration and
     * progress alone, disclosed as such by the coverage figures.
     *
     * Zero was rejected: it would rank an un-enriched game below a game the player's own library
     * facts rate genuinely badly, which is a claim the app has no basis for.
     */
    const val NEUTRAL = 0.5

    /** 1.959964…, the two-sided 95% normal quantile. Named so the interval is not a magic number. */
    private const val Z_95 = 1.959963984540054

    /**
     * The quality score for a summary, and the reason it justifies.
     *
     * A missing or unavailable summary contributes [NEUTRAL] and **no reason at all**: a plan must
     * not claim a rating fact it does not have, and silence is the honest form of that. An
     * available summary always carries its reason, so every review-influenced ranking is
     * checkable against Steam's own words and counts.
     */
    fun scoreAndReason(summary: GameReviewSummary?): Pair<Double, GapPlanReason?> = when (summary) {
        null, GameReviewSummary.Unavailable -> NEUTRAL to null
        is GameReviewSummary.Available -> {
            val score = wilsonLowerBound(summary.positive, summary.negative)
            score to GapPlanReason.ReviewQuality(
                description = summary.description,
                positive = summary.positive,
                total = summary.total,
            )
        }
    }

    /**
     * The 95% Wilson lower bound for [positive] successes out of `positive + negative` trials.
     *
     * Returns [NEUTRAL] for an empty sample rather than 0.0 — no reviews is no information, and
     * the two must not be confused here any more than they are at the cache boundary. The result
     * is clamped to `0.0..1.0` so a floating-point edge can never push a component outside the
     * range the weighted sum assumes.
     */
    fun wilsonLowerBound(positive: Int, negative: Int): Double {
        val total = positive.toLong() + negative.toLong()
        if (total <= 0L || positive < 0 || negative < 0) return NEUTRAL
        val n = total.toDouble()
        val observed = positive.toDouble() / n
        val z2 = Z_95 * Z_95
        val denominator = 1.0 + z2 / n
        val centre = observed + z2 / (2.0 * n)
        val margin = Z_95 * sqrt((observed * (1.0 - observed) + z2 / (4.0 * n)) / n)
        return ((centre - margin) / denominator).coerceIn(0.0, 1.0)
    }
}
