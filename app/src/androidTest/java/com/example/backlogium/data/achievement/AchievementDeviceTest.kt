package com.example.backlogium.data.achievement

import android.Manifest
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.BuildConfig
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.credentials.EncryptedCredentialStore
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.AchievementWatchWork
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.*
import com.example.backlogium.data.repo.*
import com.example.backlogium.di.NetworkModule
import com.example.backlogium.domain.*
import com.example.backlogium.work.ActivityVisibilityTracker
import com.example.backlogium.work.SteamSyncCoordinator
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with instrumentation argument verifyAchievementDevice=true after revoking notification
 * permission on the targeted emulator. Uses temporary databases and local debug credentials;
 * no account responses or credentials become fixtures. These network/device checks stay out of CI.
 */
@RunWith(AndroidJUnit4::class)
class AchievementDeviceTest {
    @Before fun requireExplicitDeviceVerification() {
        assumeTrue("Enable verifyAchievementDevice for the manual device acceptance run",
            InstrumentationRegistry.getArguments().getString("verifyAchievementDevice") == "true")
    }

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun game(appId: Long) = Game(appId, "Test game", "", 0, 0, 0)
    private fun open(name: String) = Room.databaseBuilder(context, BacklogiumDatabase::class.java, name).build()

    private fun repository(db: BacklogiumDatabase, api: SteamApi, credentials: CredentialsProvider): AchievementRepository {
        val time = SystemTimeProvider()
        return AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(),
            HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time), time,
            AchievementObservationStore(db, credentials, AccountChangeMarkerStore(context), SteamSyncCoordinator()))
    }

    @Test fun reportedUnplayedGamesLoadOnlyTheirCataloguesAndCacheSurvivesReopen() = runBlocking {
        // Opted-in local verification only: read the debug configuration, never log it.
        assertTrue("Local debug Steam credentials are required for live catalogue verification",
            BuildConfig.STEAM_API_KEY.isNotBlank() && BuildConfig.STEAM_ID.isNotBlank())
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured(BuildConfig.STEAM_API_KEY, BuildConfig.STEAM_ID)
        }
        val requested = mutableListOf<Long>()
        val client = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).addInterceptor { chain ->
            val request = chain.request()
            if (request.url.encodedPath.contains("GetSchemaForGame")) synchronized(requested) {
                request.url.queryParameter("appid")?.toLongOrNull()?.let { requested += it }
            }
            chain.proceed(request)
        }.build()
        val api = NetworkModule.provideSteamApi(NetworkModule.provideRetrofit(NetworkModule.provideJson(), client))
        val name = "achievement-live-${System.nanoTime()}"
        var db = open(name)
        try {
            db.playerProfileDao().insertIfMissing()
            db.playerProfileDao().resetForAccountChange(BuildConfig.STEAM_ID)
            (1L..300).forEach { db.gameDao().upsert(game(it)) }
            val targets = listOf(1462040L, 2909400L, 552520L)
            targets.forEach { db.gameDao().upsert(game(it)) }
            var repo = repository(db, api, credentials)
            for (appId in targets) {
                val result = withTimeout(60_000) { repo.hydrateDetail(appId).first { it !is AchievementHydrationState.Loading } }
                assertEquals("Catalogue unavailable for app $appId", AchievementHydrationState.Ready, result)
                assertTrue("Expected objectives for app $appId", repo.observeForGame(appId).first().isNotEmpty())
            }
            assertEquals(targets.toSet(), requested.toSet())
            assertEquals(targets.size, requested.size)
            db.close()
            db = open(name)
            repo = repository(db, api, credentials)
            for (appId in targets) {
                assertEquals(AchievementHydrationState.Ready, repo.hydrateDetail(appId).first())
                assertTrue(repo.observeForGame(appId).first().isNotEmpty())
            }
            assertEquals("Reopening fresh catalogues must not repeat schema work", targets.size, requested.size)
            assertTrue("Browsing must not create playtime sessions", db.sessionDao().getAll().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun selectedCadenceSwitchExitRestartAndAlertOptOutWorkOnDevice() = runBlocking {
        val name = "achievement-watch-${System.nanoTime()}"
        var db = open(name)
        val settings = DataStoreSettingsRepository(SettingsDataStore(context))
        val previousWatch = settings.achievementWatch.first()
        val previousMonitor = settings.liveMonitorEnabled.first()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = ConcurrentHashMap<Long, AtomicInteger>()
        val unlocked = ConcurrentHashMap<Long, Boolean>()
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured("fixture", "device-fixture")
        }
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, method, args ->
            when (method.name) {
                "getSchemaForGame" -> GameSchemaResponse(GameSchemaResult(AvailableGameStatsDto(listOf(AchievementSchemaDto("first", "First")))))
                "getPlayerAchievements" -> {
                    val appId = args[2] as Long
                    calls.computeIfAbsent(appId) { AtomicInteger() }.incrementAndGet()
                    PlayerAchievementsResponse(PlayerAchievementsResult(true, achievements = listOf(PlayerAchievementDto("first", if (unlocked[appId] == true) 1 else 0))))
                }
                "getGlobalAchievementPercentages" -> GlobalAchievementPercentagesResponse(GlobalAchievementPercentagesResult(listOf(GlobalAchievementPercentageDto("first", 5.0))))
                else -> error("A watch must not request playtime")
            }
        } as SteamApi
        try {
            settings.setLiveMonitorEnabled(true)
            settings.setAchievementWatch(AchievementWatchPreferences(intervalSeconds = 30))
            db.playerProfileDao().insertIfMissing()
            db.playerProfileDao().resetForAccountChange("device-fixture")
            db.gameDao().upsert(game(1)); db.gameDao().upsert(game(2))
            val time = SystemTimeProvider()
            val hidden = HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time)
            val network = object : AchievementNetwork(context) { override fun isOnline() = true }
            val watcher = AchievementWatchCoordinator(repository(db, api, credentials), settings, credentials, hidden, network, db, time,
                SyncRunRecorder(db.diagnosticsDao(), time), scope)
            watcher.monitorStarted()
            watcher.afterPresence("device-fixture", 1)
            withTimeout(10_000) { db.diagnosticsDao().observeRuns().first { it.isNotEmpty() } }
            assertTrue(db.achievementWatchDao().getAll("device-fixture").isEmpty())
            val seededAt = System.currentTimeMillis()
            unlocked[1] = true
            val first = withTimeout(45_000) { db.achievementWatchDao().observePending().first { it.isNotEmpty() }.single() }
            assertTrue("Selected cadence must not issue catch-up reads", System.currentTimeMillis() - seededAt >= 29_000)
            assertEquals(2, calls[1]!!.get())
            watcher.beforePresence("device-fixture", 2)
            watcher.afterPresence("device-fixture", 2)
            withTimeout(10_000) { db.diagnosticsDao().observeRuns().first { runs -> runs.any { it.errorMessage == "appId=2;outcome=unchanged" } } }
            settings.setAchievementWatch(AchievementWatchPreferences(intervalSeconds = 30, alertsEnabled = false))
            unlocked[2] = true
            val second = withTimeout(45_000) { db.achievementWatchDao().observePending().first { it.size == 2 }.first { it.appId == 2L } }
            assertTrue(db.achievementDao().getOne(2, "first")!!.unlocked)
            var posts = 0
            val dispatcher = AchievementUnlockDispatcher(db.achievementWatchDao(), AchievementObservationStore(db, credentials,
                AccountChangeMarkerStore(context), SteamSyncCoordinator()), settings, hidden,
                CredentialsRepository(EncryptedCredentialStore(context), api),
                ProgressEventRepository(InMemoryProgressMarksStore(), db.playerProfileDao(), db.dailyProgressDao(), ProgressTransitionCoordinator(), db.achievementWatchDao()),
                ActivityVisibilityTracker(context), object : AchievementUnlockNotifier {
                    override fun post(group: AchievementUnlockGroup): AchievementUnlockNotifier.Result { posts++; return AchievementUnlockNotifier.Result.POSTED }
                }, scope)
            dispatcher.dispatch(second, false)
            assertEquals(0, posts)
            assertEquals(AchievementWatchWork.SUPPRESSED, db.achievementWatchDao().get(second.eventId)!!.deliveryState)
            watcher.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            assertTrue(db.sessionDao().getAll().isEmpty())
            assertEquals(2, calls[1]!!.get())
            db.close()
            db = open(name)
            assertEquals(AchievementWatchWork.PENDING, db.achievementWatchDao().get(first.eventId)!!.deliveryState)
            assertEquals(AchievementWatchWork.SUPPRESSED, db.achievementWatchDao().get(second.eventId)!!.deliveryState)
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            settings.setAchievementWatch(previousWatch)
            settings.setLiveMonitorEnabled(previousMonitor)
            db.close(); context.deleteDatabase(name)
        }
    }

    @Test fun deniedNotificationPermissionSuppressesPostingOnDevice() {
        assertEquals("Revoke notification permission on the targeted test device before running this check",
            PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val group = AchievementUnlockGroup("device-permission-fixture", "device-fixture", 1, "Fixture", 1,
            listOf(AchievementUnlockItem("first", "First", null, null, 5.0)))
        assertEquals(AchievementUnlockNotifier.Result.SUPPRESSED, AndroidAchievementUnlockNotifier(context).post(group))
    }
}
