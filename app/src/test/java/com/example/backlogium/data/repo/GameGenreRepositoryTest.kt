package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppData
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreGenreDto
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The enrichment policy, over the real cache SQL rather than a hand-written stand-in: the ordering
 * and freshness rules live in the DAO query, so a fake DAO would only re-assert this test's own
 * arithmetic.
 *
 * The distinction the whole feature rests on is between a *definitive* Store answer (genres, or a
 * checked "this app has none") and a *failure* to get one. Only the first may be written; the
 * second must leave last-known data exactly as it was and ask WorkManager to come back later.
 * Collapsing the two would either erase real genres on a flaky connection or re-query the same
 * genre-less apps on every sync forever.
 */
@RunWith(RobolectricTestRunner::class)
class GameGenreRepositoryTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var gameDao: GameDao
    private lateinit var cacheDao: GameGenreCacheDao
    private val time = FakeTime(NOW)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        gameDao = db.gameDao()
        cacheDao = db.gameGenreCacheDao()
    }

    @After fun tearDown() = db.close()

    @Test
    fun aFullyFreshCacheIsSkippedEntirely() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        cacheDao.upsert(cached(1, "[]", checkedAt = NOW - 1))
        cacheDao.upsert(cached(2, "[]", checkedAt = NOW - GameGenreRepository.FRESHNESS_WINDOW_MILLIS + 1))
        val store = FakeStoreApi()

        val batch = repository(store).enrichNextBatch()

        assertEquals(0, batch.attempted)
        assertFalse(batch.hasMoreEligible)
        assertFalse(batch.transientFailure)
        assertEquals(emptyList<Long>(), store.requested)
    }

    @Test
    fun missingAppsComeBeforeStaleOnes_oldestStaleFirst() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2), game(3), game(4)))
        // 1 and 3 were checked long enough ago to be stale; 3 is the older of the two.
        cacheDao.upsert(cached(1, "[]", checkedAt = NOW - GameGenreRepository.FRESHNESS_WINDOW_MILLIS - 1_000))
        cacheDao.upsert(cached(3, "[]", checkedAt = NOW - GameGenreRepository.FRESHNESS_WINDOW_MILLIS - 9_000))
        val store = FakeStoreApi()

        val batch = repository(store).enrichNextBatch()

        // 2 and 4 have no row at all and are backfilled first; then the stale rows, oldest first.
        assertEquals(listOf(2L, 4L), store.requested.take(2).sorted())
        assertEquals(listOf(3L, 1L), store.requested.drop(2))
        assertEquals(4, batch.attempted)
        assertFalse(batch.hasMoreEligible)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime, for the spacing assertion
    @Test
    fun oneBatchIsBoundedAndReportsThatMoreRemain() = runTest {
        val eligible = GameGenreRepository.MAX_APPS_PER_BATCH + 7
        gameDao.upsertAll((1L..eligible).map(::game))
        val store = FakeStoreApi()

        val batch = repository(store).enrichNextBatch()

        assertEquals(GameGenreRepository.MAX_APPS_PER_BATCH, batch.attempted)
        assertEquals(GameGenreRepository.MAX_APPS_PER_BATCH, store.requested.size)
        // The continuation signal the worker turns into a delayed follow-up batch.
        assertTrue(batch.hasMoreEligible)

        // Requests are spaced, not fired as a burst: only the gaps between them are paid for.
        assertEquals(
            GameGenreRepository.MIN_REQUEST_SPACING_MILLIS * (GameGenreRepository.MAX_APPS_PER_BATCH - 1),
            testScheduler.currentTime,
        )

        // The next batch picks up exactly where this one stopped, and then the chain is done.
        val second = repository(store).enrichNextBatch()
        assertEquals(7, second.attempted)
        assertFalse(second.hasMoreEligible)
        assertEquals((1L..eligible).toList(), store.requested.sorted())
    }

    @Test
    fun aTransientFailureStopsTheBatchAndPreservesLastKnownGenres() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2), game(3)))
        val known = listOf(GameGenre("1", "Action"), GameGenre("23", "Indie"))
        // A stale row that still holds good data — exactly what a failure must not touch.
        cacheDao.upsert(
            cached(
                1,
                GameGenreCodec.encode(known),
                checkedAt = NOW - GameGenreRepository.FRESHNESS_WINDOW_MILLIS - 1,
            ),
        )
        // Both un-cached apps are throttled, so the very first request of the batch fails.
        val store = FakeStoreApi(unavailable = setOf(2L, 3L))

        val batch = repository(store).enrichNextBatch()

        assertTrue(batch.transientFailure)
        // Retrying is WorkManager's job, so the batch stops instead of hammering a throttling Store.
        assertEquals(1, store.requested.size)
        assertTrue(batch.hasMoreEligible)
        val preserved = cacheDao.observeAll().first().single { it.appId == 1L }
        assertEquals(known, GameGenreCodec.decodeOrEmpty(preserved.genresJson))
        // Not re-stamped as checked: the app stays eligible for the retry.
        assertEquals(NOW - GameGenreRepository.FRESHNESS_WINDOW_MILLIS - 1, preserved.checkedAt)
    }

    @Test
    fun aCheckedAppWithNoGenresIsCachedNegativelyAndNotAskedAgain() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        val store = FakeStoreApi(
            genres = mapOf(1L to listOf(GameGenre("1", "Action"))), // 2 answers with no genres
        )
        val repository = repository(store)

        val first = repository.enrichNextBatch()
        assertEquals(2, first.attempted)
        assertFalse(first.hasMoreEligible)

        val rows = cacheDao.observeAll().first().associateBy { it.appId }
        assertEquals(listOf(GameGenre("1", "Action")), GameGenreCodec.decodeOrEmpty(rows.getValue(1L).genresJson))
        assertEquals(emptyList<GameGenre>(), GameGenreCodec.decodeOrEmpty(rows.getValue(2L).genresJson))
        assertEquals(NOW, rows.getValue(2L).checkedAt)

        // The genre-less app is a settled answer, not an unanswered question.
        store.requested.clear()
        assertEquals(0, repository.enrichNextBatch().attempted)
        assertEquals(emptyList<Long>(), store.requested)
    }

    @Test
    fun storeOrderSurvivesTheRoundTripThroughTheCache() = runTest {
        gameDao.upsert(game(1))
        val ordered = listOf(GameGenre("23", "Indie"), GameGenre("1", "Action"), GameGenre("4", "Casual"))

        repository(FakeStoreApi(genres = mapOf(1L to ordered))).enrichNextBatch()

        val stored = cacheDao.observeAll().first().single()
        assertEquals(ordered, GameGenreCodec.decodeOrEmpty(stored.genresJson))
    }

    /**
     * Enrichment is a separate best-effort concern from the owned-games poll. A Store outage
     * surfaces as a batch result the caller may ignore — never as an exception escaping into the
     * sync path — and it writes nothing, so the library the sync just persisted is untouched.
     *
     * `SteamSyncWorker` additionally wraps its `ensureEnqueued()` call in `runCatching`; that
     * WorkManager-level guard needs `androidx.work:work-testing`, which this module deliberately
     * does not depend on, and is covered by the on-device check in task 8.3.
     */
    @Test
    fun aStoreOutageNeitherThrowsNorTouchesTheLibrary() = runTest {
        gameDao.upsertAll(listOf(game(1), game(2)))
        val store = FakeStoreApi(offline = setOf(1L, 2L))

        val batch = repository(store).enrichNextBatch()

        assertTrue(batch.transientFailure)
        assertEquals(emptyList<GameGenreCache>(), cacheDao.observeAll().first())
        assertEquals(listOf(1L, 2L), gameDao.observeLibrary().first().map { it.appId }.sorted())
    }

    private fun repository(store: FakeStoreApi) = GameGenreRepository(
        cacheDao = cacheDao,
        store = SteamStoreGenreDataSource(store),
        time = time,
    )

    private fun game(appId: Long) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )

    private fun cached(appId: Long, json: String, checkedAt: Long) =
        GameGenreCache(appId = appId, genresJson = json, checkedAt = checkedAt)

    /**
     * Records every app id asked for, in order — the batch's shape is only observable from the
     * request sequence. Apps in [offline] fail at the transport, apps in [unavailable] with an
     * HTTP error; apps with no configured genres answer successfully with none.
     */
    private class FakeStoreApi(
        private val genres: Map<Long, List<GameGenre>> = emptyMap(),
        private val offline: Set<Long> = emptySet(),
        private val unavailable: Set<Long> = emptySet(),
    ) : SteamStoreApi {
        val requested = mutableListOf<Long>()

        override suspend fun appDetails(
            appId: Long,
            language: String,
        ): Response<Map<String, StoreAppDetails>> {
            requested += appId
            if (appId in offline) throw java.io.IOException("offline")
            if (appId in unavailable) {
                return Response.error(429, "slow down".toResponseBody("text/plain".toMediaType()))
            }
            val dtos = genres[appId].orEmpty().map { StoreGenreDto(it.id, it.label) }
            return Response.success(mapOf(appId.toString() to StoreAppDetails(true, StoreAppData(genres = dtos))))
        }
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
