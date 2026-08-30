package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.WishlistDao
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import com.example.backlogium.data.remote.SteamIconMapper
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * A discount the app is currently sure of: observed inside the freshness window and still running
 * as far as the last successful look could tell. A retained discount is deliberately not one —
 * a sale seen yesterday says nothing about today.
 */
val WishlistPrice.isLiveDiscount: Boolean
    get() = this is WishlistPrice.Observed && current && discountPercent > 0

/**
 * Whether the wishlist itself could be read on the last attempt. Distinct from having no
 * entries: the section must say "this could not be read" rather than appear empty.
 */
enum class WishlistAvailability {
    /** Nothing has been attempted yet this session. Whatever is stored is shown as it stands. */
    UNKNOWN,

    /** Steam listed the wishlist. Its entries are current as of the last refresh. */
    AVAILABLE,

    /** Steam answered and would not list it — the wishlist is not publicly readable. */
    NOT_READABLE,

    /** No answer at all: no network, an HTTP error, or a response shape gone unrecognisable. */
    UNREACHABLE,
}

/** What a refresh did, for callers that need to distinguish "skipped" from "nothing changed". */
enum class WishlistRefresh { REFRESHED, SKIPPED_FRESH, NOT_CONFIGURED, FAILED }

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
    private val credentials: CredentialsProvider,
    private val wishlistSource: SteamWishlistDataSource,
    private val priceSource: SteamStorePriceDataSource,
    private val time: TimeProvider,
) {
    private val refreshMutex = Mutex()
    private val availabilityState = MutableStateFlow(WishlistAvailability.UNKNOWN)

    /** Whether the wishlist could be read, so the section can explain itself when it could not. */
    val availability: StateFlow<WishlistAvailability> = availabilityState.asStateFlow()

    /**
     * Wishlisted games, each carrying the latest thing observed about its price: games on a live
     * discount first, then everything else, and Steam's own priority order within each group.
     *
     * Sorting on top of Steam's order at all is a deliberate departure — the player's ranking is
     * the whole reason the wishlist has one — but checking a wishlist is overwhelmingly checking
     * whether anything is on sale, and a sale buried at position forty is a sale the player finds
     * out about from somewhere else. The sort is stable, so within both groups the ranking they
     * set in Steam is exactly what they see.
     *
     * Only a *live* discount floats. A retained one is an observation about a day that has passed
     * and is not evidence of a sale running now; promoting it would dress a price the app is
     * unsure of as the most urgent thing on the list, which is the one thing every other decision
     * here has been careful not to do.
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
        items.filterNot { it.appId in owned }
            .map { item -> item.toDomain(latest[item.appId], now) }
            .sortedByDescending { it.price.isLiveDiscount }
    }

    /**
     * Refresh the wishlist and its prices, as the section is opened.
     *
     * Skipped when every entry already has an observation newer than [FRESHNESS_WINDOW_MILLIS],
     * so navigating back and forth does not re-request. [force] is for the periodic sampler,
     * which exists precisely to record history on days the player never opens the section.
     *
     * Nothing here can fail loudly. A wishlist that cannot be read leaves the stored entries and
     * their dated prices exactly as they were and only moves [availability]; a price chunk that
     * fails records nothing for its app ids, so their previous observations stand. The one thing
     * that must never happen is an absence being written over a price that was simply not
     * re-observed.
     */
    suspend fun refresh(force: Boolean = false): WishlistRefresh = refreshMutex.withLock {
        val steamId = (credentials.currentCredentials())?.steamId
        if (steamId.isNullOrBlank()) return@withLock WishlistRefresh.NOT_CONFIGURED

        if (!force && isFresh()) return@withLock WishlistRefresh.SKIPPED_FRESH

        // `loccountrycode` is a public profile location, not Steam's payment-derived Store Country.
        // No explicit store-country setting exists yet, so omit `cc` rather than asserting a region.
        val region: String? = null
        val listed = when (val fetch = wishlistSource.wishlistFor(steamId)) {
            is WishlistFetch.Entries -> {
                availabilityState.value = WishlistAvailability.AVAILABLE
                storeEntries(fetch.entries, region)
                true
            }

            WishlistFetch.NotReadable -> {
                availabilityState.value = WishlistAvailability.NOT_READABLE
                false
            }

            is WishlistFetch.Unreachable -> {
                availabilityState.value = WishlistAvailability.UNREACHABLE
                false
            }
        }

        // Prices are still worth refreshing when the list could not be re-read: the entries
        // already stored are the same games, and a stale price is the thing most worth replacing.
        val priced = refreshPrices(region)
        when {
            listed || priced -> WishlistRefresh.REFRESHED
            else -> WishlistRefresh.FAILED
        }
    }

    /**
     * Whether both the wishlist membership and every stored entry's price observation are inside
     * the freshness window. Price freshness alone is insufficient: a successful price pass after a
     * failed wishlist read must not suppress the next membership retry. An empty wishlist is never
     * "fresh": there may be entries Steam knows about that this app does not.
     */
    private suspend fun isFresh(): Boolean {
        // A failed membership read must always be retried while this repository still reports the
        // wishlist as unavailable, even if an earlier successful read was recent.
        if (availabilityState.value != WishlistAvailability.AVAILABLE) return false
        val now = time.nowMillis()
        val oldestMembershipRead = wishlistDao.oldestLastSeenAt() ?: return false
        if (now - oldestMembershipRead > FRESHNESS_WINDOW_MILLIS) return false
        val oldestPriceObservation = wishlistDao.oldestLatestObservationAt() ?: return false
        return now - oldestPriceObservation <= FRESHNESS_WINDOW_MILLIS
    }

    /**
     * Replace the stored entries with what Steam listed, keeping names and artwork already held
     * for entries the store does not answer for this time.
     */
    private suspend fun storeEntries(entries: List<WishlistEntry>, region: String?) {
        val now = time.nowMillis()
        if (entries.isEmpty()) {
            wishlistDao.deleteAllItems()
            return
        }

        val existing = wishlistDao.items().associateBy { it.appId }
        // Only entries with no name stored yet cost a details request: a name does not change,
        // and re-asking for the whole wishlist on every refresh would double the request count
        // for nothing. A blank one is retried, so a details lookup that failed once recovers.
        val needingDetails = entries
            .map { it.appId }
            .filter { existing[it]?.name.isNullOrBlank() }
        val details = wishlistSource.detailsFor(needingDetails, region)

        wishlistDao.upsertItems(
            entries.map { entry ->
                val known = existing[entry.appId]
                val fetched = details[entry.appId]
                WishlistItem(
                    appId = entry.appId,
                    name = fetched?.name ?: known?.name.orEmpty(),
                    artworkUrl = fetched?.artworkUrl
                        ?: known?.artworkUrl?.takeIf { it.isNotBlank() }
                        ?: SteamIconMapper.headerUrl(entry.appId),
                    priority = entry.priority,
                    addedAt = entry.addedAtMillis,
                    lastSeenAt = now,
                )
            },
        )
        wishlistDao.deleteItemsNotSeenSince(now)
    }

    /**
     * Observe prices for everything still wanted. Owned entries are left out of the request as
     * well as out of the section — there is no point pricing a game the player already has.
     *
     * Returns whether any observation was recorded at all, which is what separates "the refresh
     * did something" from "every chunk failed".
     */
    private suspend fun refreshPrices(region: String?): Boolean {
        val owned = gameDao.allAppIds().toHashSet()
        val appIds = wishlistDao.appIds().filterNot { it in owned }
        if (appIds.isEmpty()) return false

        val now = time.nowMillis()
        val batch = priceSource.pricesFor(appIds, region)
        if (batch.prices.isEmpty()) return false

        wishlistDao.insertObservations(
            batch.prices.map { (appId, price) ->
                when (price) {
                    is StorePrice.Amount -> WishlistPriceObservation(
                        appId = appId,
                        observedAt = now,
                        currency = price.currency,
                        finalMinorUnits = price.finalMinorUnits,
                        initialMinorUnits = price.initialMinorUnits,
                        discountPercent = price.discountPercent,
                        formatted = price.formatted,
                        listFormatted = price.listFormatted,
                    )
                    // An observed absence, recorded with its date. Nothing is written for the ids
                    // in `batch.unresolved`: a failed chunk is not an observation.
                    StorePrice.None -> WishlistPriceObservation(appId = appId, observedAt = now)
                }
            },
        )
        return true
    }

    private fun WishlistItem.toDomain(
        observation: WishlistPriceObservation?,
        now: Long,
    ) = WishlistGame(
        appId = appId,
        // The wishlist endpoint carries no name, and the store lookup that supplies one can fail
        // independently. Naming the app by its id says exactly what is known rather than leaving
        // a blank label under artwork that already shows the title.
        name = name.ifBlank { "Steam app $appId" },
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
