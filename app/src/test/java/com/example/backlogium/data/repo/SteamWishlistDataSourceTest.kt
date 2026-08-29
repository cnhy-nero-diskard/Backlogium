package com.example.backlogium.data.repo

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.remote.dto.StoreItemsResponse
import com.example.backlogium.data.remote.dto.WishlistResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
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
        val api = FakeSteamApi(storeItems = json.decodeFromString(STORE_ITEMS))

        SteamWishlistDataSource(api).detailsFor(listOf(440), null)

        assertTrue("country_code" !in api.storeItemsInput.orEmpty())
        assertTrue("\"appid\":440" in api.storeItemsInput.orEmpty())
    }

    private fun source(
        wishlist: String = NOT_READABLE,
        wishlistFailure: Throwable? = null,
        storeItems: String = EMPTY_STORE_ITEMS,
        storeItemsFailure: Throwable? = null,
    ) = SteamWishlistDataSource(
        FakeSteamApi(
            wishlist = runCatching { json.decodeFromString<WishlistResponse>(wishlist) }.getOrNull(),
            wishlistFailure = wishlistFailure,
            storeItems = runCatching { json.decodeFromString<StoreItemsResponse>(storeItems) }.getOrNull(),
            storeItemsFailure = storeItemsFailure,
        ),
    )

    /** Only the two wishlist endpoints are reachable; anything else is a test failure. */
    private class FakeSteamApi(
        private val wishlist: WishlistResponse? = null,
        private val wishlistFailure: Throwable? = null,
        private val storeItems: StoreItemsResponse? = null,
        private val storeItemsFailure: Throwable? = null,
    ) : SteamApi {
        var storeItemsInput: String? = null
            private set

        override suspend fun getWishlist(steamId: String, scope: SyncRunRecorder.RunScope?): WishlistResponse {
            wishlistFailure?.let { throw it }
            // A body the app cannot decode arrives exactly as the converter would raise it.
            return wishlist ?: throw SerializationException("unrecognised")
        }

        override suspend fun getStoreItems(inputJson: String, scope: SyncRunRecorder.RunScope?): StoreItemsResponse {
            storeItemsInput = inputJson
            storeItemsFailure?.let { throw it }
            return storeItems ?: throw SerializationException("unrecognised")
        }

        override suspend fun getOwnedGames(key: String, steamId: String, includeAppInfo: Int, includePlayedFreeGames: Int, scope: SyncRunRecorder.RunScope?): OwnedGamesResponse = error("not used")
        override suspend fun getRecentlyPlayedGames(key: String, steamId: String, count: Int, scope: SyncRunRecorder.RunScope?): RecentlyPlayedGamesResponse = error("not used")
        override suspend fun getSteamLevel(key: String, steamId: String, scope: SyncRunRecorder.RunScope?): SteamLevelResponse = error("not used")
        override suspend fun getPlayerSummaries(key: String, steamIds: String, scope: SyncRunRecorder.RunScope?): PlayerSummariesResponse = error("not used")
        override suspend fun getPlayerAchievements(key: String, steamId: String, appId: Long, scope: SyncRunRecorder.RunScope?): PlayerAchievementsResponse = error("not used")
        override suspend fun getGlobalAchievementPercentages(gameId: Long, scope: SyncRunRecorder.RunScope?): GlobalAchievementPercentagesResponse = error("not used")
        override suspend fun getSchemaForGame(key: String, appId: Long, scope: SyncRunRecorder.RunScope?): GameSchemaResponse = error("not used")
        override suspend fun resolveVanityUrl(key: String, vanityUrl: String): ResolveVanityResponse = error("not used")
        override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse = error("not used")
    }

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
