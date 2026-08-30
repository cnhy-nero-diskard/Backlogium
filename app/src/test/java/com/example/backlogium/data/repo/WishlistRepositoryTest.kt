package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import com.example.backlogium.data.remote.dto.StoreItemsResponse
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import com.example.backlogium.data.remote.dto.WishlistResponse
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import retrofit2.Response

/** The read side: Steam's ordering, the four price states, and ownership reconciliation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WishlistRepositoryTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var repository: WishlistRepository
    private val clock = FixedTime(NOW)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = repository()
    }

    private fun repository(
        steamId: String? = "76561197979911851",
        api: FakeWishlistApi = FakeWishlistApi(),
        priceApi: FakePriceApi = FakePriceApi { _, _ -> Response.success(emptyMap()) },
        time: TimeProvider = clock,
    ) = WishlistRepository(
        wishlistDao = db.wishlistDao(),
        gameDao = db.gameDao(),
        playerProfileDao = db.playerProfileDao(),
        credentials = FakeCredentials(steamId),
        wishlistSource = SteamWishlistDataSource(api),
        priceSource = SteamStorePriceDataSource(priceApi),
        time = time,
    )

    @After fun tearDown() = db.close()

    @Test fun entriesFollowSteamsPriorityOrder_withUnprioritizedLast() = runBlocking {
        db.wishlistDao().upsertItems(
            listOf(
                item(appId = 1, priority = 0, addedAt = 10),
                item(appId = 2, priority = 2),
                item(appId = 3, priority = 0, addedAt = 5),
                item(appId = 4, priority = 1),
            ),
        )

        // Priority 0 means "unprioritized", not "first" — ordering by the column alone would put
        // both unranked entries above the player's actual first choice.
        assertEquals(listOf(4L, 2L, 3L, 1L), repository.wishlist.first().map { it.appId })
    }

    @Test fun aLiveDiscountFloatsToTheTop_keepingSteamsOrderWithinEachGroup() = runBlocking {
        db.wishlistDao().upsertItems(
            listOf(
                item(appId = 1, priority = 1),
                item(appId = 2, priority = 2),
                item(appId = 3, priority = 3),
                item(appId = 4, priority = 4),
            ),
        )
        db.wishlistDao().insertObservations(
            listOf(
                price(appId = 1, at = NOW),
                price(appId = 2, at = NOW, discount = 50),
                price(appId = 4, at = NOW, discount = 75),
            ),
        )

        // Discounted first, but the player's own ranking still decides the order inside each
        // group — 2 before 4, then 1 before 3.
        assertEquals(listOf(2L, 4L, 1L, 3L), repository.wishlist.first().map { it.appId })
    }

    @Test fun aRetainedDiscountDoesNotFloat() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1, priority = 1), item(appId = 2, priority = 2)))
        db.wishlistDao().insertObservations(
            listOf(
                price(
                    appId = 2,
                    at = NOW - WishlistRepository.FRESHNESS_WINDOW_MILLIS - 1,
                    discount = 75,
                ),
            ),
        )

        // A sale seen yesterday says nothing about today; promoting it would dress a price the
        // app is unsure of as the most urgent thing on the list.
        assertEquals(listOf(1L, 2L), repository.wishlist.first().map { it.appId })
    }

    @Test fun anOwnedGameIsNoLongerPresentedAsWanted() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1), item(appId = 2)))
        db.gameDao().upsertAll(listOf(game(2)))

        assertEquals(listOf(1L), repository.wishlist.first().map { it.appId })
    }

    @Test fun aFreshObservationIsCurrent_andAnOldOneIsRetainedWithItsDate() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1), item(appId = 2)))
        val stale = NOW - WishlistRepository.FRESHNESS_WINDOW_MILLIS - 1
        db.wishlistDao().insertObservations(
            listOf(price(appId = 1, at = NOW), price(appId = 2, at = stale)),
        )

        val byId = repository.wishlist.first().associateBy { it.appId }
        assertEquals(
            WishlistPrice.Observed("P2,099.00", null, 0, NOW, current = true),
            byId.getValue(1L).price,
        )
        assertEquals(
            WishlistPrice.Observed("P2,099.00", null, 0, stale, current = false),
            byId.getValue(2L).price,
        )
    }

    @Test fun onlyTheLatestObservationIsPresented_andEarlierOnesAreKept() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1)))
        db.wishlistDao().insertObservations(listOf(price(appId = 1, at = NOW - 1000, formatted = "P1.00")))
        db.wishlistDao().insertObservations(listOf(price(appId = 1, at = NOW, formatted = "P2.00")))

        val price = repository.wishlist.first().single().price as WishlistPrice.Observed
        assertEquals("P2.00", price.formatted)
        // Appended, never overwritten: the history is the whole reason the table exists.
        assertEquals(2, observationCount())
    }

    @Test fun aRecordedAbsenceIsUnavailable_andNoObservationAtAllClaimsNothing() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1), item(appId = 2)))
        db.wishlistDao().insertObservations(listOf(noPrice(appId = 1, at = NOW)))

        val byId = repository.wishlist.first().associateBy { it.appId }
        assertEquals(WishlistPrice.Unavailable(NOW), byId.getValue(1L).price)
        assertEquals(WishlistPrice.NeverObserved, byId.getValue(2L).price)
    }

    @Test fun aDiscountCarriesTheStruckThroughListPrice() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1)))
        db.wishlistDao().insertObservations(
            listOf(price(appId = 1, at = NOW, formatted = "P849.75", listFormatted = "P3,399.00", discount = 75)),
        )

        assertEquals(
            WishlistPrice.Observed("P849.75", "P3,399.00", 75, NOW, current = true),
            repository.wishlist.first().single().price,
        )
    }

    @Test fun everyEntryOffersItsStorePage() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 292030)))

        assertEquals(
            "https://store.steampowered.com/app/292030",
            repository.wishlist.first().single().storeUrl,
        )
    }

    @Test fun theFreshnessWindowCountsAnUnobservedEntryAsStale() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1), item(appId = 2)))
        db.wishlistDao().insertObservations(listOf(price(appId = 1, at = NOW)))

        // Otherwise the one entry with no price would be the one entry a refresh never covers.
        assertEquals(0L, db.wishlistDao().oldestLatestObservationAt())
    }

    @Test fun aRefreshStoresSteamsEntriesWithTheirNamesAndPrices() = runBlocking {
        val repo = repository(
            api = FakeWishlistApi(
                wishlist = { wishlistJson(WISHLIST) },
                storeItems = { storeItemsJson(STORE_ITEMS) },
            ),
            priceApi = FakePriceApi { _, _ -> Response.success(pricesJson(PRICED)) },
        )

        assertEquals(WishlistRefresh.REFRESHED, repo.refresh())

        val entry = repo.wishlist.first().single()
        assertEquals(292030L, entry.appId)
        assertEquals("The Witcher 3: Wild Hunt", entry.name)
        assertEquals(WishlistPrice.Observed("P2,099.00", null, 0, NOW, current = true), entry.price)
        assertEquals(WishlistAvailability.AVAILABLE, repo.availability.value)
    }

    @Test fun aSecondOpenInsideTheFreshnessWindowMakesNoRequest() = runBlocking {
        val api = FakeWishlistApi(
            wishlist = { wishlistJson(WISHLIST) },
            storeItems = { storeItemsJson(STORE_ITEMS) },
        )
        val priceApi = FakePriceApi { ids, _ -> Response.success(pricedChunk(ids)) }
        val repo = repository(api = api, priceApi = priceApi)

        repo.refresh()
        assertEquals(WishlistRefresh.SKIPPED_FRESH, repo.refresh())

        assertEquals(1, api.wishlistCalls)
        assertEquals(1, priceApi.requests.size)
    }

    @Test fun anEmptyWishlistStaysFreshAfterReopeningInsideTheWindow() = runBlocking {
        val firstApi = FakeWishlistApi(wishlist = { wishlistJson(EMPTY_WISHLIST) })
        val firstPriceApi = FakePriceApi { _, _ -> error("an empty wishlist must not be priced") }
        val first = repository(api = firstApi, priceApi = firstPriceApi)

        assertEquals(WishlistRefresh.REFRESHED, first.refresh())

        val reopenedApi = FakeWishlistApi(wishlist = { error("fresh membership must not be re-read") })
        val reopenedPriceApi = FakePriceApi { _, _ -> error("a fresh empty wishlist must not be priced") }
        val reopened = repository(api = reopenedApi, priceApi = reopenedPriceApi)

        assertEquals(WishlistRefresh.SKIPPED_FRESH, reopened.refresh())
        assertEquals(WishlistAvailability.AVAILABLE, reopened.availability.value)
        assertEquals(0, reopenedApi.wishlistCalls)
        assertEquals(0, reopenedPriceApi.requests.size)
    }

    @Test fun anEntirelyOwnedWishlistStaysFreshWithoutARedundantPriceRequest() = runBlocking {
        db.gameDao().upsertAll(listOf(game(1)))
        val first = repository(
            api = FakeWishlistApi(wishlist = { wishlistJson(OWNED_ONLY_WISHLIST) }),
            priceApi = FakePriceApi { _, _ -> error("an entirely-owned wishlist must not be priced") },
        )

        assertEquals(WishlistRefresh.REFRESHED, first.refresh())

        val reopenedApi = FakeWishlistApi(wishlist = { error("fresh membership must not be re-read") })
        val reopenedPriceApi = FakePriceApi { _, _ -> error("owned entries must never be priced") }
        val reopened = repository(api = reopenedApi, priceApi = reopenedPriceApi)

        assertEquals(WishlistRefresh.SKIPPED_FRESH, reopened.refresh())
        assertEquals(0, reopenedApi.wishlistCalls)
        assertEquals(0, reopenedPriceApi.requests.size)
    }

    @Test fun anOwnedWishlistEntryDoesNotPreventFreshnessSkippingForWantedEntries() = runBlocking {
        db.gameDao().upsertAll(listOf(game(1)))
        val api = FakeWishlistApi(
            wishlist = { wishlistJson(WISHLIST_WITH_OWNED_ENTRY) },
            storeItems = { storeItemsJson(STORE_ITEMS_WITH_OWNED_ENTRY) },
        )
        val priceApi = FakePriceApi { ids, _ -> Response.success(pricedChunk(ids)) }
        val repo = repository(api = api, priceApi = priceApi)

        assertEquals(WishlistRefresh.REFRESHED, repo.refresh())
        assertEquals(WishlistRefresh.SKIPPED_FRESH, repo.refresh())

        // The owned row is hidden and excluded from both the first price request and freshness.
        assertEquals(1, api.wishlistCalls)
        assertEquals(listOf("2"), priceApi.requests.map { it.first })
    }

    @Test fun theSamplerIgnoresTheFreshnessWindowEntirely() = runBlocking {
        val api = FakeWishlistApi(
            wishlist = { wishlistJson(WISHLIST) },
            storeItems = { storeItemsJson(STORE_ITEMS) },
        )
        val repo = repository(api = api, priceApi = FakePriceApi { _, _ -> Response.success(pricesJson(PRICED)) })

        repo.refresh()
        // The periodic sampler exists to record history on days nobody opens the section, so it
        // must not be turned away by the window the view path just refreshed.
        assertEquals(WishlistRefresh.REFRESHED, repo.refresh(force = true))
        assertEquals(2, api.wishlistCalls)
        assertEquals(2, observationCount())
    }

    @Test fun anUnreadableWishlistLeavesRetainedEntriesAndTheirDatesAlone() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1)))
        val observedAt = NOW - WishlistRepository.FRESHNESS_WINDOW_MILLIS - 1
        db.wishlistDao().insertObservations(listOf(price(appId = 1, at = observedAt)))
        val repo = repository(
            api = FakeWishlistApi(wishlist = { wishlistJson(NOT_READABLE) }),
            priceApi = FakePriceApi { _, _ ->
                Response.error(500, "down".toResponseBody("text/plain".toMediaType()))
            },
        )

        repo.refresh()

        assertEquals(WishlistAvailability.NOT_READABLE, repo.availability.value)
        val entry = repo.wishlist.first().single()
        assertEquals(1L, entry.appId)
        // Retained with its original date, never cleared and never re-dated to now.
        assertEquals(
            WishlistPrice.Observed("P2,099.00", null, 0, observedAt, current = false),
            entry.price,
        )
    }

    @Test fun aFailedChunkLeavesOnlyItsOwnPricesAtTheirPreviousValues() = runBlocking {
        // 101 wanted games is two price requests. The one carrying 101 fails.
        val appIds = (1L..101L).toList()
        db.wishlistDao().upsertItems(appIds.map { item(appId = it) })
        db.wishlistDao().insertObservations(
            listOf(price(appId = 101, at = NOW - 5_000, formatted = "P1.00")),
        )
        val repo = repository(
            api = FakeWishlistApi(wishlist = { wishlistJson(NOT_READABLE) }),
            priceApi = FakePriceApi { ids, _ ->
                if ("101" in ids.split(",")) {
                    Response.error(500, "down".toResponseBody("text/plain".toMediaType()))
                } else {
                    Response.success(pricedChunk(ids))
                }
            },
        )

        repo.refresh()

        val byId = repo.wishlist.first().associateBy { it.appId }
        assertEquals(NOW, (byId.getValue(1L).price as WishlistPrice.Observed).observedAt)
        // The failed chunk recorded nothing, so this app keeps the price and date it already had.
        assertEquals(
            WishlistPrice.Observed("P1.00", null, 0, NOW - 5_000, current = true),
            byId.getValue(101L).price,
        )
    }

    @Test fun aFailedWishlistReadDoesNotBecomeFreshFromASuccessfulPriceRefresh() = runBlocking {
        val staleMembership = NOW - WishlistRepository.FRESHNESS_WINDOW_MILLIS - 1
        db.wishlistDao().upsertItems(listOf(item(appId = 1, lastSeenAt = staleMembership)))
        var wishlistAttempt = 0
        val api = FakeWishlistApi(wishlist = {
            wishlistAttempt++
            if (wishlistAttempt == 1) throw IOException("offline")
            wishlistJson(NOT_READABLE)
        })
        val priceApi = FakePriceApi { ids, _ -> Response.success(pricedChunk(ids)) }
        val repo = repository(api = api, priceApi = priceApi)

        // The membership read fails, but retained entries still get a fresh price observation.
        assertEquals(WishlistRefresh.REFRESHED, repo.refresh())
        assertEquals(1, api.wishlistCalls)

        // The membership timestamp stayed stale, so a fresh price must not suppress this retry.
        assertEquals(WishlistRefresh.REFRESHED, repo.refresh())
        assertEquals(2, api.wishlistCalls)
    }

    @Test fun afterAForcedFailedRead_reopeningRetriesMembershipDespiteFreshPrices() = runBlocking {
        var wishlistAttempt = 0
        val api = FakeWishlistApi(wishlist = {
            wishlistAttempt++
            if (wishlistAttempt == 1) wishlistJson(WISHLIST) else wishlistJson(NOT_READABLE)
        })
        val priceApi = FakePriceApi { ids, _ -> Response.success(pricedChunk(ids)) }
        val first = repository(api = api, priceApi = priceApi)

        assertEquals(WishlistRefresh.REFRESHED, first.refresh())
        // The forced sampler read fails, but its price pass succeeds and records a fresh observation.
        assertEquals(WishlistRefresh.REFRESHED, first.refresh(force = true))

        val reopened = repository(api = api, priceApi = priceApi)
        assertEquals(WishlistRefresh.REFRESHED, reopened.refresh())
        assertEquals(3, api.wishlistCalls)
    }

    @Test fun aPriceExpiresWhileTheSectionIsOpen_withoutAnyDatabaseEmission() = runTest {
        val virtualClock = VirtualTime(this)
        db.wishlistDao().upsertItems(
            listOf(item(appId = 1, priority = 2), item(appId = 2, priority = 1)),
        )
        db.wishlistDao().insertObservations(
            listOf(
                price(appId = 1, at = NOW, discount = 50),
                price(appId = 2, at = NOW),
            ),
        )

        val states = repository(time = virtualClock).wishlist
            .filter { games -> games.all { it.price is WishlistPrice.Observed } }
            .distinctUntilChanged()
            .take(2)
            .toList()

        assertEquals(listOf(1L, 2L), states[0].map { it.appId })
        assertEquals(true, (states[0].first().price as WishlistPrice.Observed).current)
        assertEquals(listOf(2L, 1L), states[1].map { it.appId })
        assertEquals(
            WishlistPrice.Observed("P2,099.00", null, 50, NOW, current = false),
            states[1].last().price,
        )
    }

    @Test fun anUnresolvedPriceShapeRetainsThePreviousObservation() = runBlocking {
        val staleMembership = NOW - WishlistRepository.FRESHNESS_WINDOW_MILLIS - 1
        db.wishlistDao().upsertItems(listOf(item(appId = 1, lastSeenAt = staleMembership)))
        val previous = price(appId = 1, at = NOW - 5_000, formatted = "P1.00")
        db.wishlistDao().insertObservations(listOf(previous))
        val repo = repository(
            api = FakeWishlistApi(wishlist = { wishlistJson(NOT_READABLE) }),
            priceApi = FakePriceApi { _, _ -> Response.success(pricesJson(EMPTY_OBJECT_DATA)) },
        )

        repo.refresh()

        assertEquals(
            WishlistPrice.Observed("P1.00", null, 0, NOW - 5_000, current = true),
            repo.wishlist.first().single().price,
        )
        assertEquals(1, observationCount())
    }

    @Test fun anOwnedGameIsNotEvenPriced() = runBlocking {
        db.wishlistDao().upsertItems(listOf(item(appId = 1), item(appId = 2)))
        db.gameDao().upsertAll(listOf(game(2)))
        val priceApi = FakePriceApi { ids, _ -> Response.success(pricedChunk(ids)) }
        val repo = repository(
            api = FakeWishlistApi(wishlist = { wishlistJson(NOT_READABLE) }),
            priceApi = priceApi,
        )

        repo.refresh()

        assertEquals(listOf("1"), priceApi.requests.map { it.first })
    }

    @Test fun withNoCredentials_nothingIsRequestedAtAll() = runBlocking {
        val api = FakeWishlistApi(wishlist = { error("must not be called") })

        assertEquals(WishlistRefresh.NOT_CONFIGURED, repository(steamId = null, api = api).refresh())
        assertEquals(0, api.wishlistCalls)
    }

    @Test fun anExplicitStoreRegionIsUsedForDetailsAndPrices() = runBlocking {
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().updateSteamIdentity("1", 0, "Nero", null, storeRegion = "PH")
        db.wishlistDao().upsertItems(listOf(item(appId = 1)))
        val api = FakeWishlistApi(
            wishlist = { wishlistJson(WISHLIST) },
            storeItems = { storeItemsJson(STORE_ITEMS) },
        )
        val priceApi = FakePriceApi { _, _ -> Response.success(pricesJson(PRICED)) }

        repository(
            api = api,
            priceApi = priceApi,
        ).refresh()

        assertEquals(listOf("PH"), priceApi.requests.map { it.second })
        assertEquals(true, "\"country_code\":\"PH\"" in api.lastStoreItemsInput.orEmpty())
    }

    private fun wishlistJson(body: String) = JSON.decodeFromString<WishlistResponse>(body)

    private fun storeItemsJson(body: String) = JSON.decodeFromString<StoreItemsResponse>(body)

    private fun pricesJson(body: String) = JSON.decodeFromString<Map<String, StorePriceEnvelope>>(body)

    /** A priced entry for each id in a chunk, so a whole request can be answered generically. */
    private fun pricedChunk(idCsv: String) =
        pricesJson(idCsv.split(",").joinToString(",", "{", "}") { PRICED_ENTRY(it) })

    private fun observationCount(): Int =
        db.query("SELECT COUNT(*) FROM wishlist_price_observations", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun item(
        appId: Long,
        priority: Int = 0,
        addedAt: Long = 0L,
        lastSeenAt: Long = NOW,
    ) = WishlistItem(
        appId = appId,
        name = "Wanted $appId",
        artworkUrl = "https://cdn/$appId.jpg",
        priority = priority,
        addedAt = addedAt,
        lastSeenAt = lastSeenAt,
    )

    private fun price(
        appId: Long,
        at: Long,
        formatted: String = "P2,099.00",
        listFormatted: String? = null,
        discount: Int = 0,
    ) = WishlistPriceObservation(
        appId = appId,
        observedAt = at,
        currency = "PHP",
        finalMinorUnits = 209900,
        initialMinorUnits = 209900,
        discountPercent = discount,
        formatted = formatted,
        listFormatted = listFormatted,
    )

    private fun noPrice(appId: Long, at: Long) =
        WishlistPriceObservation(appId = appId, observedAt = at)

    private fun game(appId: Long) = Game(
        appId = appId, name = "Owned $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )

    private class FixedTime(private val now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-30")
    }

    private class VirtualTime(
        private val scope: kotlinx.coroutines.test.TestScope,
    ) : TimeProvider {
        override fun nowMillis(): Long = NOW + scope.testScheduler.currentTime
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-30")
    }

    private companion object {
        const val NOW = 1_800_000_000_000L

        val JSON = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        const val WISHLIST = """{"response":{"items":[{"appid":292030,"priority":1,"date_added":1549370695}]}}"""

        const val WISHLIST_WITH_OWNED_ENTRY = """{"response":{"items":[{"appid":1,"priority":1,"date_added":1549370695},{"appid":2,"priority":2,"date_added":1549370695}]}}"""

        const val EMPTY_WISHLIST = """{"response":{"items":[]}}"""

        const val OWNED_ONLY_WISHLIST = """{"response":{"items":[{"appid":1,"priority":1,"date_added":1549370695}]}}"""

        const val NOT_READABLE = """{"response":{}}"""

        const val STORE_ITEMS = """{"response":{"store_items":[{"id":292030,"appid":292030,"success":1,"visible":true,"name":"The Witcher 3: Wild Hunt"}]}}"""

        const val STORE_ITEMS_WITH_OWNED_ENTRY = """{"response":{"store_items":[{"id":1,"appid":1,"success":1,"visible":true,"name":"Owned game"},{"id":2,"appid":2,"success":1,"visible":true,"name":"Wanted game"}]}}"""

        const val PRICED = """{"292030":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":209900,"final":209900,"discount_percent":0,"initial_formatted":"","final_formatted":"P2,099.00"}}}}"""

        const val EMPTY_OBJECT_DATA = """{"1":{"success":true,"data":{}}}"""

        fun PRICED_ENTRY(appId: String) =
            """"@ID@":{"success":true,"data":{"price_overview":{"currency":"PHP","initial":209900,"final":209900,"discount_percent":0,"initial_formatted":"","final_formatted":"P2,099.00"}}}"""
                .replace("@ID@", appId)
    }
}
