package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StorePriceOverviewDto
import kotlinx.serialization.SerializationException
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What the store answered about one app's price. Only these two outcomes may be recorded. */
sealed interface StorePrice {

    /**
     * A price in the requested region's currency. [formatted] is Steam's own rendering and the
     * only form safe to display; [listFormatted] is the struck-through pre-discount price and is
     * present only while [discountPercent] is non-zero.
     */
    data class Amount(
        val currency: String,
        val finalMinorUnits: Long,
        val initialMinorUnits: Long,
        val discountPercent: Int,
        val formatted: String,
        val listFormatted: String?,
    ) : StorePrice

    /**
     * Steam answered `data: []` — this app has no price. Free-to-play, unreleased, and
     * not-sold-in-this-region all arrive this way and the response does not say which, so this
     * carries no reason with it.
     */
    data object None : StorePrice
}

/**
 * One batched price lookup.
 *
 * [unresolved] is deliberately not folded into [prices] as a "no price": a chunk that failed, and
 * an app id Steam declined to describe, both say nothing about whether a price exists. Their
 * previously observed prices must stand rather than being overwritten with an absence.
 */
data class StorePriceBatch(
    val prices: Map<Long, StorePrice>,
    val unresolved: Set<Long>,
)

/**
 * Prices for a set of app ids, batched.
 *
 * A whole wishlist is one or two requests, which is why nothing here resembles the throttled,
 * resumable, one-app-at-a-time shape of [SteamStoreGenreDataSource]: `filters=price_overview` is
 * the one filter `appdetails` will answer for a list of ids at once.
 */
@Singleton
class SteamStorePriceDataSource @Inject constructor(
    private val api: SteamStoreApi,
) {
    /**
     * Look up [appIds] in [countryCode], or in whatever region Steam resolves when it is null.
     *
     * Chunked, and a failed chunk fails only its own app ids: a wishlist that spans two requests
     * must not lose the prices in the first because the second timed out.
     */
    suspend fun pricesFor(appIds: Collection<Long>, countryCode: String?): StorePriceBatch {
        val ids = appIds.distinct()
        if (ids.isEmpty()) return StorePriceBatch(emptyMap(), emptySet())

        val prices = mutableMapOf<Long, StorePrice>()
        val unresolved = mutableSetOf<Long>()

        for (chunk in ids.chunked(MAX_APPS_PER_REQUEST)) {
            val body = requestChunk(chunk, countryCode)
            if (body == null) {
                unresolved += chunk
                continue
            }
            for (appId in chunk) {
                val envelope = body[appId.toString()]
                // A missing entry, or `success = false`, is Steam declining to describe the id —
                // not an answer about whether it has a price.
                if (envelope == null || !envelope.success) {
                    unresolved += appId
                    continue
                }
                val overview = envelope.data?.priceOverview
                when {
                    overview == null -> prices[appId] = StorePrice.None
                    overview.finalFormatted.isBlank() -> unresolved += appId
                    else -> prices[appId] = overview.toAmount()
                }
            }
        }

        return StorePriceBatch(prices, unresolved)
    }

    private suspend fun requestChunk(
        chunk: List<Long>,
        countryCode: String?,
    ): Map<String, com.example.backlogium.data.remote.dto.StorePriceEnvelope>? = try {
        val response = api.appDetailsPrices(chunk.joinToString(","), countryCode)
        if (response.isSuccessful) response.body() else null
    } catch (_: IOException) {
        null
    } catch (_: HttpException) {
        null
    } catch (_: SerializationException) {
        // An undocumented endpoint changing shape is a failed chunk, not a crash: the retained
        // prices for these ids stay exactly as they were.
        null
    }

    private companion object {
        /**
         * Probed at 391 and again at 1191 ids in one request, both answered in full — there is no
         * low ceiling to design around. 100 is nonetheless what is asked for: the constraint that
         * bites first is URL length rather than any documented app cap, and a wishlist of any
         * realistic size is one or two requests at this size anyway. A failed chunk then costs a
         * hundred prices instead of all of them.
         */
        const val MAX_APPS_PER_REQUEST = 100
    }
}

private fun StorePriceOverviewDto.toAmount() = StorePrice.Amount(
    currency = currency,
    finalMinorUnits = finalMinorUnits,
    initialMinorUnits = initialMinorUnits,
    discountPercent = discountPercent,
    formatted = finalFormatted,
    // Empty at full price, so it is carried only when there is actually a discount to strike out.
    listFormatted = initialFormatted.takeIf { discountPercent > 0 && it.isNotBlank() },
)
