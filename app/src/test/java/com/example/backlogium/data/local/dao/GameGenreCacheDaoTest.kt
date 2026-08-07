package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
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
        cacheDao.upsert(GameGenreCache(1, GameGenreCodec.encode(genres), checkedAt = 100))

        val stored = cacheDao.observeAll().first().single()
        assertEquals(genres, GameGenreCodec.decodeOrEmpty(stored.genresJson))
    }

    @Test fun checkedEmptyResult_isFreshWhileMissingAndStaleRowsRemainEligible() = runBlocking {
        gameDao.upsertAll(listOf(game(1), game(2), game(3)))
        cacheDao.upsert(GameGenreCache(1, "[]", checkedAt = 100))
        cacheDao.upsert(GameGenreCache(3, "[]", checkedAt = 10))

        assertEquals(listOf(2L, 3L), cacheDao.eligibleAppIds(staleBefore = 50, limit = 25))
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
