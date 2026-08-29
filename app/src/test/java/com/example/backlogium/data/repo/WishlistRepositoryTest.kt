package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.WishlistItem
import com.example.backlogium.data.local.entity.WishlistPriceObservation
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/** The read side: Steam's ordering, the four price states, and ownership reconciliation. */
@RunWith(RobolectricTestRunner::class)
class WishlistRepositoryTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var repository: WishlistRepository
    private val clock = FixedTime(NOW)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = WishlistRepository(db.wishlistDao(), db.gameDao(), clock)
    }

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

    private fun observationCount(): Int =
        db.query("SELECT COUNT(*) FROM wishlist_price_observations", emptyArray()).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun item(appId: Long, priority: Int = 0, addedAt: Long = 0L) = WishlistItem(
        appId = appId,
        name = "Wanted $appId",
        artworkUrl = "https://cdn/$appId.jpg",
        priority = priority,
        addedAt = addedAt,
        lastSeenAt = NOW,
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

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
