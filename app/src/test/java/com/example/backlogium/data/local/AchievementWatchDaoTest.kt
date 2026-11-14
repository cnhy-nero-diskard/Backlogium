package com.example.backlogium.data.local

import androidx.room.Room
import com.example.backlogium.data.local.entity.AchievementWatchWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementWatchDaoTest {
    @Test
    fun groupsAndRepairWorkSurviveReopenAndClaimsAreIndependent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "watch-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, BacklogiumDatabase::class.java, name)
            .allowMainThreadQueries().build()
        var db = open()
        try {
            val first = AchievementWatchWork("one", "account", 1, "epoch", 1, true, "payload-one")
            val second = first.copy(eventId = "two", observedAt = 2, payload = "payload-two")
            val repair = first.copy(eventId = "repair", payload = null)
            assertTrue(db.achievementWatchDao().insert(first) != -1L)
            assertEquals(-1L, db.achievementWatchDao().insert(first))
            db.achievementWatchDao().insert(second)
            db.achievementWatchDao().insert(repair)
            db.close()
            db = open()
            val dao = db.achievementWatchDao()
            assertEquals(listOf(first, repair, second), dao.getAll("account"))
            assertTrue(dao.getAll("other-account").isEmpty())
            assertEquals(1, dao.claim("one", AchievementWatchWork.FOREGROUND))
            assertEquals(0, dao.claim("one", AchievementWatchWork.BACKGROUND))
            dao.finishRecompute("repair")
            assertEquals(2, dao.needingRecompute().size)
            dao.closeEpoch("epoch")
            dao.prune(10)
            // A foreground event still needing recompute and an unclaimed event are retained.
            assertEquals(listOf("one", "two"), dao.getAll("account").map { it.eventId })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
