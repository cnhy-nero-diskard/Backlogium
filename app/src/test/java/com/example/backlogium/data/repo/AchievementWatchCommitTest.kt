package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.achievement.AchievementObservationStore
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.domain.AchievementUnlockGroup
import com.example.backlogium.domain.SystemTimeProvider
import com.example.backlogium.work.SteamSyncCoordinator
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementWatchCommitTest {
    private lateinit var db: BacklogiumDatabase
    private lateinit var repo: AchievementRepository
    private var account = "fixture-account"

    @Before
    fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().resetForAccountChange(account)
        db.gameDao().upsert(Game(1, "Fixture", "", 0, 0, 0))
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, _, _ ->
            error("A prepared commit must not issue network requests")
        } as SteamApi
        val time = SystemTimeProvider()
        repo = AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(),
            HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time), time,
            AchievementObservationStore(db, object : CredentialsProvider {
                override suspend fun currentCredentials() = CredentialsState.Configured("fixture", account)
            }, AccountChangeMarkerStore(context), SteamSyncCoordinator()))
    }

    @After fun close() = db.close()

    private fun reading(at: Long, unlocked: Boolean, percent: Double? = null) = AchievementRefresh(
        1, at, listOf(PlayerAchievementDto("first", if (unlocked) 1 else 0, 5)),
        percent?.let { mapOf("first" to it) }.orEmpty(), emptyMap(), null,
    )

    private suspend fun commit(refresh: AchievementRefresh, baseline: Set<String>? = emptySet(), valid: () -> Boolean = { true }) =
        repo.commitWatch("fixture-account", "epoch", refresh, baseline, valid)

    @Test fun deferredUnlockWritesNothingThenRetriesExactlyOneGroup() = runBlocking {
        val baseline = commit(reading(1, false), null) as AchievementRepository.WatchCommit.Committed
        assertTrue(baseline.unlocked.isEmpty())
        assertEquals(AchievementRepository.WatchCommit.DeferredRarity, commit(reading(2, true)))
        assertFalse(db.achievementDao().getOne(1, "first")!!.unlocked)
        assertTrue(db.achievementWatchDao().getAll(account).isEmpty())
        val result = commit(reading(3, true, 0.0)) as AchievementRepository.WatchCommit.Committed
        assertEquals(setOf("first"), result.unlocked)
        assertTrue(result.changedXp)
        val work = db.achievementWatchDao().getAll(account).single()
        val payload = Json.decodeFromString<AchievementUnlockGroup>(work.payload!!)
        assertEquals(listOf("first"), payload.achievements.map { it.apiName })
        assertEquals(0.0, payload.achievements.single().snapshotPercent!!, 0.0)
        commit(reading(3, true, 50.0))
        assertEquals(1, db.achievementWatchDao().getAll(account).size)
        assertEquals(0.0, db.achievementDao().getOne(1, "first")!!.snapshotPercent!!, 0.0)
    }

    @Test fun firstReadAndNullRepairProduceOnlyRecoverableWork() = runBlocking {
        db.achievementDao().upsertAll(listOf(Achievement(1, "first", unlocked = true, fetchedAt = 0)))
        assertEquals(AchievementRepository.WatchCommit.DeferredRarity, commit(reading(1, true), null))
        val seeded = commit(reading(2, true, 5.0), null) as AchievementRepository.WatchCommit.Committed
        assertNull(seeded.eventId)
        assertTrue(seeded.changedXp)
        assertNull(db.achievementWatchDao().getAll(account).single().payload)
        val unchanged = commit(reading(3, true), seeded.unlocked) as AchievementRepository.WatchCommit.Committed
        assertFalse(unchanged.changedXp)
        assertEquals(1, db.achievementWatchDao().getAll(account).size)
    }

    @Test fun normalRefreshCannotConsumeTheWatchBaselineAndRetiredRowsSurvive() = runBlocking {
        db.achievementDao().upsertAll(listOf(Achievement(1, "retired", unlocked = true, retired = true, snapshotPercent = 9.0, fetchedAt = 0)))
        commit(reading(1, false), null)
        repo.applyRefreshes(listOf(reading(2, true, 7.0)))
        val result = commit(reading(3, true)) as AchievementRepository.WatchCommit.Committed
        assertNotNull(result.eventId)
        assertFalse(result.changedXp)
        assertTrue(db.achievementDao().getOne(1, "retired")!!.retired)
        assertEquals(7.0, db.achievementDao().getOne(1, "first")!!.snapshotPercent!!, 0.0)
        assertEquals(AchievementRepository.WatchCommit.Stale, commit(reading(1, false)))
    }

    @Test fun cancellationRollsBackBothRowsAndOutbox() = runBlocking {
        var checks = 0
        try {
            commit(reading(1, true, 2.0)) { ++checks < 4 }
            fail("expected generation cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(db.achievementDao().getForGame(1).isEmpty())
        assertTrue(db.achievementWatchDao().getAll(account).isEmpty())
    }

    @Test fun accountHiddenAndRemovedGuardsRejectLateResponsesAndResetClearsWork() = runBlocking {
        account = "other-account"
        assertEquals(AchievementRepository.WatchCommit.Stale, commit(reading(1, true, 2.0)))
        account = "fixture-account"
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(1, 1, false)))
        assertEquals(AchievementRepository.WatchCommit.Stale, commit(reading(1, true, 2.0)))
        db.hiddenGameDao().deleteAll()
        commit(reading(1, true, 2.0))
        assertEquals(1, db.achievementWatchDao().getAll(account).size)
        AccountRoomReset(db).resetForAccountChange("other-account")
        assertTrue(db.achievementWatchDao().getAll(account).isEmpty())
        assertEquals(AchievementRepository.WatchCommit.Stale, commit(reading(2, true, 2.0)))
    }
}
