package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.WishlistDao
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** A wishlisted game as the section presents it. Never a [LibraryGame]: it is wanted, not owned. */
data class WishlistGame(
    val appId: Long,
    val name: String,
    val artworkUrl: String,
    /** Steam's own priority. 0 means unprioritized; entries arrive already in Steam's order. */
    val priority: Int,
    val addedAt: Long,
    val price: WishlistPrice,
    val storeUrl: String,
)

/**
 * What is known about a wishlisted game's price, and how sure the app is of it.
 *
 * The four states are distinct because the section has to render them differently and none of
 * them may be shown as a zero, a dash, or a blank — each of which reads as a price.
 */
sealed interface WishlistPrice {

    /**
     * A price Steam gave, with when it was observed. [current] is false once the observation is
     * older than [WishlistRepository.FRESHNESS_WINDOW_MILLIS], at which point it must be
     * presented as a retained price with its date rather than as today's.
     */
    data class Observed(
        val formatted: String,
        /** The struck-through list price, non-null only while a discount is active. */
        val listFormatted: String?,
        val discountPercent: Int,
        val observedAt: Long,
        val current: Boolean,
    ) : WishlistPrice

    /**
     * Steam answered, and this app has no price. Free-to-play, unreleased, and not-sold-in-region
     * all arrive this way and the response does not distinguish them, so neither does this: the
     * app states that no price is available rather than inventing a reason.
     */
    data class Unavailable(val observedAt: Long) : WishlistPrice

    /** No observation has ever been recorded, so the app claims nothing at all. */
    data object NeverObserved : WishlistPrice
}

/**
 * The wishlist as the app holds it: Steam's entries, their latest observed prices, and the
 * reconciliation that keeps an already-owned game from being presented as still wanted.
 *
 * Room entities stay inside this layer; everything crossing out of it is a [WishlistGame].
 */
@Singleton
class WishlistRepository @Inject constructor(
    private val wishlistDao: WishlistDao,
    private val gameDao: GameDao,
    private val time: TimeProvider,
) {
    /**
     * Wishlisted games in Steam's priority order, each carrying the latest thing observed about
     * its price.
     *
     * Ownership is reconciled here, at read time, rather than by deleting rows on purchase.
     * Steam removes a game from the wishlist when it is bought, but gifts, keys, and family
     * additions all produce windows where a game is owned and still listed — and a sync that
     * first reports ownership must be reflected immediately, without waiting for the next
     * wishlist refresh. One set intersection over data already in Room buys both.
     */
    val wishlist: Flow<List<WishlistGame>> = combine(
        wishlistDao.observeItems(),
        wishlistDao.observeLatestPrices(),
        gameDao.observeAppIds(),
    ) { items, observations, ownedAppIds ->
        val owned = ownedAppIds.toHashSet()
        val latest = observations.associateBy { it.appId }
        val now = time.nowMillis()
        items.filterNot { it.appId in owned }.map { item -> item.toDomain(latest[item.appId], now) }
    }

    private fun WishlistItem.toDomain(
        observation: WishlistPriceObservation?,
        now: Long,
    ) = WishlistGame(
        appId = appId,
        name = name,
        artworkUrl = artworkUrl,
        priority = priority,
        addedAt = addedAt,
        price = observation.toPrice(now),
        storeUrl = storeUrlFor(appId),
    )

    private fun WishlistPriceObservation?.toPrice(now: Long): WishlistPrice = when {
        this == null -> WishlistPrice.NeverObserved
        formatted.isNullOrBlank() -> WishlistPrice.Unavailable(observedAt)
        else -> WishlistPrice.Observed(
            formatted = formatted,
            listFormatted = listFormatted?.takeIf { it.isNotBlank() },
            discountPercent = discountPercent ?: 0,
            observedAt = observedAt,
            current = now - observedAt <= FRESHNESS_WINDOW_MILLIS,
        )
    }

    companion object {
        /**
         * How long an observed price stands as "current" before it is presented as retained, and
         * the window that suppresses a re-request when the section is reopened.
         *
         * Six hours, deliberately the inverse of `GameGenreRepository`'s thirty *days*. Same host,
         * opposite volatility: a genre is effectively immutable, while a price changes on Steam's
         * sale calendar and a day-old one can be wrong by 80%. The genre path's tuning would have
         * been the wrong answer here even before batching made its throttling unnecessary.
         */
        const val FRESHNESS_WINDOW_MILLIS = 6L * 60 * 60 * 1000

        /**
         * The web store URL, not a `steam://` one. Android resolves it to the Steam app when that
         * app is installed and claims the link, and to a browser when it is not — where a custom
         * scheme would simply fail to resolve for anyone without Steam installed.
         */
        fun storeUrlFor(appId: Long) = "https://store.steampowered.com/app/$appId"
    }
}
