package com.example.backlogium.data.achievement

import androidx.room.Room
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.*
import com.example.backlogium.data.repo.*
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.SystemTimeProvider
import com.example.backlogium.work.SteamSyncCoordinator
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class AchievementWatchDiagnosticsTest {
    @Test fun watchRecordsEachBoundedOutcomeWithoutCredentials() = runBlocking {
        for (outcome in listOf("success", "unchanged", "deferred_rarity", "offline", "cancelled", "throttled")) {
            val context = RuntimeEnvironment.getApplication()
            val db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                db.playerProfileDao().insertIfMissing()
                db.playerProfileDao().resetForAccountChange("fixture")
                db.gameDao().upsert(Game(1, "Fixture", "", 0, 0, 0))
                val settings = FakeSettingsRepository()
                settings.setLiveMonitorEnabled(true)
                val time = SystemTimeProvider()
                val hidden = HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time)
                val credentials = object : CredentialsProvider {
                    override suspend fun currentCredentials() = CredentialsState.Configured("fixture-secret", "fixture")
                }
                val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getSchemaForGame" -> {
                            if (outcome == "cancelled") throw CancellationException("fixture")
                            if (outcome == "throttled") throw HttpException(Response.error<Any>(429, "fixture".toResponseBody()))
                            GameSchemaResponse(GameSchemaResult(AvailableGameStatsDto(listOf(AchievementSchemaDto("first")))))
                        }
                        "getPlayerAchievements" -> PlayerAchievementsResponse(PlayerAchievementsResult(true, achievements = listOf(PlayerAchievementDto("first", if (outcome == "unchanged") 0 else 1))))
                        "getGlobalAchievementPercentages" -> GlobalAchievementPercentagesResponse(GlobalAchievementPercentagesResult(
                            if (outcome == "deferred_rarity") emptyList() else listOf(GlobalAchievementPercentageDto("first", 5.0))))
                        else -> error("Unexpected request")
                    }
                } as SteamApi
                val network = object : AchievementNetwork(context) { override fun isOnline() = outcome != "offline" }
                val repo = AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(), hidden, time,
                    AchievementObservationStore(db, credentials, AccountChangeMarkerStore(context), SteamSyncCoordinator()))
                val watcher = AchievementWatchCoordinator(repo, settings, credentials, hidden, network, db, time, SyncRunRecorder(db.diagnosticsDao(), time), scope)
                watcher.monitorStarted()
                watcher.afterPresence("fixture", 1)
                val run = withTimeout(10_000) { db.diagnosticsDao().observeRuns().first { it.isNotEmpty() }.first() }
                watcher.stop()
                assertEquals("achievement_watch", run.trigger)
                assertEquals("appId=1;outcome=$outcome", run.errorMessage)
                assertFalse(run.errorMessage!!.contains("fixture-secret"))
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); db.close() }
        }
    }
}
