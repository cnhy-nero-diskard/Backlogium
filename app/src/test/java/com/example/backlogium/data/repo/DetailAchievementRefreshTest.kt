package com.example.backlogium.data.repo

import com.example.backlogium.test.SettingsDataStoreRule
import org.junit.Rule

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.*
import com.example.backlogium.domain.*
import com.example.backlogium.work.SteamSyncCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DetailAchievementRefreshTest {
    @get:Rule val settingsFixture = SettingsDataStoreRule()

    private lateinit var db: BacklogiumDatabase
    private lateinit var marker: AccountChangeMarkerStore
    private lateinit var action: RefreshGameAchievementsUseCase
    private lateinit var repository: AchievementRepository
    private lateinit var api: Api
    private var configured: CredentialsState.Configured? = CredentialsState.Configured("fixture", "a")
    private val time = Clock()

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        marker = AccountChangeMarkerStore(context)
        marker.clear()
        db.playerProfileDao().upsert(PlayerProfile(steamId = "a"))
        db.gameDao().upsert(game())
        api = Api()
        val syncDao = object : GameAchievementSyncDao by db.gameAchievementSyncDao() {
            override suspend fun get(appId: Long): com.example.backlogium.data.local.entity.GameAchievementSync? {
                val row = db.gameAchievementSyncDao().get(appId)
                if (api.block && api.calls > 0) api.joined.complete(Unit)
                return row
            }
        }
        repository = AchievementRepository(api, db.achievementDao(), syncDao, db.gameDao(),
            HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time), time,
            RoomDatabaseTransactionScope(db))
        val updater = GamificationUpdater(db.sessionDao(), db.dailyProgressDao(), db.playerProfileDao(),
            db.hltbDataDao(), db.achievementDao(), db.gameDao(), db.hiddenGameDao())
        action = RefreshGameAchievementsUseCase(repository, db, object : CredentialsProvider {
            override suspend fun currentCredentials() = configured
        }, marker, SteamSyncCoordinator(), DerivedStateWriteCoordinator(), updater,
            DataStoreSettingsRepository(settingsFixture.create()), time)
    }

    @After fun close() { db.close() }
    private fun game() = Game(10, "Ten", "", 0, 0, 0)

    @Test fun contentComparisonIgnoresFreshnessButDetectsEqualCountSchemaAndRateChanges() = runBlocking {
        assertEquals(AchievementRefreshOutcome.UPDATED, action(10))
        val xp = db.playerProfileDao().get()!!.totalXp
        assertTrue(xp > 0)
        time.now++
        assertEquals(AchievementRefreshOutcome.NO_CHANGE, action(10))
        time.now += AchievementRepository.SCHEMA_WINDOW_MILLIS + 1
        api.description = "Changed description"
        assertEquals(AchievementRefreshOutcome.UPDATED, action(10))
        assertEquals("Changed description", db.achievementDao().getForGame(10).single().description)
        api.percent = 50.0
        time.now++
        assertEquals(AchievementRefreshOutcome.UPDATED, action(10))
        assertEquals(0.8, db.achievementDao().getForGame(10).single().snapshotPercent!!, 0.0)
        assertEquals(xp, db.playerProfileDao().get()!!.totalXp)
        assertTrue(db.sessionDao().getAll().isEmpty())
    }

    @Test fun missingCredentialsAndPendingResetNeverReachNetwork() = runBlocking {
        configured = null
        assertEquals(AchievementRefreshOutcome.NEEDS_CREDENTIALS, action(10))
        configured = CredentialsState.Configured("fixture", "a")
        marker.markPending("b")
        assertEquals(AchievementRefreshOutcome.DISCARDED, action(10))
        assertEquals(0, api.calls)
        assertTrue(db.achievementDao().getForGame(10).isEmpty())
    }

    @Test fun oldResponseCannotSurviveAnAccountRoundTrip() = runBlocking {
        api.block = true
        val refresh = async { action(10) }
        api.started.await()
        for (id in listOf("b", "a")) {
            marker.markPending(id)
            AccountRoomReset(db).resetForAccountChange(id)
            configured = CredentialsState.Configured("fixture", id)
            marker.clear()
        }
        db.gameDao().upsert(game())
        api.release.complete(Unit)
        assertEquals(AchievementRefreshOutcome.DISCARDED, refresh.await())
        assertTrue(db.achievementDao().getForGame(10).isEmpty())
        assertNull(db.gameAchievementSyncDao().get(10))
    }

    @Test fun pendingTransitionAfterFetchStartsDiscardsCommit() = runBlocking {
        api.block = true
        val refresh = async { action(10) }
        api.started.await()
        marker.markPending("b")
        api.release.complete(Unit)
        assertEquals(AchievementRefreshOutcome.DISCARDED, refresh.await())
        assertTrue(db.achievementDao().getForGame(10).isEmpty())
    }

    @Test fun hidingDuringFetchDiscardsCommit() = runBlocking {
        api.block = true
        val refresh = async { action(10) }
        api.started.await()
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(10, time.now, false)))
        api.release.complete(Unit)
        assertEquals(AchievementRefreshOutcome.DISCARDED, refresh.await())
        assertTrue(db.achievementDao().getForGame(10).isEmpty())
    }

    @Test fun removingDuringFetchCannotRecreateGameOrAchievements() = runBlocking {
        api.block = true
        val refresh = async { action(10) }
        api.started.await()
        db.gameDao().deleteAll()
        api.release.complete(Unit)
        assertEquals(AchievementRefreshOutcome.DISCARDED, refresh.await())
        assertNull(db.gameDao().getById(10))
    }

    @Test fun sameAccountKeyRotationKeepsResponseEligible() = runBlocking {
        api.block = true
        val refresh = async { action(10) }
        api.started.await()
        configured = CredentialsState.Configured("rotated-fixture", "a")
        api.release.complete(Unit)
        assertEquals(AchievementRefreshOutcome.UPDATED, refresh.await())
        assertEquals(1, db.achievementDao().getForGame(10).size)
    }

    @Test fun unusableAndTransportFailureRetainLastGoodContent() = runBlocking {
        action(10)
        val before = db.achievementDao().getForGame(10)
        api.usable = false
        assertEquals(AchievementRefreshOutcome.NO_USABLE_DATA, action(10))
        api.fail = true
        assertEquals(AchievementRefreshOutcome.FAILED, action(10))
        assertEquals(before, db.achievementDao().getForGame(10))
    }

    @Test fun detailAndSyncShareOneInFlightFetchAndCommitSerially() = runBlocking {
        api.block = true
        val detail = async { action(10) }
        api.started.await()
        val sync = async { repository.refreshOne("fixture", "a", 10) }
        api.joined.await()
        // The second caller has read its metadata and enters the shared flight before release.
        kotlinx.coroutines.yield()
        api.release.complete(Unit)
        assertTrue(detail.await() in listOf(AchievementRefreshOutcome.UPDATED, AchievementRefreshOutcome.NO_CHANGE))
        assertTrue(sync.await() is SingleGameRefresh.Persisted)
        assertEquals(1, api.calls)
        assertEquals(1, db.achievementDao().getForGame(10).size)
    }

    private class Clock : TimeProvider {
        var now = 1_800_000_000_000L
        override fun nowMillis() = now
        override fun today() = LocalDate.of(2026, 10, 3)
        override fun zone(): ZoneId = ZoneId.of("UTC")
    }

    private class Api : SteamApi by OfflineSteamApiDouble {
        var calls = 0
        var block = false
        var usable = true
        var fail = false
        var description = "Earn it"
        var percent = 0.8
        val started = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun getPlayerAchievements(key: String, steamId: String, appId: Long,
            scope: SyncRunRecorder.RunScope?): PlayerAchievementsResponse {
            calls++
            started.complete(Unit)
            if (block) release.await()
            if (fail) throw java.io.IOException("fixture transport failure")
            return PlayerAchievementsResponse(PlayerAchievementsResult(success = usable,
                achievements = listOf(PlayerAchievementDto("ONE", 1, 1_700_000_000))))
        }
        override suspend fun getGlobalAchievementPercentages(gameId: Long,
            scope: SyncRunRecorder.RunScope?) = GlobalAchievementPercentagesResponse(
            GlobalAchievementPercentagesResult(listOf(GlobalAchievementPercentageDto("ONE", percent))))
        override suspend fun getSchemaForGame(key: String, appId: Long,
            scope: SyncRunRecorder.RunScope?) = GameSchemaResponse(GameSchemaResult(
            AvailableGameStatsDto(listOf(AchievementSchemaDto("ONE", "One", description = description)))))
    }
}
