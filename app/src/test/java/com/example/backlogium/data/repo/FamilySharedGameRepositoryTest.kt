package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.ExcludedSharedGameDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.ExcludedSharedGame
import com.example.backlogium.data.local.entity.Game
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
import com.example.backlogium.domain.FakeGameDao
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GamificationUpdater
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
    ) = FamilySharedGameRepository(
        gameDao = gameDao,
        excludedDao = excludedDao,
        profileDao = noOpProxy(PlayerProfileDao::class.java),
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
        sessionDao = noOpProxy(SessionDao::class.java),
        dailyProgressDao = noOpProxy(DailyProgressDao::class.java),
        transaction = PassThroughTransactionScope,
        gamificationUpdater = GamificationUpdater(
            sessionDao = noOpProxy(SessionDao::class.java),
            dailyProgressDao = noOpProxy(DailyProgressDao::class.java),
            playerProfileDao = noOpProxy(PlayerProfileDao::class.java),
            hltbDataDao = noOpProxy(HltbDataDao::class.java),
            achievementDao = noOpProxy(AchievementDao::class.java),
            gameDao = noOpProxy(GameDao::class.java),
        ),
        derivedStateWrites = DerivedStateWriteCoordinator(),
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
