package com.example.backlogium.domain.gapplan

import com.example.backlogium.data.repo.GameReviewSummary

/**
 * Steam's review summary, as a fact a pick's card can state.
 *
 * This used to compute the lower bound of the 95% Wilson score interval, so that a game with nine
 * positive reviews could be *ranked* against one with two million. Nothing ranks any more, and the
 * confidence adjustment had no other consumer: a card states Steam's own description and the volume
 * behind it, which is the checkable form of the same information and needs no interval at all.
 *
 * The neutral constant that stood in for a missing summary went with it. It existed only to keep an
 * un-enriched game comparable inside a weighted sum, and a chosen 0.5 was always a claim the app
 * had no basis for. A game with no cached summary now simply has nothing to show here, which is the
 * honest version of the same absence.
 */
object ReviewQuality {

    /**
     * The fact this summary supports, or null when there is none.
     *
     * A missing or unavailable summary yields **no fact at all** rather than a placeholder: a card
     * must not claim a rating it does not have, and silence is the honest form of that. An
     * available summary always carries Steam's own words and counts, so what the card says is
     * checkable against the store page.
     */
    fun factFor(summary: GameReviewSummary?): GapPlanFact.Reviews? = when (summary) {
        null, GameReviewSummary.Unavailable -> null
        is GameReviewSummary.Available -> GapPlanFact.Reviews(
            description = summary.description,
            positive = summary.positive,
            total = summary.total,
        )
    }
}
