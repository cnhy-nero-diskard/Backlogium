package com.example.backlogium.domain.gapplan

/**
 * Attaches live current-player counts to an already-finalized snapshot.
 *
 * Pure, and deliberately incapable of doing more than it is allowed to. An available count may do
 * exactly one thing: state a fact on the pick that already exists. It cannot change which game a
 * tier offers, because this function only ever appends to the facts of picks it is handed.
 *
 * With one game per tier there is no row order left for a count to influence either. That closed
 * the previous design's last avenue for a network fact to affect what the player sees: counts used
 * to reorder the multiplayer members inside a five-game variant, which was defensible but still
 * meant an enriched run and an offline run presented the same plan differently. Now they cannot.
 */
object GapPlanDecoration {

    /**
     * Returns a snapshot whose picks carry the counts in [playerCounts].
     *
     * An app id absent from the map is *unavailable*, which is different from a successful count of
     * zero: an unavailable pick gains no fact, while a zero is a real observation and is stated as
     * one.
     *
     * A second pass replaces any previous count rather than stacking a second one, so a decoration
     * that runs twice cannot leave a pick claiming two different numbers of players.
     */
    fun apply(
        snapshot: GapPlanSnapshot,
        playerCounts: Map<Long, Int>,
    ): GapPlanSnapshot = snapshot.copy(
        picks = snapshot.picks.map { pick ->
            val game = pick.game ?: return@map pick
            pick.copy(game = game.withCount(playerCounts[game.appId]))
        },
    )

    /**
     * A count is a live fact for this lifecycle only. It is appended to the presented facts rather
     * than stored as a value on the candidate, so nothing downstream can mistake it for something
     * the pick was drawn from.
     */
    private fun GapPlanCandidate.withCount(players: Int?): GapPlanCandidate {
        val withoutStale = facts.filterNot { it is GapPlanFact.PlayingNow }
        if (players == null) return copy(facts = withoutStale)
        return copy(facts = withoutStale + GapPlanFact.PlayingNow(players))
    }
}
