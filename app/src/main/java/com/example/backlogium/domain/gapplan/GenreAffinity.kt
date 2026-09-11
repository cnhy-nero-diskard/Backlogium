package com.example.backlogium.domain.gapplan

import java.time.LocalDate
import kotlin.math.pow

/** One completed session reduced to what affinity actually reads: which game, on which local date. */
data class PlayedDate(
    val appId: Long,
    val date: LocalDate,
)

/**
 * Inferred taste, derived from what the player recently chose to play rather than from anything
 * they were asked to declare.
 *
 * Two decisions keep this honest and are both load-bearing:
 *
 * **Active dates, not minutes.** A game contributes at most once per date to each of its genres,
 * however long it was played that day. An endless game, or one unattended weekend marathon, would
 * otherwise dominate the profile and every plan after it would be built from a single outlier.
 *
 * **The same recency decay as Personal Pace.** 56 completed days with a 28-day half-life, so the
 * two derivations agree about what "recently" means instead of quietly disagreeing.
 *
 * Nothing here is persisted. There is no durable taste profile to go stale, be wrong about a
 * player who has changed, or need a way to correct.
 */
object GenreAffinity {

    /**
     * The value used when a game's genres are unknown, or when there is no history to compare them
     * to. Neutral for the same reason [ReviewQuality.NEUTRAL] is: an un-enriched game must not be
     * ranked below one the player's own history actively disfavours.
     */
    const val NEUTRAL = 0.5

    const val LOOKBACK_DAYS: Long = 56L
    private const val HALF_LIFE_DAYS = 28.0

    /**
     * Recency-weighted genre weights, normalized so the strongest observed genre is 1.0.
     *
     * Normalization is what makes the result comparable across players: an absolute weighted count
     * would mean something different for someone who plays daily than for someone who plays twice
     * a month, and the ranking only ever needs the relative shape.
     *
     * [genreIdsByApp] supplies each played game's genres; a game with none simply contributes
     * nothing, which is different from contributing zero to every genre.
     */
    fun weights(
        playedDates: List<PlayedDate>,
        genreIdsByApp: Map<Long, List<String>>,
        today: LocalDate,
    ): Map<String, Double> {
        val earliest = today.minusDays(LOOKBACK_DAYS)
        val latest = today.minusDays(1)
        val counted = mutableSetOf<Triple<Long, LocalDate, String>>()
        val totals = mutableMapOf<String, Double>()

        for (played in playedDates) {
            if (played.date.isBefore(earliest) || played.date.isAfter(latest)) continue
            val genres = genreIdsByApp[played.appId].orEmpty()
            if (genres.isEmpty()) continue
            val weight = recencyWeight(played.date, latest)
            for (genreId in genres.distinct()) {
                // At most one contribution per game, per date, per genre — the marathon guard.
                if (!counted.add(Triple(played.appId, played.date, genreId))) continue
                totals[genreId] = (totals[genreId] ?: 0.0) + weight
            }
        }

        val strongest = totals.values.maxOrNull() ?: return emptyMap()
        if (strongest <= 0.0) return emptyMap()
        return totals.mapValues { (_, total) -> (total / strongest).coerceIn(0.0, 1.0) }
    }

    /**
     * A candidate's affinity, and the reason it justifies.
     *
     * The mean of the game's *known* genre weights, so a four-genre game is not penalised against
     * a one-genre game simply for being described in more detail. A game whose genres are unknown,
     * or whose genres the player has no history with, scores [NEUTRAL] and carries no reason —
     * the plan does not claim a preference it cannot show.
     *
     * The reason names the single strongest matching genre, because "you have been playing Action"
     * is a fact the player can check and "your genre affinity is 0.72" is not.
     */
    fun scoreAndReason(
        genreIds: List<String>,
        weights: Map<String, Double>,
        genreLabels: Map<String, String>,
    ): Pair<Double, GapPlanReason?> {
        if (genreIds.isEmpty() || weights.isEmpty()) return NEUTRAL to null
        val matched = genreIds.distinct().mapNotNull { id -> weights[id]?.let { id to it } }
        if (matched.isEmpty()) return NEUTRAL to null
        val score = matched.sumOf { it.second } / matched.size
        // Ties resolve on genre id so the named genre is stable across identical runs.
        val strongest = matched.sortedWith(
            compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first },
        ).first()
        val label = genreLabels[strongest.first]
        return score.coerceIn(0.0, 1.0) to label?.let(GapPlanReason::GenreAffinity)
    }

    private fun recencyWeight(date: LocalDate, latest: LocalDate): Double {
        val age = (latest.toEpochDay() - date.toEpochDay()).coerceAtLeast(0)
        return 2.0.pow(-age.toDouble() / HALF_LIFE_DAYS)
    }
}

/**
 * How far through the selected estimate an already-started game is, as a fraction.
 *
 * Zero for an unplayed game — which is a real zero, not a missing value: "has made no progress" is
 * something the library definitely knows. This is the smallest component of candidate quality by
 * design, so it nudges a nearly-finished game upward without letting every plan become backlog
 * cleanup.
 */
object CompletionMomentum {

    fun scoreAndReason(
        playedMinutes: Int,
        estimateMinutes: Int,
    ): Pair<Double, GapPlanReason?> {
        if (estimateMinutes <= 0 || playedMinutes <= 0) return 0.0 to null
        val fraction = (playedMinutes.toDouble() / estimateMinutes.toDouble()).coerceIn(0.0, 1.0)
        return fraction to GapPlanReason.Progress(playedMinutes, estimateMinutes)
    }
}
