package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.achievement.AchievementRecomputeRecovery
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.AchievementWatchWork
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.domain.*
import com.example.backlogium.work.SteamSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementRecomputeRecoveryTest {
    @Test fun restartCompletesRepairWorkAndRepeatRecoveryIsIdempotent() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.playerProfileDao().insertIfMissing()
            db.playerProfileDao().resetForAccountChange("fixture")
            db.gameDao().upsert(Game(1, "Fixture", "", 0, 0, 0))
            db.achievementDao().upsertAll(listOf(Achievement(1, "first", unlocked = true, snapshotPercent = 0.1, fetchedAt = 1)))
            val work = AchievementWatchWork("repair", "fixture", 1, "epoch", 1, true)
            db.achievementWatchDao().insert(work)
            val updater = GamificationUpdater(db.sessionDao(), db.dailyProgressDao(), db.playerProfileDao(),
                db.hltbDataDao(), db.achievementDao(), db.gameDao(), db.hiddenGameDao())
            val recovery = AchievementRecomputeRecovery(db, FakeSettingsRepository(), updater, SystemTimeProvider(),
                SteamSyncCoordinator(), DerivedStateWriteCoordinator(), AccountChangeMarkerStore(context))
            recovery.resume()
            val xp = db.playerProfileDao().get()!!.totalXp
            assertTrue(xp > 0)
            assertTrue(db.achievementWatchDao().needingRecompute().isEmpty())
            // Simulate a process interruption after recompute but before acknowledging the marker.
            db.achievementWatchDao().insert(work.copy(eventId = "replay"))
            recovery.resume()
            assertEquals(xp, db.playerProfileDao().get()!!.totalXp)
            assertTrue(db.achievementWatchDao().needingRecompute().isEmpty())
        } finally { db.close() }
    }
}
