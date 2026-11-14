package com.example.backlogium.data.local

import androidx.room.Room
import com.example.backlogium.data.local.entity.*
import com.example.backlogium.data.repo.AccountRoomReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementCatalogueDaoTest {
    @Test fun positiveAndEmptyCataloguesSurviveReopeningWithoutInventingProgress() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "catalogue-${System.nanoTime()}"
        fun open() = Room.databaseBuilder(context, BacklogiumDatabase::class.java, name).allowMainThreadQueries().build()
        var db = open()
        try {
            db.gameDao().upsert(Game(1, "Positive", "", 0, 0, 0))
            db.gameDao().upsert(Game(2, "Empty", "", 0, 0, 0))
            val entry = AchievementCatalogueEntry(1, "fixture", "Objective", "icon", null, true, 5.0)
            db.achievementCatalogueDao().upsert(listOf(entry))
            db.achievementCatalogueDao().upsert(AchievementCatalogueCache(1, 100))
            db.achievementCatalogueDao().upsert(AchievementCatalogueCache(2, 100))
            db.close()
            db = open()
            assertEquals(listOf(entry), db.achievementCatalogueDao().entries(1))
            assertTrue(db.achievementCatalogueDao().entries(2).isEmpty())
            assertEquals(100L, db.achievementCatalogueDao().cache(2)!!.fetchedAt)
            assertNull(db.achievementCatalogueDao().cache(3))
            assertTrue(db.achievementDao().getForGame(1).isEmpty())
            assertNull(db.gameAchievementSyncDao().get(1))
            AccountRoomReset(db).resetForAccountChange("new-account")
            assertTrue(db.achievementCatalogueDao().entries(1).isEmpty())
            assertNull(db.achievementCatalogueDao().cache(2))
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
