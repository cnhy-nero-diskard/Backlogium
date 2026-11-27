package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementInsightsRepositoryTest {
    @Test fun boundedEvidenceUsesSnapshotsDisclosesGapsAndFencesAccountsAndHiddenGames() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.playerProfileDao().insertIfMissing()
            db.playerProfileDao().resetForAccountChange("fixture")
            for (id in 1L..3L) {
                db.gameDao().upsert(Game(id, "Game $id", "", 0, 0, 0))
                db.gameAchievementSyncDao().upsert(GameAchievementSync(id, 100, 100, true, 100, completedAt = if (id == 1L) 150 else null))
            }
            db.achievementDao().upsertAll(listOf(
                Achievement(1, "start", unlocked = true, unlockedAt = 100, globalPercent = 90.0, snapshotPercent = 1.0, fetchedAt = 100),
                Achievement(1, "end", unlocked = true, unlockedAt = 200, fetchedAt = 100),
                Achievement(1, "undated", unlocked = true, fetchedAt = 100),
                Achievement(2, "baseline", unlocked = true, unlockedAt = 50, snapshotPercent = 5.0, fetchedAt = 100),
                Achievement(3, "hidden", unlocked = true, unlockedAt = 120, fetchedAt = 100),
                Achievement(1, "retired", unlocked = true, unlockedAt = 130, retired = true, fetchedAt = 100),
            ))
            db.hiddenGameDao().upsertAll(listOf(HiddenGame(3, 1)))
            val repo = AchievementInsightsRepository(db)
            val snapshot = repo.between("fixture", 100, 200).first()
            assertEquals(listOf("start"), snapshot.unlocks.map { it.apiName })
            assertEquals(1.0, snapshot.unlocks.single().rarityPercent!!, 0.0)
            assertEquals(listOf(DatedCompletionEvidence(1, 150)), snapshot.completions)
            assertEquals(1, snapshot.unsupportedUnlocks)
            assertEquals(1, snapshot.unsupportedCompletions)
            assertEquals(2, snapshot.lifetimeRarity.size)
            val next = repo.between("fixture", 200, 300).first()
            assertEquals(listOf("end"), next.unlocks.map { it.apiName })
            assertEquals(snapshot.lifetimeRarity, next.lifetimeRarity)
            assertEquals(AchievementEvidenceSnapshot(), repo.between("other", 100, 200).first())
            AccountRoomReset(db).resetForAccountChange("other")
            assertEquals(AchievementEvidenceSnapshot(), repo.between("fixture", 100, 200).first())
        } finally { db.close() }
    }
}
