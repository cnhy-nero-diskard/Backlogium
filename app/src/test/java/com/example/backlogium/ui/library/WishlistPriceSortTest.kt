package com.example.backlogium.ui.library

import com.example.backlogium.data.repo.WishlistGame
import com.example.backlogium.data.repo.WishlistPrice
import org.junit.Assert.assertEquals
import org.junit.Test

class WishlistPriceSortTest {
    @Test fun directionChangesOnlyThePayableOrderWithinACurrencyAndFreshnessGroup() {
        val games = listOf(
            game(1, observed(800, discount = 80, listFormatted = "USD 40.00"), priority = 1),
            game(2, observed(900), priority = 2),
            game(3, observed(0, explicitZero = true, currency = " usd "), priority = 3),
            game(4, observed(10, currency = "PHP")),
            game(5, observed(20, currency = "PHP")),
            game(6, observed(800, currency = "CAD", current = false)),
            game(7, observed(600, currency = "CAD", current = false)),
            game(8, observed(300, current = false)),
            game(9, observed(0), priority = 1),
            game(10, observed(-1), priority = 2),
            game(11, observed(null), priority = 3),
            game(12, observed(1, currency = "ZZZ"), priority = 4),
            game(13, observed(2, currency = " "), priority = 5),
            game(14, observed(3, currency = null), priority = 6),
            game(15, observed(4, formatted = " "), priority = 7),
            game(16, WishlistPrice.Unavailable(10), priority = 8),
            game(17, WishlistPrice.NeverObserved, priority = 9),
        ).reversed()

        val ascending = groupWishlistByPrice(games, descending = false)
        val descending = groupWishlistByPrice(games, descending = true)
        val groups = listOf(
            WishlistPriceGroup("PHP", current = true),
            WishlistPriceGroup("USD", current = true),
            WishlistPriceGroup("CAD", current = false),
            WishlistPriceGroup("USD", current = false),
            null,
        )
        assertEquals(groups, ascending.keys.toList())
        assertEquals(groups, descending.keys.toList())
        assertEquals(listOf(4L, 5L, 3L, 1L, 2L, 7L, 6L, 8L) + (9L..17L), ascending.ids())
        assertEquals(listOf(5L, 4L, 2L, 1L, 3L, 6L, 7L, 8L) + (9L..17L), descending.ids())
    }

    @Test fun equalAmountsAndUnrankedEntriesUseTheSameSteamTieBreaksInBothDirections() {
        val games = listOf(
            game(5, observed(800), priority = 0, addedAt = 1),
            game(4, observed(800), priority = Int.MAX_VALUE),
            game(3, observed(800), priority = 8, addedAt = 3),
            game(2, observed(800), priority = 8, addedAt = 1),
            game(1, observed(800), priority = 8, addedAt = 1),
        )
        val unranked = games.map { it.copy(appId = it.appId + 10, price = WishlistPrice.NeverObserved) }

        for (descending in listOf(false, true)) {
            assertEquals(
                listOf(1L, 2L, 3L, 4L, 5L, 11L, 12L, 13L, 14L, 15L),
                groupWishlistByPrice(games + unranked, descending).ids(),
            )
        }
    }

    private fun Map<WishlistPriceGroup?, List<WishlistGame>>.ids() = values.flatten().map { it.appId }

    private fun game(appId: Long, price: WishlistPrice, priority: Int = 0, addedAt: Long = 0) =
        WishlistGame(appId, "Game $appId", "", priority, addedAt, price, "")

    private fun observed(
        amount: Long?,
        currency: String? = "USD",
        current: Boolean = true,
        explicitZero: Boolean = false,
        discount: Int = 0,
        formatted: String = "Steam price",
        listFormatted: String? = null,
    ) = WishlistPrice.Observed(formatted, listFormatted, discount, 10, current, amount, currency, explicitZero)
}
