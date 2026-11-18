package com.example.backlogium.data.achievement

import android.Manifest
import android.content.pm.PackageManager
import android.net.InetAddresses
import android.os.Process
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import okhttp3.Dns
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
    private val restartName get() = InstrumentationRegistry.getArguments().getString("achievementRestartDatabase")

    private fun repository(db: BacklogiumDatabase, api: SteamApi, credentials: CredentialsProvider): AchievementRepository {
        val time = SystemTimeProvider()
        return AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(),
            HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time), time,
            AchievementObservationStore(db, credentials, AccountChangeMarkerStore(context), SteamSyncCoordinator()))
    }

    @Test fun reportedUnplayedGamesLoadOnlyTheirCataloguesAndCacheSurvivesReopen() = runBlocking {
        val requested = mutableListOf<Pair<String, Long>>()
        val outcomes = mutableListOf<String>()
        val clientBuilder = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        InstrumentationRegistry.getArguments().getString("achievementSteamIpv4")?.let { address ->
            // Use a host-resolved address when the emulator DNS fails. HTTPS still validates Steam's hostname.
            clientBuilder.dns(object : Dns {
                override fun lookup(hostname: String) = if (hostname == "api.steampowered.com") {
                    listOf(InetAddresses.parseNumericAddress(address))
                } else {
                    Dns.SYSTEM.lookup(hostname)
                }
            })
        }
        val client = clientBuilder.addInterceptor { chain ->
            val request = chain.request()
            val appId = (request.url.queryParameter("appid") ?: request.url.queryParameter("gameid"))?.toLongOrNull()
            if (appId != null) synchronized(requested) {
                requested += request.url.encodedPath to appId
            }
            try {
                chain.proceed(request).also { response ->
                    synchronized(outcomes) { outcomes += "${request.url.encodedPath}: HTTP ${response.code}" }
                }
            } catch (error: java.io.IOException) {
                synchronized(outcomes) { outcomes += "${request.url.encodedPath}: ${error.javaClass.simpleName}" }
                throw error
            }
        }.build()
        val api = NetworkModule.provideSteamApi(NetworkModule.provideRetrofit(NetworkModule.provideJson(), client))
        val credentials = CredentialsRepository(EncryptedCredentialStore(context), api)
        val account = checkNotNull(credentials.currentCredentials()) {
            "Configure Steam in the emulator app or in git-ignored local.properties before the live catalogue check"
        }.steamId
        val name = "achievement-live-${System.nanoTime()}"
        var db = open(name)
        try {
            db.playerProfileDao().insertIfMissing()
            db.playerProfileDao().resetForAccountChange(account)
            (1L..300).forEach { db.gameDao().upsert(game(it)) }
            val targets = listOf(1462040L, 2909400L, 552520L)
            targets.forEach { db.gameDao().upsert(game(it)) }
            var repo = repository(db, api, credentials)
            for (appId in targets) {
                val result = withTimeout(60_000) { repo.hydrateDetail(appId).first { it !is AchievementHydrationState.Loading } }
                assertEquals("Catalogue unavailable for app $appId. Requests: $outcomes", AchievementHydrationState.Ready, result)
                assertTrue("Expected objectives for app $appId", repo.observeForGame(appId).first().isNotEmpty())
            }
            assertEquals(targets, requested.filter { (path, _) -> path.contains("GetSchemaForGame") }.map { it.second })
            assertTrue("Every achievement request must target an opened game", requested.all { (_, appId) -> appId in targets })
            val requestCount = requested.size
            db.close()
            db = open(name)
            repo = repository(db, api, credentials)
            for (appId in targets) {
                assertEquals(AchievementHydrationState.Ready, repo.hydrateDetail(appId).first())
                assertTrue(repo.observeForGame(appId).first().isNotEmpty())
            }
            assertEquals("Reopening fresh catalogues must not repeat achievement requests", requestCount, requested.size)
            assertTrue("Browsing must not create playtime sessions", db.sessionDao().getAll().isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun syntheticCatalogueCacheAndLaterProgressAcrossProcessRestart() = runBlocking {
        val name = restartName
        assumeTrue("Set achievementRestartDatabase for this two-phase check", name != null)
        checkNotNull(name)
        val marker = context.cacheDir.resolve("$name-catalogue")
        val restarted = marker.isFile
        val account = "catalogue-fixture"
        val positive = 1462040L
        val empty = 301L
        val requestedSchemas = mutableListOf<Long>()
        val requestedPlayers = mutableListOf<Long>()
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured("fixture", account)
        }
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, method, args ->
            when (method.name) {
                "getSchemaForGame" -> {
                    val appId = args[1] as Long
                    requestedSchemas += appId
                    val entries = if (appId == empty) emptyList() else listOf(AchievementSchemaDto("fixture", "Fixture objective"))
                    GameSchemaResponse(GameSchemaResult(AvailableGameStatsDto(entries)))
                }
                "getPlayerAchievements" -> {
                    requestedPlayers += args[2] as Long
                    PlayerAchievementsResponse(PlayerAchievementsResult(success = restarted,
                        achievements = if (restarted) listOf(PlayerAchievementDto("fixture", achieved = 1)) else emptyList()))
                }
                "getGlobalAchievementPercentages" -> GlobalAchievementPercentagesResponse(
                    GlobalAchievementPercentagesResult(listOf(GlobalAchievementPercentageDto("fixture", 12.0))),
                )
                else -> error("Unexpected request: ${method.name}")
            }
        } as SteamApi
        val db = open(name)
        var preserveForRestart = false
        try {
            if (restarted) {
                assertNotEquals("Run phase two in a fresh Android process", marker.readText().toInt(), Process.myPid())
            } else {
                db.playerProfileDao().insertIfMissing()
                db.playerProfileDao().resetForAccountChange(account)
                (1L..300L).forEach { db.gameDao().upsert(game(it)) }
                db.gameDao().upsert(game(positive))
                db.gameDao().upsert(game(empty))
            }
            val repo = repository(db, api, credentials)
            for (appId in listOf(positive, empty)) {
                assertEquals(AchievementHydrationState.Ready, withTimeout(10_000) {
                    repo.hydrateDetail(appId).first { it != AchievementHydrationState.Loading }
                })
            }
            assertFalse(repo.observeForGame(positive).first().single().progressKnown)
            assertTrue(repo.observeCatalogue(empty).first().confirmedEmpty)
            if (restarted) {
                assertTrue(requestedSchemas.isEmpty())
                assertTrue(requestedPlayers.isEmpty())
                assertEquals(SingleGameRefresh.Persisted(total = 1, unlocked = 1), repo.refreshOne("fixture", account, positive))
                val achievement = repo.observeForGame(positive).first().single()
                assertTrue(achievement.progressKnown && achievement.unlocked)
                assertEquals(12.0, achievement.rarityPercent!!, 0.0)
                assertTrue(requestedSchemas.isEmpty())
                assertEquals(listOf(positive), requestedPlayers)
                assertTrue(db.achievementWatchDao().getAll(account).none { it.payload != null })
            } else {
                assertEquals(listOf(positive, empty), requestedSchemas)
                assertEquals(listOf(positive, empty), requestedPlayers)
                marker.writeText(Process.myPid().toString())
                preserveForRestart = true
            }
        } finally {
            db.close()
            if (!preserveForRestart) {
                context.deleteDatabase(name)
                marker.delete()
            }
        }
        Unit
    }

    @Test fun selectedCadenceSwitchExitRestartAndAlertOptOutWorkOnDevice() = runBlocking {
        val name = restartName ?: "achievement-watch-${System.nanoTime()}"
        val db = open(name)
        var preserveForRestart = false
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
            watcher.beforePresence("device-fixture", null)
            watcher.afterPresence("device-fixture", null)
            withTimeout(10_000) {
                db.achievementWatchDao().observePending().first { rows -> rows.any { it.eventId == first.eventId && it.epochClosed } }
                while (db.achievementWatchDao().get(second.eventId)?.epochClosed != true) delay(20)
            }
            watcher.stop()
            scope.coroutineContext[Job]!!.cancelAndJoin()
            assertTrue(db.sessionDao().getAll().isEmpty())
            assertEquals(2, calls[1]!!.get())
            assertEquals(2, calls[2]!!.get())
            assertEquals(AchievementWatchWork.PENDING, db.achievementWatchDao().get(first.eventId)!!.deliveryState)
            assertEquals(AchievementWatchWork.SUPPRESSED, db.achievementWatchDao().get(second.eventId)!!.deliveryState)
            if (restartName != null) {
                context.cacheDir.resolve(name).writeText(Process.myPid().toString())
                preserveForRestart = true
            }
        } finally {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            settings.setAchievementWatch(previousWatch)
            settings.setLiveMonitorEnabled(previousMonitor)
            db.close()
            if (!preserveForRestart) context.deleteDatabase(name)
        }
        Unit
    }

    @Test fun pendingAndSuppressedGroupsSurviveProcessRestart() = runBlocking {
        val name = restartName
        val processMarker = name?.let { context.cacheDir.resolve(it) }
        assumeTrue("Run the cadence phase before the restart phase", processMarker?.isFile == true)
        checkNotNull(name)
        checkNotNull(processMarker)
        val db = open(name)
        try {
            assertNotEquals("Restart phase must run in a new app process", processMarker.readText().toInt(), Process.myPid())
            val work = db.achievementWatchDao().getAll("device-fixture").associateBy { it.appId }
            assertEquals(setOf(1L, 2L), work.keys)
            assertEquals(AchievementWatchWork.PENDING, work.getValue(1).deliveryState)
            assertEquals(AchievementWatchWork.SUPPRESSED, work.getValue(2).deliveryState)
            assertTrue(work.values.all { it.epochClosed && it.recomputeNeeded })
            assertEquals(listOf(work.getValue(1).eventId), db.achievementWatchDao().observePending().first().map { it.eventId })
        } finally {
            db.close()
            context.deleteDatabase(name)
            processMarker.delete()
        }
        Unit
    }

    @Test fun deniedNotificationPermissionSuppressesPostingOnDevice() {
        assertEquals("Revoke notification permission on the targeted test device before running this check",
            PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val group = AchievementUnlockGroup("device-permission-fixture", "device-fixture", 1, "Fixture", 1,
            listOf(AchievementUnlockItem("first", "First", null, null, 5.0)))
        assertEquals(AchievementUnlockNotifier.Result.SUPPRESSED, AndroidAchievementUnlockNotifier(context).post(group))
    }
}
