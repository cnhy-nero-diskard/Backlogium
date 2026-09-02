package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.dto.StoreItemsResponse
import com.example.backlogium.data.remote.dto.WishlistResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Fixtures recorded from `IWishlistService/GetWishlist/v1` and `IStoreBrowseService/GetItems/v1`.
 *
 * The load-bearing one is [NOT_READABLE]: a wishlist that cannot be read answers `HTTP 200` with
 * the `items` key absent rather than an empty array, and that absence is the only thing separating
 * "private" from "nothing wishlisted". A DTO defaulting the list to empty would erase it.
 */
class SteamWishlistDataSourceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test fun entries_keepSteamsOwnPriorityAndAreDatedInMillis() = runBlocking {
        val fetch = source(wishlist = ENTRIES).wishlistFor("76561197979911851")

        assertEquals(
            WishlistFetch.Entries(
                listOf(
                    WishlistEntry(appId = 1620, priority = 0, addedAtMillis = 1_572_456_900_000L),
                    WishlistEntry(appId = 34180, priority = 879, addedAtMillis = 1_549_370_695_000L),
                ),
            ),
            fetch,
        )
    }

    @Test fun anAbsentItemsKey_isUnreadable_notEmpty() = runBlocking {
        assertEquals(WishlistFetch.NotReadable, source(wishlist = NOT_READABLE).wishlistFor("1"))
    }

    @Test fun anEmptyItemsArray_isAnEmptyWishlist() = runBlocking {
        assertEquals(WishlistFetch.Entries(emptyList()), source(wishlist = EMPTY).wishlistFor("1"))
    }

    @Test fun offlineAndMalformed_areUnreachable_soRetainedEntriesStand() = runBlocking {
        assertTrue(source(wishlistFailure = IOException("offline")).wishlistFor("1") is WishlistFetch.Unreachable)
        assertTrue(source(wishlist = MALFORMED).wishlistFor("1") is WishlistFetch.Unreachable)
    }

    @Test fun details_useTheStoresOwnAssetPath() = runBlocking {
        val details = source(storeItems = STORE_ITEMS).detailsFor(listOf(440, 292030), "PH")

        assertEquals(
            WishlistItemDetails(
                name = "Team Fortress 2",
                artworkUrl = "https://cdn.cloudflare.steamstatic.com/steam/apps/440/header.jpg?t=1757348372",
            ),
            details[440L],
        )
        // No assets block: the well-known path is the fallback rather than a blank image.
        assertEquals(
            WishlistItemDetails(
                name = "The Witcher 3: Wild Hunt",
                artworkUrl = "https://cdn.cloudflare.steamstatic.com/steam/apps/292030/header.jpg",
            ),
            details[292030L],
        )
    }

    @Test fun detailsSteamWouldNotAnswerFor_areOmittedRatherThanBlank() = runBlocking {
        val details = source(storeItems = STORE_ITEMS).detailsFor(listOf(440, 292030, 11), "PH")

        assertTrue(11L !in details)
    }

    @Test fun detailsFailure_leavesTheCallerWithWhatItAlreadyHad() = runBlocking {
        val details = source(storeItemsFailure = IOException("offline")).detailsFor(listOf(440), "PH")

        assertTrue(details.isEmpty())
    }

    @Test fun anUnknownRegion_isOmittedFromTheStoreContext() = runBlocking {
        val api = api(storeItems = STORE_ITEMS)

        SteamWishlistDataSource(api).detailsFor(listOf(440), null)

        assertTrue("country_code" !in api.lastStoreItemsInput.orEmpty())
        assertTrue("\"appid\":440" in api.lastStoreItemsInput.orEmpty())
    }

    /**
     * `include_basic_info` is what actually carries the `name` field on this endpoint — without
     * it Steam answers `success:1` with assets attached but an empty name, and every entry falls
     * back to its bare app id. Regression coverage for that exact failure mode.
     */
    @Test fun theStoreRequest_asksForBasicInfoSoNamesComeBack() = runBlocking {
        val api = api(storeItems = STORE_ITEMS)

        SteamWishlistDataSource(api).detailsFor(listOf(440), "PH")

        assertTrue("\"include_basic_info\":true" in api.lastStoreItemsInput.orEmpty())
    }

    private fun source(
        wishlist: String = NOT_READABLE,
        wishlistFailure: Throwable? = null,
        storeItems: String = EMPTY_STORE_ITEMS,
        storeItemsFailure: Throwable? = null,
    ) = SteamWishlistDataSource(api(wishlist, wishlistFailure, storeItems, storeItemsFailure))

    private fun api(
        wishlist: String = NOT_READABLE,
        wishlistFailure: Throwable? = null,
        storeItems: String = EMPTY_STORE_ITEMS,
        storeItemsFailure: Throwable? = null,
    ) = FakeWishlistApi(
        wishlist = {
            wishlistFailure?.let { throw it }
            // A body the app cannot decode arrives exactly as the converter would raise it.
            json.decodeFromString<WishlistResponse>(wishlist)
        },
        storeItems = {
            storeItemsFailure?.let { throw it }
            json.decodeFromString<StoreItemsResponse>(storeItems)
        },
    )

    private companion object {
        const val ENTRIES = """{"response":{"items":[{"appid":1620,"priority":0,"date_added":1572456900},{"appid":34180,"priority":879,"date_added":1549370695}]}}"""

        /** What a wishlist that is not publicly readable actually returns. */
        const val NOT_READABLE = """{"response":{}}"""

        const val EMPTY = """{"response":{"items":[]}}"""

        const val MALFORMED = """{"response":{"items":[{"appid":"""

        const val EMPTY_STORE_ITEMS = """{"response":{"store_items":[]}}"""

        const val STORE_ITEMS = """{"response":{"store_items":[{"item_type":0,"id":440,"success":1,"visible":true,"name":"Team Fortress 2","appid":440,"assets":{"asset_url_format":"steam/apps/440/${'$'}{FILENAME}?t=1757348372","header":"header.jpg"}},{"id":292030,"success":1,"visible":true,"name":"The Witcher 3: Wild Hunt","appid":292030},{"id":11,"success":2,"visible":false,"name":""}]}}"""
    }
}
