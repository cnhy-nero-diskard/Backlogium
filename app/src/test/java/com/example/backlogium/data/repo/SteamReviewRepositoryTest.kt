package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SteamReviewCacheDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.SteamReviewCache
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import com.example.backlogium.data.remote.dto.StoreReviewSummaryDto
import com.example.backlogium.data.remote.dto.StoreReviewsResponse
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response
import java.time.LocalDate
import java.time.ZoneId

/**
 * The review cache's three states and the enrichment policy that writes them, over the real DAO
 * SQL rather than a stand-in — the ordering and freshness rules live in the query, so a fake DAO
 * would only re-assert this test's own arithmetic.
 *
 * The distinction everything rests on is between *missing*, *unavailable*, and *available*.
 * Missing is absence from the map; unavailable is a cached fact; available carries counts. Fold
 * any pair together and the ranking engine either fabricates a zero rating for an un-enriched
 * game or re-asks the Store about a genuinely unreviewed one on every run forever.
 */
@RunWith(RobolectricTestRunner::class)
class SteamReviewRepositoryTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var gameDao: GameDao
    private lateinit var cacheDao: SteamReviewCacheDao
    private val time = FakeTime(NOW)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        gameDao = db.gameDao()
        cacheDao = db.steamReviewCacheDao()
    }

    @After fun tearDown() = db.close()

    @Test
    fun theThreeStatesAreDistinguishableAndNoneOfThemIsAZeroRating() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2), game(3)))
        cacheDao.upsert(
            SteamReviewCache(
                appId = 1, description = "Very Positive", positive = 2234895,
                negative = 541071, total = 2775966, available = true, checkedAt = NOW,
            ),
        )
        cacheDao.upsert(SteamReviewCache(appId = 2, available = false, checkedAt = NOW))
        // 3 has no row at all.

        val reviews = repository(FakeReviewApi()).allReviews.first()

        assertEquals(
            GameReviewSummary.Available("Very Positive", 2234895, 541071, 2775966),
            reviews[1L],
        )
        assertEquals(GameReviewSummary.Unavailable, reviews[2L])
        // Missing is absence, never a third value a consumer could render.
        assertFalse(reviews.containsKey(3L))
    }

    /**
     * A row that claims availability without counts is read as unavailable rather than padded with
     * zeros. A fabricated zero is the one rating this cache must never be able to produce.
     */
    @Test
    fun anAvailableRowMissingItsCountsDegradesToUnavailableRatherThanZero() = runTest {
        gameDao.upsert(game(1))
        cacheDao.upsert(
            SteamReviewCache(appId = 1, description = "Positive", available = true, checkedAt = NOW),
        )

        assertEquals(
            GameReviewSummary.Unavailable,
            repository(FakeReviewApi()).allReviews.first().getValue(1L),
        )
    }

    @Test
    fun missingAppsComeBeforeStaleOnes_oldestStaleFirst() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2), game(3), game(4)))
        cacheDao.upsert(cached(1, checkedAt = NOW - SteamReviewRepository.FRESHNESS_WINDOW_MILLIS - 1_000))
        cacheDao.upsert(cached(3, checkedAt = NOW - SteamReviewRepository.FRESHNESS_WINDOW_MILLIS - 9_000))
        val store = FakeReviewApi()

        val batch = repository(store).enrichNextBatch()

        assertEquals(listOf(2L, 4L), store.requested.take(2).sorted())
        assertEquals(listOf(3L, 1L), store.requested.drop(2))
        assertEquals(4, batch.attempted)
        assertFalse(batch.hasMoreEligible)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime, for the spacing assertion
    @Test
    fun oneBatchIsBoundedSpacedAndReportsThatMoreRemain() = runTest {
        val eligible = SteamReviewRepository.MAX_APPS_PER_BATCH + 7
        gameDao.upsertAll((1L..eligible).map(::game))
        val store = FakeReviewApi()

        val batch = repository(store).enrichNextBatch()

        assertEquals(SteamReviewRepository.MAX_APPS_PER_BATCH, batch.attempted)
        assertTrue(batch.hasMoreEligible)
        // Spaced, not burst: only the gaps between requests are paid for.
        assertEquals(
            SteamReviewRepository.MIN_REQUEST_SPACING_MILLIS *
                (SteamReviewRepository.MAX_APPS_PER_BATCH - 1),
            testScheduler.currentTime,
        )

        val second = repository(store).enrichNextBatch()
        assertEquals(7, second.attempted)
        assertFalse(second.hasMoreEligible)
    }

    /**
     * A rate-limit response ends the batch **without recording an absence**. Recording one would
     * mark a game unreviewed for 30 days on the strength of a throttling message.
     */
    @Test
    fun aRateLimitEndsTheBatchAndRecordsNothing() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2), game(3)))
        val store = FakeReviewApi(throttled = setOf(1L, 2L, 3L))

        val batch = repository(store).enrichNextBatch()

        assertTrue(batch.transientFailure)
        // Stops at the first failure rather than hammering a Store that is already throttling.
        assertEquals(1, store.requested.size)
        assertEquals(emptyList<SteamReviewCache>(), cacheDao.observeAll().first())
        assertTrue(batch.hasMoreEligible)
    }

    @Test
    fun aTransientFailurePreservesTheLastKnownSummaryAndItsCheckTime() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        val staleAt = NOW - SteamReviewRepository.FRESHNESS_WINDOW_MILLIS - 1
        cacheDao.upsert(
            SteamReviewCache(
                appId = 1, description = "Overwhelmingly Positive", positive = 900,
                negative = 10, total = 910, available = true, checkedAt = staleAt,
            ),
        )
        // The un-cached app is attempted first and fails, so the batch never reaches the stale row.
        val store = FakeReviewApi(offline = setOf(2L))

        assertTrue(repository(store).enrichNextBatch().transientFailure)

        val preserved = cacheDao.observeAll().first().single()
        assertEquals("Overwhelmingly Positive", preserved.description)
        assertEquals(910, preserved.total)
        // Not re-stamped as checked: the app stays eligible for the retry.
        assertEquals(staleAt, preserved.checkedAt)
    }

    /**
     * A declined app id gets queue bookkeeping and the batch keeps going. It is neither an
     * unavailable review fact nor a reason to abandon the other twenty-four ids in the run.
     */
    @Test
    fun aDeclinedAppIdGetsCooldownWithoutEndingTheBatchOrCachingAnAbsence() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        val store = FakeReviewApi(declined = setOf(1L))

        val batch = repository(store).enrichNextBatch()

        assertEquals(2, batch.attempted)
        assertFalse(batch.transientFailure)
        assertEquals(listOf(1L, 2L), store.requested)
        val rows = cacheDao.observeAll().first().associateBy { it.appId }
        assertEquals(setOf(1L, 2L), rows.keys)
        assertEquals(NOW, rows.getValue(1L).declinedAt)
        assertNull(repository(store).allReviews.first()[1L])
        // The declined row is held out until its cooldown expires, so it cannot be retried forever
        // ahead of the rest of the library.
        assertFalse(batch.hasMoreEligible)
        store.requested.clear()
        assertEquals(0, repository(store).enrichNextBatch().attempted)
        assertTrue(store.requested.isEmpty())
    }

    /** More than one full declined batch still lets later missing games reach the Store. */
    @Test
    fun aDeclinedPrefixDoesNotStarveLaterMissingApps() = runTest {
        val totalGames = SteamReviewRepository.MAX_APPS_PER_BATCH + 5L
        val declinedPrefix = (1L..(SteamReviewRepository.MAX_APPS_PER_BATCH + 1L)).toSet()
        gameDao.upsertAll((1L..totalGames).map(::game))
        val store = FakeReviewApi(declined = declinedPrefix)
        val repository = repository(store)

        val first = repository.enrichNextBatch()

        assertEquals(
            (1L..SteamReviewRepository.MAX_APPS_PER_BATCH.toLong()).toList(),
            store.requested,
        )
        assertTrue(first.hasMoreEligible)

        val second = repository.enrichNextBatch()

        assertEquals(
            (SteamReviewRepository.MAX_APPS_PER_BATCH.toLong() + 1L..totalGames).toList(),
            store.requested.drop(SteamReviewRepository.MAX_APPS_PER_BATCH),
        )
        assertEquals(5, second.attempted)
        assertFalse(second.hasMoreEligible)
    }

    @Test
    fun aDefinitiveNoReviewsResultIsCachedAndNotAskedAgain() = runTest {
        gameDao.upsert(game(1))
        val store = FakeReviewApi(unreviewed = setOf(1L))
        val repository = repository(store)

        assertEquals(1, repository.enrichNextBatch().attempted)

        val row = cacheDao.observeAll().first().single()
        assertFalse(row.available)
        // Null, not zero: an unavailable row must not be able to present itself as zero reviews.
        assertNull(row.positive)
        assertNull(row.negative)
        assertNull(row.total)
        assertEquals(
            GameReviewSummary.Unavailable,
            repository.allReviews.first().getValue(1L),
        )

        store.requested.clear()
        assertEquals(0, repository.enrichNextBatch().attempted)
        assertEquals(emptyList<Long>(), store.requested)
    }

    /** The request budget belongs to games the player can see (add-hidden-games). */
    @Test
    fun hiddenGamesAreNotSelectedForEnrichment() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(appId = 2, hiddenAt = 0L)))
        val store = FakeReviewApi()

        assertEquals(1, repository(store).enrichNextBatch().attempted)
        assertEquals(listOf(1L), store.requested)

        db.hiddenGameDao().delete(listOf(2L))
        store.requested.clear()
        assertEquals(1, repository(store).enrichNextBatch().attempted)
        assertEquals(listOf(2L), store.requested)
    }

    /** A Store outage surfaces as a batch result, never as an exception into a caller. */
    @Test
    fun aStoreOutageNeitherThrowsNorTouchesTheLibrary() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        val store = FakeReviewApi(offline = setOf(1L, 2L))

        val batch = repository(store).enrichNextBatch()

        assertTrue(batch.transientFailure)
        assertEquals(emptyList<SteamReviewCache>(), cacheDao.observeAll().first())
        assertEquals(listOf(1L, 2L), gameDao.observeLibrary().first().map { it.appId }.sorted())
    }

    private fun repository(store: FakeReviewApi) = SteamReviewRepository(
        cacheDao = cacheDao,
        store = SteamStoreReviewDataSource(store),
        time = time,
    )

    private fun game(appId: Long) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )

    private fun cached(appId: Long, checkedAt: Long) = SteamReviewCache(
        appId = appId, description = "Positive", positive = 9, negative = 1, total = 10,
        available = true, checkedAt = checkedAt,
    )

    /**
     * Records every app id asked for, in order — a batch's shape is only observable from the
     * request sequence. Apps in [offline] fail at the transport, [throttled] with HTTP 429,
     * [declined] with an unsuccessful envelope, and [unreviewed] with a definitive zero.
     */
    private class FakeReviewApi(
        private val offline: Set<Long> = emptySet(),
        private val throttled: Set<Long> = emptySet(),
        private val declined: Set<Long> = emptySet(),
        private val unreviewed: Set<Long> = emptySet(),
    ) : SteamStoreApi {
        val requested = mutableListOf<Long>()

        override suspend fun appReviews(
            appId: Long,
            json: Int,
            language: String,
            purchaseType: String,
            pageSize: Int,
        ): Response<StoreReviewsResponse> {
            requested += appId
            if (appId in offline) throw java.io.IOException("offline")
            if (appId in throttled) {
                return Response.error(429, "slow down".toResponseBody("text/plain".toMediaType()))
            }
            if (appId in declined) {
                return Response.success(StoreReviewsResponse(success = 0))
            }
            if (appId in unreviewed) {
                return Response.success(
                    StoreReviewsResponse(1, StoreReviewSummaryDto("No user reviews", 0, 0, 0)),
                )
            }
            return Response.success(
                StoreReviewsResponse(1, StoreReviewSummaryDto("Positive", 9, 1, 10)),
            )
        }

        override suspend fun appDetails(
            appId: Long,
            language: String,
        ): Response<Map<String, StoreAppDetails>> = error("the review path must not fetch details")

        override suspend fun appDetailsPrices(
            appIds: String,
            countryCode: String?,
            filters: String,
        ): Response<Map<String, StorePriceEnvelope>> = error("the review path must not price anything")
    }

    private class FakeTime(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-08")
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
