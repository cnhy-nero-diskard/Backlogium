package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.domain.GameSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Covers [GameDao.setManualSharedMinutes]'s SQL-level source guard and
 * [GameDao.convertSharedToOwned]'s atomic estimate clearing
 * (add-shared-game-playtime-and-filter) — the write must be a no-op for an owned game regardless
 * of what a caller passes, the same defense-in-depth `deleteSharedGame` already applies, and a
 * shared→owned conversion must not leave a stale estimate behind for XP/detail/collections to
 * double-count.
 */
@RunWith(RobolectricTestRunner::class)
class GameDaoTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var gameDao: GameDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        gameDao = db.gameDao()
    }

    @After fun tearDown() = db.close()

    @Test fun setManualSharedMinutes_appliesForAFamilySharedGame() = runBlocking {
        gameDao.upsert(game(appId = 1, source = GameSource.FAMILY_SHARED))

        gameDao.setManualSharedMinutes(1, 90)

        assertEquals(90, gameDao.getById(1)?.manualSharedMinutes)
    }

    @Test fun setManualSharedMinutes_isANoOpForAnOwnedGame() = runBlocking {
        gameDao.upsert(game(appId = 1, source = GameSource.STEAM_OWNED))

        gameDao.setManualSharedMinutes(1, 90)

        assertEquals(0, gameDao.getById(1)?.manualSharedMinutes)
    }

    @Test fun setManualSharedMinutes_zeroClearsAPreviouslySetEstimate() = runBlocking {
        gameDao.upsert(game(appId = 1, source = GameSource.FAMILY_SHARED))
        gameDao.setManualSharedMinutes(1, 90)

        gameDao.setManualSharedMinutes(1, 0)

        assertEquals(0, gameDao.getById(1)?.manualSharedMinutes)
    }

    @Test fun convertSharedToOwned_clearsAManualSharedEstimate() = runBlocking {
        gameDao.upsert(game(appId = 1, source = GameSource.FAMILY_SHARED))
        gameDao.setManualSharedMinutes(1, 600)

        val rows = gameDao.convertSharedToOwned(
            appId = 1,
            playtimeForever = 900,
            playtime2Weeks = 0,
            convertedAt = 5_000L,
        )

        assertEquals(1, rows)
        val row = gameDao.getById(1)
        assertEquals(GameSource.STEAM_OWNED, row?.source)
        assertEquals(0, row?.manualSharedMinutes)
        assertEquals(900, row?.playtimeForever)
        assertEquals(900, row?.lastPlaytime)
        // XP, detail, and smart collections all read backfill + manual + tracked from this row,
        // so a cleared estimate keeps the converted game's derived totals free of borrowed-hours
        // double counting (Steam's reported total already includes them).
        assertEquals(0, (row?.backfillMinutes ?: -1) + (row?.manualSharedMinutes ?: -1))
    }

    private fun game(appId: Long, source: GameSource) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0, source = source,
    )
}
