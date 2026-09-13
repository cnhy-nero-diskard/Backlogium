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
 * It no longer selects anything. A weighted affinity score used to be 30% of a composite that
 * ranked candidates, which meant a derivation that can mistake repeated obligation for enjoyment
 * was quietly deciding what the app offered. Now it only ever names a genre on a pick that happens
 * to match — "you have been playing Action" — which the player can dismiss in a glance if it is
 * wrong. The neutral constant for an unknown genre went with the score: nothing is being compared,
 * so there is nothing for a placeholder to keep comparable.
 *
 * Two decisions still keep the derivation honest and are both load-bearing:
 *
 * **Active dates, not minutes.** A game contributes at most once per date to each of its genres,
 * however long it was played that day. An endless game, or one unattended weekend marathon, would
 * otherwise dominate the profile.
 *
 * **The same recency decay as Personal Pace.** 56 completed days with a 28-day half-life, so the
 * two derivations agree about what "recently" means instead of quietly disagreeing.
 *
 * Nothing here is persisted. There is no durable taste profile to go stale, be wrong about a player
 * who has changed, or need a way to correct.
 */
object GenreAffinity {

    const val LOOKBACK_DAYS: Long = 56L
    private const val HALF_LIFE_DAYS = 28.0

    /**
     * Recency-weighted genre weights, normalized so the strongest observed genre is 1.0.
     *
     * Normalization is what keeps the weights comparable within one player's history: an absolute
     * weighted count would mean something different for someone who plays daily than for someone
     * who plays twice a month, and only the relative shape is ever read — to find which of a
     * candidate's genres the player has most been playing.
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
     * The affinity fact for a candidate, or null when there is nothing to say.
     *
     * The fact names the single strongest matching genre, because "you have been playing Action"
     * is something the player can check and "your genre affinity is 0.72" is not. A game whose
     * genres are unknown, or whose genres the player has no recent history with, carries no fact —
     * the card does not claim a preference it cannot show.
     */
    fun factFor(
        genreIds: List<String>,
        weights: Map<String, Double>,
        genreLabels: Map<String, String>,
    ): GapPlanFact.GenreAffinity? {
        if (genreIds.isEmpty() || weights.isEmpty()) return null
        val matched = genreIds.distinct().mapNotNull { id -> weights[id]?.let { id to it } }
        if (matched.isEmpty()) return null
        // Ties resolve on genre id so the named genre is stable across identical runs.
        val strongest = matched.sortedWith(
            compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first },
        ).first()
        return genreLabels[strongest.first]?.let(GapPlanFact::GenreAffinity)
    }

    private fun recencyWeight(date: LocalDate, latest: LocalDate): Double {
        val age = (latest.toEpochDay() - date.toEpochDay()).coerceAtLeast(0)
        return 2.0.pow(-age.toDouble() / HALF_LIFE_DAYS)
    }
}

/**
 * How far through the selected estimate an already-started game is.
 *
 * Stated as the two minute figures it was derived from, not as the fraction it used to contribute
 * to candidate quality. The fraction was the smallest term of a composite whose job was to nudge a
 * nearly-finished game upward; with nothing being ranked, what remains is the useful half — a card
 * saying "9h of 15h played", which is a fact the player can weigh for themselves.
 */
object CompletionMomentum {

    /** Null for an unplayed game: "no progress" is worth showing nowhere. */
    fun factFor(playedMinutes: Int, estimateMinutes: Int): GapPlanFact.Progress? {
        if (estimateMinutes <= 0 || playedMinutes <= 0) return null
        return GapPlanFact.Progress(playedMinutes, estimateMinutes)
    }
}
