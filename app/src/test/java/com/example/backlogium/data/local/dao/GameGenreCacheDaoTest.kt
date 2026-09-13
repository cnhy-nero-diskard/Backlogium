package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.repo.GameGenre
import com.example.backlogium.data.repo.GameGenreCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GameGenreCacheDaoTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var gameDao: GameDao
    private lateinit var cacheDao: GameGenreCacheDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        gameDao = db.gameDao()
        cacheDao = db.gameGenreCacheDao()
    }

    @After fun tearDown() = db.close()

    @Test fun orderedGenres_roundTripAndRemainObservable() = runBlocking {
        gameDao.upsert(game(1))
        val genres = listOf(GameGenre("1", "Action"), GameGenre("23", "Indie"))
        cacheDao.upsert(
            GameGenreCache(1, GameGenreCodec.encode(genres), checkedAt = 100, categoriesJson = "[]"),
        )

        val stored = cacheDao.observeAll().first().single()
        assertEquals(genres, GameGenreCodec.decodeOrEmpty(stored.genresJson))
    }

    @Test fun checkedEmptyResult_isFreshWhileMissingAndStaleRowsRemainEligible() = runBlocking {
        gameDao.upsertAll(listOf(game(1), game(2), game(3)))
        // Fully checked by this build: empty genres *and* an answered category payload.
        cacheDao.upsert(GameGenreCache(1, "[]", checkedAt = 100, categoriesJson = "[]"))
        cacheDao.upsert(GameGenreCache(3, "[]", checkedAt = 10, categoriesJson = "[]"))

        assertEquals(listOf(2L, 3L), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
        assertEquals(2, cacheDao.eligibleCount(staleBefore = 50))
    }

    /**
     * A row written before participation categories were retained is fresh by `checkedAt` and
     * would be skipped for the rest of its 30-day window, leaving the gap-plan multiplayer path
     * inert on exactly the installs with the most enriched data. A null category payload means
     * the row has never been checked for the data now required, so it is eligible regardless of
     * freshness and sorts with the never-checked rows (add-gap-plan-suggestions).
     */
    @Test fun rowsWithNoCategoryPayload_areEligibleRegardlessOfFreshness() = runBlocking {
        gameDao.upsertAll(listOf(game(1), game(2), game(3)))
        // Fresh, but pre-dates categories.
        cacheDao.upsert(GameGenreCache(1, "[]", checkedAt = 100, categoriesJson = null))
        // Fresh and fully answered — genuinely settled.
        cacheDao.upsert(GameGenreCache(2, "[]", checkedAt = 100, categoriesJson = "[]"))
        // Stale and fully answered.
        cacheDao.upsert(GameGenreCache(3, "[]", checkedAt = 10, categoriesJson = "[]"))

        // 1 sorts ahead of the stale row 3, because unknown categories are a gap, not a refresh.
        assertEquals(listOf(1L, 3L), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
        assertEquals(2, cacheDao.eligibleCount(staleBefore = 50))

        // Answering it — even with "advertises none" — settles it.
        cacheDao.upsert(GameGenreCache(1, "[]", checkedAt = 100, categoriesJson = "[]"))
        assertEquals(listOf(3L), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
        assertEquals(1, cacheDao.eligibleCount(staleBefore = 50))
    }

    @Test fun aRecentlyRefusedCategoryLookup_isHeldOutUntilItsCooldownExpires() = runBlocking {
        gameDao.upsert(game(1))
        cacheDao.upsert(
            GameGenreCache(
                appId = 1,
                genresJson = "[]",
                checkedAt = 10,
                categoriesJson = null,
                categoriesDeclinedAt = 100,
            ),
        )

        assertEquals(emptyList<Long>(), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
        assertEquals(listOf(1L), cacheDao.eligibleAppIds(staleBefore = 101, limit = 25))
    }

    /**
     * A hidden game is not enriched: the store request budget belongs to games the player can see
     * (add-hidden-games). Eligibility is this query rather than a stored decision, so unhiding
     * makes the game eligible again with no extra bookkeeping.
     */
    @Test fun hiddenGames_areNotEligibleForEnrichment() = runBlocking {
        gameDao.upsertAll(listOf(game(1), game(2)))
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(appId = 2, hiddenAt = 0L)))

        assertEquals(listOf(1L), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
        assertEquals(1, cacheDao.eligibleCount(staleBefore = 50))

        db.hiddenGameDao().delete(listOf(2L))

        assertEquals(listOf(1L, 2L), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
        assertEquals(2, cacheDao.eligibleCount(staleBefore = 50))
    }

    @Test fun malformedCachedJson_isDefensivelyEmpty() {
        assertEquals(emptyList<GameGenre>(), GameGenreCodec.decodeOrEmpty("not json"))
        assertEquals(emptyList<GameGenre>(), GameGenreCodec.decodeOrEmpty("[{\"id\":\"\",\"label\":\"Action\"}]"))
    }

    private fun game(appId: Long) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )
}
