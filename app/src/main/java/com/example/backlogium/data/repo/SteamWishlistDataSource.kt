package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamIconMapper
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/** One wishlisted app as Steam holds it: no name and no artwork, which arrive separately. */
data class WishlistEntry(
    val appId: Long,
    /** The player's own ordering. 0 means unprioritized, not "first". */
    val priority: Int,
    val addedAtMillis: Long,
)

/** What the store says about a wishlisted app beyond its price. */
data class WishlistItemDetails(
    val name: String,
    val artworkUrl: String,
)

/**
 * The result boundary for a wishlist read. The three outcomes are kept apart because the section
 * has to say something different about each, and because only [Entries] may replace what is
 * stored — the other two leave retained entries exactly as they were.
 */
sealed interface WishlistFetch {

    /** The wishlist as Steam listed it. An empty list is a genuinely empty wishlist. */
    data class Entries(val entries: List<WishlistEntry>) : WishlistFetch

    /**
     * Steam answered, and would not list the wishlist: `HTTP 200` with no `items` key at all,
     * which is what a wishlist that is not publicly readable returns. Permanent until the player
     * changes their privacy settings, so it is worth explaining rather than silently retrying.
     */
    data object NotReadable : WishlistFetch

    /** No answer: no network, an HTTP error, or a response shape the app no longer recognises. */
    data class Unreachable(val cause: Throwable? = null) : WishlistFetch
}

/**
 * Reads the wishlist and the store details its entries need.
 *
 * Both calls are credential-free and batched. Neither is documented or versioned — Steam withdrew
 * the previous wishlist endpoint outright in 2024 — so every failure here resolves to a state the
 * section can render, never to an exception that reaches the rest of the app.
 */
@Singleton
class SteamWishlistDataSource @Inject constructor(
    private val api: SteamApi,
) {
    suspend fun wishlistFor(steamId: String): WishlistFetch = try {
        val items = api.getWishlist(steamId).response.items
        if (items == null) {
            WishlistFetch.NotReadable
        } else {
            WishlistFetch.Entries(
                items.filter { it.appId > 0 }.map { item ->
                    WishlistEntry(
                        appId = item.appId,
                        priority = item.priority,
                        addedAtMillis = TimeUnit.SECONDS.toMillis(item.dateAddedSeconds),
                    )
                },
            )
        }
    } catch (error: IOException) {
        WishlistFetch.Unreachable(error)
    } catch (error: HttpException) {
        WishlistFetch.Unreachable(error)
    } catch (error: SerializationException) {
        WishlistFetch.Unreachable(error)
    }

    /**
     * Names and artwork for [appIds], batched the same way prices are.
     *
     * Returns only what the store answered for. A missing entry is not an error and not a blank
     * name — the caller keeps whatever it already stored for that app, which is why a failed
     * lookup here degrades to a stale name rather than an entry that loses its identity.
     */
    suspend fun detailsFor(
        appIds: Collection<Long>,
        countryCode: String?,
    ): Map<Long, WishlistItemDetails> {
        val ids = appIds.distinct()
        if (ids.isEmpty()) return emptyMap()

        val details = mutableMapOf<Long, WishlistItemDetails>()
        for (chunk in ids.chunked(MAX_APPS_PER_REQUEST)) {
            val items = try {
                api.getStoreItems(storeItemsInput(chunk, countryCode)).response.storeItems
            } catch (_: IOException) {
                continue
            } catch (_: HttpException) {
                continue
            } catch (_: SerializationException) {
                continue
            }
            for (item in items) {
                val appId = item.appId ?: item.id
                val name = item.name.trim()
                if (item.success != STORE_ITEM_SUCCESS || appId <= 0 || name.isEmpty()) continue
                details[appId] = WishlistItemDetails(
                    name = name,
                    artworkUrl = SteamIconMapper
                        .storeAssetUrl(item.assets?.assetUrlFormat, item.assets?.header)
                        .ifEmpty { SteamIconMapper.headerUrl(appId) },
                )
            }
        }
        return details
    }

    /**
     * This endpoint's calling convention: ids, context, and requested blocks as one JSON query
     * parameter. Built rather than string-formatted so an app id can never break out of the
     * document.
     *
     * Unlike the price request, this endpoint answers `x-eresult: 8` (`InvalidParam`) — an empty
     * `{"response":{}}`, indistinguishable from every other failure this class already treats as
     * "answered nothing" — when `country_code` is absent from `context` at all. A fallback of "US"
     * here is a technical default for resolving a name and an asset path, not a stand-in for the
     * player's real store region: pricing is a separate request that already carries the actual
     * configured region, and this fallback never reaches it.
     */
    private fun storeItemsInput(appIds: List<Long>, countryCode: String?): String {
        val input = JsonObject(
            mapOf(
                "ids" to JsonArray(
                    appIds.map { JsonObject(mapOf("appid" to JsonPrimitive(it))) },
                ),
                "context" to JsonObject(
                    buildMap {
                        put("language", JsonPrimitive(LANGUAGE))
                        put("country_code", JsonPrimitive(countryCode?.takeIf { it.isNotBlank() } ?: "US"))
                    },
                ),
                "data_request" to JsonObject(
                    mapOf(
                        // Without this, Steam still answers success:1 with assets attached but an
                        // empty name — the name field rides on basic_info, not on the base item.
                        "include_basic_info" to JsonPrimitive(true),
                        "include_assets" to JsonPrimitive(true),
                    ),
                ),
            ),
        )
        return Json.encodeToString(JsonObject.serializer(), input)
    }

    private companion object {
        const val LANGUAGE = "english"

        /** Steam's "the store answered for this id"; anything else describes no app. */
        const val STORE_ITEM_SUCCESS = 1

        /** Matched to the price request's chunk size so the two paths stay in step. */
        const val MAX_APPS_PER_REQUEST = 100
    }
}
