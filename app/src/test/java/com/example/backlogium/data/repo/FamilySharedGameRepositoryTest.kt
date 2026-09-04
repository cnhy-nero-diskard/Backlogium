package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.local.entity.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGameDto
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResult
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResult
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.remote.dto.StoreAppData
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.data.remote.dto.StoreItemsResponse
import com.example.backlogium.data.remote.dto.StorePriceEnvelope
import com.example.backlogium.data.remote.dto.WishlistResponse
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeDailyProgressDao
import com.example.backlogium.domain.FakeGameDao
import com.example.backlogium.domain.FakeHltbDataDao
import com.example.backlogium.domain.FakePlayerProfileDao
import com.example.backlogium.domain.FakeSessionDao
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.InMemoryProgressMarksStore
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class FamilySharedGameRepositoryTest {

    @Test
    fun `track again restores the shared game row and removes its exclusion`() = runTest {
        val appId = 42L
        val gameDao = FakeGameDao(emptyList())
        val excludedDao = FakeExcludedSharedGameDao(
            ExcludedSharedGame(appId = appId, name = "Shared Game", excludedAt = 1_000L),
        )
        val repository = repository(gameDao, excludedDao)

        assertTrue(repository.reverseRemoval(appId))

        val restored = gameDao.getById(appId)
        assertNotNull(restored)
        assertEquals("Shared Game", restored?.name)
        assertEquals(GameSource.FAMILY_SHARED, restored?.source)
        assertEquals(2_000L, restored?.lastSyncedAt)
        assertEquals(listOf(appId), gameDao.observeLibrary().first().map { it.appId })
        assertFalse(excludedDao.isExcluded(appId))
        assertFalse(repository.reverseRemoval(appId))
    }

    // --- auditfix-session-ledger-integrity #104: removal is not earned progress ---

    @Test
    fun `removal reseeds the derived-value baseline instead of producing progress events`() = runTest {
        val appId = 42L
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val marksStore = InMemoryProgressMarksStore()
        val gamificationUpdater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            progressMarksStore = marksStore,
        )
        val repository = repositoryWithRealGamification(database, gamificationUpdater)

        try {
            database.gameDao().upsert(
                Game(appId, "Shared Game", "", 0, 0, 0, source = GameSource.FAMILY_SHARED),
            )
            database.sessionDao().insert(
                Session(appId = appId, startAt = 1_000L, endAt = 2_000L, minutes = 500, open = false),
            )
            // The initial state a device would already be in: play was tracked and earned XP,
            // and that earned rise was already celebrated (marks seed on the first recompute
            // regardless of source, matching ProgressEventDetector.seed()).
            gamificationUpdater.recompute(today = FixedTimeProvider.today(), source = RecomputeSource.SYNC)
            val levelBeforeRemoval = database.playerProfileDao().get()!!.level
            val marksBeforeRemoval = marksStore.read().lastCelebratedLevel
            assertEquals(levelBeforeRemoval, marksBeforeRemoval)
            assertTrue("the scenario needs real XP to remove", levelBeforeRemoval > 1)

            assertTrue(repository.remove(appId))

            val profileAfterRemoval = database.playerProfileDao().get()!!
            assertEquals(0L, profileAfterRemoval.totalXp) // the removed game was the only XP source
            assertTrue("removal must actually lower the level", profileAfterRemoval.level < levelBeforeRemoval)
            // Reseeded, not left stuck at the old higher mark the way an earned decrease would
            // leave it (ProgressEventDetector's earned branch only bumps the mark on a *rise*) —
            // this is the proof no progress event fired for a change the player did not earn.
            assertEquals(profileAfterRemoval.level, marksStore.read().lastCelebratedLevel)
        } finally {
            database.close()
        }
    }

    @Test
    fun `reversing a removal is equally non-earned`() = runTest {
        val appId = 43L
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        )
        val gamificationUpdater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            progressMarksStore = marksStore,
        )
        val excludedDao = FakeExcludedSharedGameDao(
            ExcludedSharedGame(appId = appId, name = "Restored Shared Game", excludedAt = 1_000L),
        )
        val repository = repositoryWithRealGamification(database, gamificationUpdater, excludedDao)

        try {
            // Nothing about restoring an empty row should move totalXp/level at all (the game's
            // history does not come back), but this still proves the recompute — if it moves
            // anything — declares non-earned provenance rather than SYNC.
            assertTrue(repository.reverseRemoval(appId))

            assertEquals(1, marksStore.read().lastCelebratedLevel)
            assertEquals(1, database.playerProfileDao().get()!!.level)
        } finally {
            database.close()
        }
    }

    @Test
    fun `an ordinary sync after a removal is earned normally against the reseeded baseline`() = runTest {
        val appId = 44L
        val ongoingAppId = 45L
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        val marksStore = InMemoryProgressMarksStore()
        val gamificationUpdater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            progressMarksStore = marksStore,
        )
        val repository = repositoryWithRealGamification(database, gamificationUpdater)

        try {
            database.gameDao().upsert(
                Game(appId, "Shared Game", "", 0, 0, 0, source = GameSource.FAMILY_SHARED),
            )
            database.gameDao().upsert(
                Game(ongoingAppId, "Owned Game", "", 0, 0, 0, source = GameSource.STEAM_OWNED),
            )
            database.sessionDao().insert(
                Session(appId = appId, startAt = 1_000L, endAt = 2_000L, minutes = 500, open = false),
            )
            gamificationUpdater.recompute(today = FixedTimeProvider.today(), source = RecomputeSource.SYNC)
            repository.remove(appId)
            val levelAfterRemoval = database.playerProfileDao().get()!!.level
            assertEquals(levelAfterRemoval, marksStore.read().lastCelebratedLevel) // reseeded

            // A later ordinary sync earns real new progress on top of the reseeded baseline.
            database.sessionDao().insert(
                Session(appId = ongoingAppId, startAt = 3_000L, endAt = 4_000L, minutes = 5_000, open = false),
            )
            gamificationUpdater.recompute(today = FixedTimeProvider.today(), source = RecomputeSource.SYNC)

            val levelAfterSync = database.playerProfileDao().get()!!.level
            assertTrue("this sync must be a real earned rise", levelAfterSync > levelAfterRemoval)
            // Left unacknowledged for the celebration UI — the proof this recompute went through
            // the earned path, measured against the baseline the removal reseeded.
            assertEquals(levelAfterRemoval, marksStore.read().lastCelebratedLevel)
        } finally {
            database.close()
        }
    }

    private fun repositoryWithRealGamification(
        database: BacklogiumDatabase,
        gamificationUpdater: GamificationUpdater,
        excludedDao: ExcludedSharedGameDao = FakeExcludedSharedGameDao(),
    ) = FamilySharedGameRepository(
        gameDao = database.gameDao(),
        excludedDao = excludedDao,
        profileDao = database.playerProfileDao(),
        settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
        store = SteamStoreAppDataSource(noOpProxy(SteamStoreApi::class.java)),
        genres = GameGenreRepository(
            cacheDao = noOpProxy(GameGenreCacheDao::class.java),
            store = SteamStoreGenreDataSource(noOpProxy(SteamStoreApi::class.java)),
            time = FixedTimeProvider,
        ),
        policy = com.example.backlogium.domain.SharedGameAdmissionPolicy(),
        notifier = object : SharedGameNotifier {
            override fun notifyAdmitted(appId: Long, name: String): Boolean = false
        },
        steamApi = noOpProxy(SteamApi::class.java),
        time = FixedTimeProvider,
        sessionDao = database.sessionDao(),
        dailyProgressDao = database.dailyProgressDao(),
        transaction = PassThroughTransactionScope,
        gamificationUpdater = gamificationUpdater,
        derivedStateWrites = DerivedStateWriteCoordinator(),
        achievementRepository = AchievementRepository(
            steamApi = noOpProxy(SteamApi::class.java),
            achievementDao = database.achievementDao(),
            gameAchievementSyncDao = noOpProxy(GameAchievementSyncDao::class.java),
            gameDao = database.gameDao(),
            time = FixedTimeProvider,
        ),
    )

    @Test
    fun `importManually rejects invalid input without any Steam calls`() = runTest {
        val result = repository().importManually("not-a-number", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.InvalidInput, result)
    }

    @Test
    fun `importManually reports owned when the app is already tracked as steam owned`() = runTest {
        val appId = 440L
        val gameDao = FakeGameDao(
            listOf(Game(appId, "Team Fortress 2", "", 100, 0, 100, source = GameSource.STEAM_OWNED)),
        )

        val result = repository(gameDao = gameDao).importManually("440", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.Owned(appId, "Team Fortress 2"), result)
    }

    @Test
    fun `importManually reports owned when the app appears in the current owned library`() = runTest {
        val appId = 620L
        val steamApi = FakeSteamApi(
            ownedGames = {
                OwnedGamesResponse(
                    OwnedGamesResult(gameCount = 1, games = listOf(OwnedGameDto(appid = appId, name = "Portal 2"))),
                )
            },
        )

        val result = repository(steamApi = steamApi).importManually("620", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.Owned(appId, "Portal 2"), result)
    }

    @Test
    fun `importManually reports excluded for a removed shared game`() = runTest {
        val appId = 42L
        val excludedDao = FakeExcludedSharedGameDao(ExcludedSharedGame(appId, "Shared Game", 1_000L))

        val result = repository(excludedDao = excludedDao).importManually("42", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.Excluded(appId), result)
    }

    @Test
    fun `importManually reports not a game when it was previously verified not to be one`() = runTest {
        val appId = 730L
        val settings = SettingsDataStore(RuntimeEnvironment.getApplication())
        settings.markSharedGameNotAGame(appId)

        val result = repository(settings = settings).importManually("730", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.NotAGame(appId), result)
    }

    @Test
    fun `importManually reports not a game when store verification says so`() = runTest {
        val appId = 950L
        val steamApi = FakeSteamApi(ownedGames = { OwnedGamesResponse() })
        val storeApi = FakeSteamStoreApi(
            respond = { id ->
                Response.success(mapOf(id.toString() to StoreAppDetails(true, StoreAppData(type = "dlc", name = "Some DLC"))))
            },
        )

        val result = repository(steamApi = steamApi, storeApi = storeApi)
            .importManually("950", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.NotAGame(appId), result)
    }

    @Test
    fun `importManually reports unavailable when the owned library check fails`() = runTest {
        val appId = 100L
        val steamApi = FakeSteamApi(ownedGames = { throw IOException("offline") })

        val result = repository(steamApi = steamApi).importManually("100", "key", "76561198000000000")

        assertEquals(
            ManualSharedGameImportResult.Unavailable(appId, ManualImportUnavailableAt.OWNED_LIBRARY),
            result,
        )
    }

    @Test
    fun `importManually reports unavailable when store verification fails`() = runTest {
        val appId = 200L
        val steamApi = FakeSteamApi(ownedGames = { OwnedGamesResponse() })
        val storeApi = FakeSteamStoreApi(respond = { throw IOException("store down") })

        val result = repository(steamApi = steamApi, storeApi = storeApi)
            .importManually("200", "key", "76561198000000000")

        assertEquals(ManualSharedGameImportResult.Unavailable(appId, ManualImportUnavailableAt.STORE), result)
    }

    @Test
    fun `importManually imports a new shared game and reports achievement data`() = runTest {
        val appId = 300L
        val steamApi = FakeSteamApi(
            ownedGames = { OwnedGamesResponse() },
            playerAchievements = {
                PlayerAchievementsResponse(
                    PlayerAchievementsResult(
                        success = true,
                        achievements = listOf(
                            PlayerAchievementDto(apiName = "ACH_1", achieved = 1),
                            PlayerAchievementDto(apiName = "ACH_2", achieved = 0),
                        ),
                    ),
                )
            },
        )
        val storeApi = FakeSteamStoreApi(
            respond = { id ->
                Response.success(mapOf(id.toString() to StoreAppDetails(true, StoreAppData(type = "game", name = "Borrowed Game"))))
            },
        )
        val gameDao = FakeGameDao(emptyList())

        val result = repository(gameDao = gameDao, steamApi = steamApi, storeApi = storeApi)
            .importManually("300", "key", "76561198000000000")

        assertEquals(
            ManualSharedGameImportResult.Imported(appId, "Borrowed Game", false, PlayerDataProbe.Returned(2, 1)),
            result,
        )
        assertEquals(GameSource.FAMILY_SHARED, gameDao.getById(appId)?.source)
    }

    /**
     * The bug fix-shared-game-achievement-visibility exists for: the import message previously
     * reported achievement data that was never actually written anywhere, so the game's detail
     * screen showed nothing. The probed data must now be persisted, not just summarized.
     */
    @Test
    fun `importManually persists the probed achievement data, not just a summary`() = runTest {
        val appId = 300L
        val steamApi = FakeSteamApi(
            ownedGames = { OwnedGamesResponse() },
            playerAchievements = {
                PlayerAchievementsResponse(
                    PlayerAchievementsResult(
                        success = true,
                        achievements = listOf(
                            PlayerAchievementDto(apiName = "ACH_1", achieved = 1),
                            PlayerAchievementDto(apiName = "ACH_2", achieved = 0),
                        ),
                    ),
                )
            },
        )
        val storeApi = FakeSteamStoreApi(
            respond = { id ->
                Response.success(mapOf(id.toString() to StoreAppDetails(true, StoreAppData(type = "game", name = "Borrowed Game"))))
            },
        )
        val gameDao = FakeGameDao(emptyList())
        val achievementDao = FakeAchievementDao()
        val syncDao = FakeGameAchievementSyncDao()

        repository(
            gameDao = gameDao,
            steamApi = steamApi,
            storeApi = storeApi,
            achievementDao = achievementDao,
            syncDao = syncDao,
        ).importManually("300", "key", "76561198000000000")

        assertEquals(
            "the detail screen's query depends on real rows existing, not just the toast summary",
            2,
            achievementDao.getForGame(appId).size,
        )
        assertNotNull(
            "a sync row must exist so this game is not re-treated as missing data forever",
            syncDao.get(appId),
        )
    }

    @Test
    fun `importManually imports a new shared game and reports no achievement data`() = runTest {
        val appId = 301L
        val steamApi = FakeSteamApi(
            ownedGames = { OwnedGamesResponse() },
            playerAchievements = { PlayerAchievementsResponse(PlayerAchievementsResult(success = false)) },
        )
        val storeApi = FakeSteamStoreApi(
            respond = { id ->
                Response.success(
                    mapOf(id.toString() to StoreAppDetails(true, StoreAppData(type = "game", name = "Another Borrowed Game"))),
                )
            },
        )

        val result = repository(steamApi = steamApi, storeApi = storeApi)
            .importManually("301", "key", "76561198000000000")

        assertEquals(
            ManualSharedGameImportResult.Imported(appId, "Another Borrowed Game", false, PlayerDataProbe.NoData),
            result,
        )
    }

    @Test
    fun `importManually reports already tracked for an existing shared game`() = runTest {
        val appId = 400L
        val gameDao = FakeGameDao(
            listOf(Game(appId, "Already Shared", "", 0, 0, 0, source = GameSource.FAMILY_SHARED)),
        )
        val steamApi = FakeSteamApi(
            playerAchievements = {
                PlayerAchievementsResponse(PlayerAchievementsResult(success = true, achievements = emptyList()))
            },
        )

        val result = repository(gameDao = gameDao, steamApi = steamApi)
            .importManually("400", "key", "76561198000000000")

        // Neither the owned-library check nor Store verification runs for an already-tracked game
        // (FakeSteamApi.ownedGames and FakeSteamStoreApi.appDetails default to failing if called).
        assertEquals(
            ManualSharedGameImportResult.Imported(appId, "Already Shared", true, PlayerDataProbe.Returned(0, 0)),
            result,
        )
    }

    @Test
    fun `importManually reports the achievement probe as unavailable when the request fails`() = runTest {
        val appId = 401L
        val gameDao = FakeGameDao(
            listOf(Game(appId, "Flaky Probe", "", 0, 0, 0, source = GameSource.FAMILY_SHARED)),
        )
        val steamApi = FakeSteamApi(playerAchievements = { throw IOException("timeout") })

        val result = repository(gameDao = gameDao, steamApi = steamApi)
            .importManually("401", "key", "76561198000000000")

        assertEquals(
            ManualSharedGameImportResult.Imported(appId, "Flaky Probe", true, PlayerDataProbe.Unavailable),
            result,
        )
    }

    private fun repository(
        gameDao: GameDao = FakeGameDao(emptyList()),
        excludedDao: ExcludedSharedGameDao = FakeExcludedSharedGameDao(),
        settings: SettingsDataStore = SettingsDataStore(RuntimeEnvironment.getApplication()),
        steamApi: SteamApi = noOpProxy(SteamApi::class.java),
        storeApi: SteamStoreApi = noOpProxy(SteamStoreApi::class.java),
        achievementDao: FakeAchievementDao = FakeAchievementDao(),
        syncDao: FakeGameAchievementSyncDao = FakeGameAchievementSyncDao(),
        // Real (if empty) fakes, not no-op proxies: remove() and reverseRemoval() both recompute
        // (auditfix-session-ledger-integrity, #104), so every DAO on that path must survive being
        // actually called, not just constructed. Shared with gamificationUpdater below so a
        // recompute sees the same game/session/profile state the repository itself just wrote.
        sessionDao: SessionDao = FakeSessionDao(emptyList()),
        dailyProgressDao: DailyProgressDao = FakeDailyProgressDao(emptyList()),
        profileDao: PlayerProfileDao = FakePlayerProfileDao(),
    ) = FamilySharedGameRepository(
        gameDao = gameDao,
        excludedDao = excludedDao,
        profileDao = profileDao,
        settings = settings,
        store = SteamStoreAppDataSource(storeApi),
        genres = GameGenreRepository(
            cacheDao = noOpProxy(GameGenreCacheDao::class.java),
            store = SteamStoreGenreDataSource(noOpProxy(SteamStoreApi::class.java)),
            time = FixedTimeProvider,
        ),
        policy = com.example.backlogium.domain.SharedGameAdmissionPolicy(),
        notifier = object : SharedGameNotifier {
            override fun notifyAdmitted(appId: Long, name: String): Boolean = false
        },
        steamApi = steamApi,
        time = FixedTimeProvider,
        sessionDao = sessionDao,
        dailyProgressDao = dailyProgressDao,
        transaction = PassThroughTransactionScope,
        gamificationUpdater = GamificationUpdater(
            sessionDao = sessionDao,
            dailyProgressDao = dailyProgressDao,
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = achievementDao,
            gameDao = gameDao,
        ),
        derivedStateWrites = DerivedStateWriteCoordinator(),
        achievementRepository = AchievementRepository(
            steamApi = steamApi,
            achievementDao = achievementDao,
            gameAchievementSyncDao = syncDao,
            gameDao = gameDao,
            time = FixedTimeProvider,
        ),
    )

    /** A minimal [SteamApi] double: only the two endpoints manual import reaches are wired. */
    private class FakeSteamApi(
        private val ownedGames: suspend () -> OwnedGamesResponse = { error("getOwnedGames not used") },
        private val playerAchievements: suspend () -> PlayerAchievementsResponse =
            { error("getPlayerAchievements not used") },
    ) : SteamApi {
        override suspend fun getWishlist(
            steamId: String,
            scope: SyncRunRecorder.RunScope?,
        ): WishlistResponse = error("the wishlist is not part of this test")

        override suspend fun getStoreItems(
            inputJson: String,
            scope: SyncRunRecorder.RunScope?,
        ): StoreItemsResponse = error("store items are not part of this test")

        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
            scope: SyncRunRecorder.RunScope?,
        ): OwnedGamesResponse = ownedGames()

        override suspend fun getRecentlyPlayedGames(
            key: String,
            steamId: String,
            count: Int,
            scope: SyncRunRecorder.RunScope?,
        ): RecentlyPlayedGamesResponse = throw UnsupportedOperationException()

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerAchievementsResponse = playerAchievements()

        override suspend fun getSteamLevel(
            key: String,
            steamId: String,
            scope: SyncRunRecorder.RunScope?,
        ): SteamLevelResponse = error("not used")

        override suspend fun getPlayerSummaries(
            key: String,
            steamIds: String,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerSummariesResponse = error("not used")

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GlobalAchievementPercentagesResponse = error("not used")

        override suspend fun getSchemaForGame(
            key: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GameSchemaResponse = error("not used")

        override suspend fun resolveVanityUrl(key: String, vanityUrl: String): ResolveVanityResponse =
            error("not used")

        override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse =
            error("not used")
    }

    /** A minimal [SteamStoreApi] double: the one endpoint manual import reaches. */
    private class FakeSteamStoreApi(
        private val respond: suspend (Long) -> Response<Map<String, StoreAppDetails>> =
            { error("appDetails not used") },
    ) : SteamStoreApi {
        override suspend fun appDetailsPrices(
            appIds: String,
            countryCode: String?,
            filters: String,
        ): Response<Map<String, StorePriceEnvelope>> = error("prices are not part of this test")

        override suspend fun appDetails(appId: Long, language: String): Response<Map<String, StoreAppDetails>> =
            respond(appId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> noOpProxy(type: Class<T>): T = Proxy.newProxyInstance(
        type.classLoader,
        arrayOf(type),
    ) { _, _, _ -> null } as T

    /** In-memory [AchievementDao] so [AchievementRepository.refreshOne] can actually persist. */
    private class FakeAchievementDao : AchievementDao {
        private val store = mutableListOf<Achievement>()

        override suspend fun upsertAll(achievements: List<Achievement>) {
            achievements.forEach { incoming ->
                val index = store.indexOfFirst { it.appId == incoming.appId && it.apiName == incoming.apiName }
                if (index >= 0) store[index] = incoming else store += incoming
            }
        }

        override fun observeForGame(appId: Long): Flow<List<Achievement>> = flowOf(emptyList())
        override suspend fun getForGame(appId: Long): List<Achievement> = store.filter { it.appId == appId }
        override suspend fun deleteAll() = store.clear()
        override suspend fun getOne(appId: Long, apiName: String): Achievement? =
            store.firstOrNull { it.appId == appId && it.apiName == apiName }

        override fun observeCounts(): Flow<List<AchievementCounts>> = flowOf(emptyList())
        override suspend fun getAllUnlocked(): List<Achievement> = store.filter { it.unlocked }
        override fun observeUnlockedRarity(): Flow<List<AchievementRarity>> = flowOf(emptyList())
        override fun observeUnlockedSince(cutoff: Long): Flow<List<AchievementUnlock>> = flowOf(emptyList())
    }

    /** In-memory [GameAchievementSyncDao] so [AchievementRepository.refreshOne] can actually persist. */
    private class FakeGameAchievementSyncDao : GameAchievementSyncDao {
        private val store = mutableMapOf<Long, GameAchievementSync>()

        override suspend fun get(appId: Long): GameAchievementSync? = store[appId]
        override suspend fun getAll(appIds: Set<Long>): List<GameAchievementSync> =
            store.values.filter { it.appId in appIds }

        override fun observeAll(): Flow<List<GameAchievementSync>> = flowOf(store.values.toList())
        override suspend fun upsert(row: GameAchievementSync) {
            store[row.appId] = row
        }

        override suspend fun upsertAll(rows: List<GameAchievementSync>) {
            rows.forEach { store[it.appId] = it }
        }

        override suspend fun deleteAll() = store.clear()
        override suspend fun delete(appId: Long) {
            store.remove(appId)
        }
    }

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 2_000L
        override fun zone() = ZoneOffset.UTC
        override fun today() = LocalDate.of(2026, 8, 24)
    }

    private class FakeExcludedSharedGameDao(vararg row: ExcludedSharedGame) : ExcludedSharedGameDao {
        private val rows = row.associateByTo(linkedMapOf()) { it.appId }

        override suspend fun upsert(row: ExcludedSharedGame) {
            rows[row.appId] = row
        }

        override suspend fun getAll(): List<ExcludedSharedGame> = rows.values.toList()

        override fun observeAll() = kotlinx.coroutines.flow.flowOf(rows.values.toList())

        override suspend fun isExcluded(appId: Long): Boolean = rows.containsKey(appId)

        override suspend fun delete(appId: Long) {
            rows.remove(appId)
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
