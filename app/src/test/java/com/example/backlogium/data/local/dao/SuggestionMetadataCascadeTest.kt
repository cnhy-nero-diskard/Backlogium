package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.local.entity.SteamReviewCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Why the account-change and backup paths need no new exclusion logic for the suggestion metadata
 * caches (add-gap-plan-suggestions task 1.3).
 *
 * Both caches are refreshable Steam facts about an app id, not user-authored state. Backup never
 * carried the genre cache and does not carry the review cache either — `BackupFile` is an
 * enumerated allowlist of fields, so a new table is excluded by *not being added*, with no
 * exclusion rule to write or forget. Account change is the case worth asserting rather than
 * asserting in prose: `AccountRoomReset` clears `games`, and `ON DELETE CASCADE` has to do the
 * rest. A cache that survived an account change would describe the previous account's library.
 */
@RunWith(RobolectricTestRunner::class)
class SuggestionMetadataCascadeTest {

    private lateinit var db: BacklogiumDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        // Room's in-memory builder leaves foreign keys off by default; the shipped database has
        // them on, so the cascade this test is about would otherwise never fire.
        db.openHelper.writableDatabase.setForeignKeyConstraintsEnabled(true)
    }

    @After fun tearDown() = db.close()

    @Test
    fun deletingGamesClearsBothSuggestionMetadataCaches() = runTest {
        db.gameDao().upsertAll(listOf(game(1), game(2)))
        db.gameGenreCacheDao().upsert(
            GameGenreCache(appId = 1, genresJson = "[]", checkedAt = 1, categoriesJson = "[]"),
        )
        db.steamReviewCacheDao().upsert(
            SteamReviewCache(
                appId = 1, description = "Very Positive", positive = 10, negative = 1,
                total = 11, available = true, checkedAt = 1,
            ),
        )
        db.steamReviewCacheDao().upsert(
            SteamReviewCache(appId = 2, available = false, checkedAt = 1),
        )
        assertEquals(2, db.steamReviewCacheDao().observeAll().first().size)

        // Exactly what AccountRoomReset does last, inside its transaction.
        db.gameDao().deleteAll()

        assertEquals(emptyList<SteamReviewCache>(), db.steamReviewCacheDao().observeAll().first())
        assertEquals(emptyList<GameGenreCache>(), db.gameGenreCacheDao().observeAll().first())
    }

    private fun game(appId: Long) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 0,
        playtime2Weeks = 0, lastPlaytime = 0,
    )
}
