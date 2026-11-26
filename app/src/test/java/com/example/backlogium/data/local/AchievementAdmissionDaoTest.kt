package com.example.backlogium.data.local

import androidx.room.Room
import com.example.backlogium.data.local.entity.AchievementAdmissionWork
import com.example.backlogium.data.local.entity.Game
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementAdmissionDaoTest {
    @Test
    fun pendingAdmissionSurvivesRoomReopenAndKeepsItsRetryState() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = "achievement-admission-${System.nanoTime()}.db"
        fun open() = Room.databaseBuilder(context, BacklogiumDatabase::class.java, name)
            .allowMainThreadQueries().build()

        try {
            val first = open()
            first.gameDao().upsert(Game(10L, "Queued", "", 0, 0, 0))
            first.achievementAdmissionDao().insert(
                AchievementAdmissionWork("account", 7L, 10L, 100L, attempts = 2, nextEligibleAt = 500L),
            )
            assertTrue(first.achievementAdmissionDao().hasPendingForCommit("account", 7L, 100L))
            assertFalse(first.achievementAdmissionDao().hasPendingForCommit("account", 7L, 101L))
            first.close()

            val reopened = open()
            try {
                val pending = reopened.achievementAdmissionDao().eligible("account", 7L, 500L, 8)
                assertEquals(1, pending.size)
                assertEquals(2, pending.single().attempts)
                assertEquals(500L, pending.single().nextEligibleAt)
                assertTrue(reopened.achievementAdmissionDao().eligible("account", 6L, Long.MAX_VALUE, 8).isEmpty())
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
