package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.WishlistGame
import com.example.backlogium.data.repo.WishlistPrice

data class WishlistPriceGroup internal constructor(val currency: String, val current: Boolean)

private data class RankedWishlistGame(
    val game: WishlistGame,
    val group: WishlistPriceGroup?,
    val payable: Long?,
)

private val priceGroupOrder = compareBy<WishlistPriceGroup?> { it == null }
    .thenByDescending { it?.current == true }
    .thenBy { it?.currency }

/** A null group contains entries without a comparable price. */
internal fun groupWishlistByPrice(
    games: List<WishlistGame>,
    descending: Boolean,
): Map<WishlistPriceGroup?, List<WishlistGame>> {
    val amountOrder = if (descending) compareByDescending(RankedWishlistGame::payable)
    else compareBy(RankedWishlistGame::payable)
    val entryOrder = amountOrder
        .thenBy { it.game.priority == 0 }
        .thenBy { it.game.priority }
        .thenBy { it.game.addedAt }
        .thenBy { it.game.appId }

    return games.map { game ->
        val observation = game.price as? WishlistPrice.Observed
        val rankable = observation?.rankablePrice
        val group = rankable?.let { WishlistPriceGroup(it.currency, observation.current) }
        RankedWishlistGame(game, group, rankable?.minorUnits)
    }.groupBy { it.group }
        .toSortedMap(priceGroupOrder)
        .mapValues { (_, group) -> group.sortedWith(entryOrder).map { it.game } }
}
