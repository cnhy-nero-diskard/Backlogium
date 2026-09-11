package com.example.backlogium.domain.gapplan

/**
 * Applies live current-player counts to an already-finalized snapshot.
 *
 * Pure, and deliberately incapable of doing more than it is allowed to. An available count may do
 * exactly two things: order multiplayer members **within the variant that already contains them**,
 * and supply a factual reason chip. It cannot add a game, remove one, or move one between
 * variants, because this function only ever reorders and annotates a list it is given.
 *
 * Letting counts swap otherwise-similar multiplayer alternatives was rejected. It would need an
 * invented similarity threshold, and it would make the same request produce different plans
 * depending on whether the network answered in time — contradicting the property a player is being
 * asked to commit a month to.
 */
object GapPlanDecoration {

    /**
     * Returns a snapshot whose variants carry the counts in [playerCounts].
     *
     * An app id absent from the map is *unavailable*, which is different from a successful count
     * of zero: an unavailable member gains no reason and contributes no ordering preference, while
     * a zero is a real fact and is stated as one.
     *
     * Single-player members are never reordered. Multiplayer members are sorted among themselves
     * by descending count, and — critically — they are placed back into the **positions the
     * multiplayer members already occupied**, so a single-player game never moves because a
     * neighbour's count arrived.
     */
    fun apply(
        snapshot: GapPlanSnapshot,
        playerCounts: Map<Long, Int>,
    ): GapPlanSnapshot = snapshot.copy(
        variants = snapshot.variants.map { variant -> variant.decorate(playerCounts) },
    )

    private fun GapPlanVariant.decorate(playerCounts: Map<Long, Int>): GapPlanVariant {
        val annotated = members.map { it.withCount(playerCounts[it.appId]) }
        val multiplayerSlots = annotated.withIndex()
            .filter { (_, member) -> member.multiplayer }
            .map { it.index }
        if (multiplayerSlots.size < 2) return copy(members = annotated)

        val reordered = multiplayerSlots
            .map { annotated[it] }
            .sortedWith(
                // Available counts first and highest first; unavailable members keep their
                // relative order behind them, and app id settles any remaining tie so the row
                // order is as reproducible as the membership.
                compareByDescending<GapPlanCandidate> { playerCounts[it.appId] != null }
                    .thenByDescending { playerCounts[it.appId] ?: 0 }
                    .thenBy { it.appId },
            )
        val members = annotated.toMutableList()
        multiplayerSlots.forEachIndexed { slot, index -> members[index] = reordered[slot] }
        return copy(members = members)
    }

    /**
     * A count is a live fact for this lifecycle only. It is appended as a reason rather than
     * stored on the candidate as a value, so nothing downstream can mistake it for something the
     * plan was built from — and a second decoration pass replaces it rather than stacking.
     */
    private fun GapPlanCandidate.withCount(players: Int?): GapPlanCandidate {
        val withoutStale = reasons.filterNot { it is GapPlanReason.PlayingNow }
        if (players == null) return copy(reasons = withoutStale)
        return copy(reasons = withoutStale + GapPlanReason.PlayingNow(players))
    }
}
