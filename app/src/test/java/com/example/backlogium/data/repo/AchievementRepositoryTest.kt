package com.example.backlogium.data.repo

import com.example.backlogium.data.achievement.AchievementFreshness
import com.example.backlogium.data.local.dao.AchievementCounts
import com.example.backlogium.data.local.dao.AchievementDao
import com.example.backlogium.data.local.dao.AchievementRarity
import com.example.backlogium.data.local.dao.AchievementUnlock
import com.example.backlogium.data.local.dao.GameAchievementSyncDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.AvailableGameStatsDto
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GameSchemaResult
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResult
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the bounded-fetch and freshness behaviour of the tiered achievement sync: that
 * never-played games cost no requests, that the fetch really is concurrent but capped, that a
 * per-kind freshness window is honoured without a permanent re-fetch leak, and that the
 * reconciliation pass covers only the cold tier and reports progress it can be interrupted on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementRepositoryTest {

    @Test
    fun `never-played games cost no requests`() = runTest {
        val api = FakeSteamApi()
        val repo = repository(api)

        val selection = repo.syncLibraryGames(
            apiKey = KEY,
            steamId = STEAM_ID,
            ownedGames = listOf(ownedGame(1, forever = 0, weeks = 0), ownedGame(2, forever = 0, weeks = 0)),
            playtimeDeltaByAppId = emptyMap(),
        )

        assertEquals(listOf(1L, 2L), selection.never)
        assertEquals(emptyList<Long>(), selection.inlineSelected)
        assertEquals(emptyList<Long>(), api.playerAchievementCalls)
    }

    @Test
    fun `hot and warm games are fetched concurrently but capped`() = runTest {
        val api = FakeSteamApi(gate = true)
        val repo = repository(api)
        // 12 warm games against a cap of 5 — enough to prove both that fetches overlap and that
        // the overlap is bounded, which a sequential loop with a semaphore would fail.
        val games = (1L..12L).map { ownedGame(it, forever = 100, weeks = 10) }

        val job = launch {
            repo.syncLibraryGames(KEY, STEAM_ID, games, emptyMap())
        }
        runCurrent()

        assertEquals(AchievementRepository.MAX_CONCURRENT_FETCHES, api.inFlight.get())
        api.release()
        job.join()

        assertEquals(12, api.playerAchievementCalls.size)
        assertEquals(
            "concurrency should never exceed the semaphore's permits",
            AchievementRepository.MAX_CONCURRENT_FETCHES,
            api.maxInFlight.get(),
        )
    }

    @Test
    fun `a fresh schema is not re-fetched`() = runTest {
        val api = FakeSteamApi()
        val syncDao = FakeGameAchievementSyncDao()
        // Fetched "now", so well inside the 30-day window.
        syncDao.upsert(syncRow(appId = 1, playerStateFetchedAt = NOW, schemaFetchedAt = NOW))
        val repo = repository(api, syncDao = syncDao)

        repo.syncLibraryGames(KEY, STEAM_ID, listOf(ownedGame(1, forever = 100, weeks = 10)), emptyMap())

        assertEquals(listOf(1L), api.playerAchievementCalls)
        assertEquals("schema is still fresh", emptyList<Long>(), api.schemaCalls)
        assertEquals(NOW, syncDao.get(1)?.schemaFetchedAt)
    }

    @Test
    fun `a stale schema is re-fetched and its timestamp advances`() = runTest {
        val api = FakeSteamApi()
        val syncDao = FakeGameAchievementSyncDao()
        val stale = NOW - AchievementRepository.SCHEMA_WINDOW_MILLIS - 1
        syncDao.upsert(syncRow(appId = 1, playerStateFetchedAt = stale, schemaFetchedAt = stale))
        val repo = repository(api, syncDao = syncDao)

        repo.syncLibraryGames(KEY, STEAM_ID, listOf(ownedGame(1, forever = 100, weeks = 10)), emptyMap())

        assertEquals(listOf(1L), api.schemaCalls)
        assertEquals(NOW, syncDao.get(1)?.schemaFetchedAt)
    }

    /**
     * The regression that motivated separating "fetch was skipped" from "fetch returned nothing":
     * a game with no schema at all used to leave `schemaFetchedAt` untouched, so every later sync
     * re-requested a schema that would always come back empty.
     */
    @Test
    fun `an empty schema response still counts as fetched`() = runTest {
        val api = FakeSteamApi(schemaAchievements = emptyList())
        val syncDao = FakeGameAchievementSyncDao()
        val repo = repository(api, syncDao = syncDao)

        repo.syncLibraryGames(KEY, STEAM_ID, listOf(ownedGame(1, forever = 100, weeks = 10)), emptyMap())

        assertEquals(listOf(1L), api.schemaCalls)
        assertEquals("an empty-but-successful schema fetch is still a fetch", NOW, syncDao.get(1)?.schemaFetchedAt)
    }

    @Test
    fun `a failed schema fetch does not advance the timestamp`() = runTest {
        val api = FakeSteamApi(failSchema = true)
        val syncDao = FakeGameAchievementSyncDao()
        val repo = repository(api, syncDao = syncDao)

        repo.syncLibraryGames(KEY, STEAM_ID, listOf(ownedGame(1, forever = 100, weeks = 10)), emptyMap())

        assertEquals(listOf(1L), api.schemaCalls)
        assertNull("a real failure must be retried, not cached as fetched", syncDao.get(1)?.schemaFetchedAt)
    }

    @Test
    fun `one failing game does not abort the batch`() = runTest {
        val api = FakeSteamApi(failPlayerAchievementsFor = setOf(2L))
        val repo = repository(api)
        val games = (1L..3L).map { ownedGame(it, forever = 100, weeks = 10) }

        repo.syncLibraryGames(KEY, STEAM_ID, games, emptyMap())

        assertEquals(setOf(1L, 2L, 3L), api.playerAchievementCalls.toSet())
    }

    @Test
    fun `reconciliation covers only the cold tier, oldest first`() = runTest {
        val api = FakeSteamApi()
        val gameDao = FakeGameDao(
            game(1, forever = 100, weeks = 0),  // cold, fetched most recently
            game(2, forever = 200, weeks = 0),  // cold, fetched longest ago
            game(3, forever = 300, weeks = 5),  // warm — not this pass's job
            game(4, forever = 0, weeks = 0),    // never — no request, ever
        )
        val syncDao = FakeGameAchievementSyncDao()
        syncDao.upsert(syncRow(appId = 1, playerStateFetchedAt = 900))
        syncDao.upsert(syncRow(appId = 2, playerStateFetchedAt = 100))
        val repo = repository(api, gameDao = gameDao, syncDao = syncDao)

        val result = repo.reconcileLibraryGames(KEY, STEAM_ID)

        assertEquals(listOf(2L, 1L), api.playerAchievementCalls)
        assertEquals(2, result.total)
        assertEquals(2, result.refreshed)
    }

    @Test
    fun `reconciliation reports its total before fetching anything`() = runTest {
        val api = FakeSteamApi(gate = true)
        val gameDao = FakeGameDao(game(1, forever = 100, weeks = 0), game(2, forever = 200, weeks = 0))
        val repo = repository(api, gameDao = gameDao)
        val progress = mutableListOf<Pair<Int, Int>>()

        val job = launch {
            repo.reconcileLibraryGames(KEY, STEAM_ID) { refreshed, total -> progress += refreshed to total }
        }
        runCurrent()

        // The total is known up front, so a pass cancelled before its first game still reports it.
        assertEquals(0 to 2, progress.first())
        api.release()
        job.join()
        assertEquals(2 to 2, progress.last())
    }

    @Test
    fun `reconciliation with an empty library does no work`() = runTest {
        val api = FakeSteamApi()
        val repo = repository(api, gameDao = FakeGameDao())

        val result = repo.reconcileLibraryGames(KEY, STEAM_ID)

        assertEquals(0, result.total)
        assertEquals(emptyList<Long>(), api.playerAchievementCalls)
    }

    @Test
    fun `cancelling a sync propagates rather than being swallowed`() = runTest {
        val api = FakeSteamApi(gate = true)
        val repo = repository(api)
        val games = (1L..3L).map { ownedGame(it, forever = 100, weeks = 10) }

        val job = launch {
            repo.syncLibraryGames(KEY, STEAM_ID, games, emptyMap())
        }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue("the batch must not outlive its cancelled caller", job.isCancelled)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun repository(
        api: FakeSteamApi,
        gameDao: GameDao = FakeGameDao(),
        syncDao: GameAchievementSyncDao = FakeGameAchievementSyncDao(),
    ) = AchievementRepository(
        steamApi = api,
        achievementDao = FakeAchievementDao(),
        gameAchievementSyncDao = syncDao,
        gameDao = gameDao,
        time = FixedTimeProvider(NOW),
    )

    private fun ownedGame(appId: Long, forever: Long, weeks: Long) =
        AchievementFreshness.OwnedGame(appId = appId, playtimeForever = forever, playtime2Weeks = weeks)

    private fun game(appId: Long, forever: Int, weeks: Int) = Game(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = forever,
        playtime2Weeks = weeks,
        lastPlaytime = forever,
    )

    private fun syncRow(appId: Long, playerStateFetchedAt: Long?, schemaFetchedAt: Long? = null) =
        GameAchievementSync(
            appId = appId,
            playerStateFetchedAt = playerStateFetchedAt,
            schemaFetchedAt = schemaFetchedAt,
            hasAchievements = true,
            checkedAt = playerStateFetchedAt ?: 0L,
        )

    private companion object {
        const val KEY = "test-key"
        const val STEAM_ID = "76561190000000000"
        const val NOW = 10_000_000_000L
    }

    /**
     * Records every achievement-related call and, when [gate] is set, parks each fetch until
     * [release] so in-flight concurrency can be observed rather than inferred.
     */
    private class FakeSteamApi(
        private val gate: Boolean = false,
        private val schemaAchievements: List<com.example.backlogium.data.remote.dto.AchievementSchemaDto>? = null,
        private val failSchema: Boolean = false,
        private val failPlayerAchievementsFor: Set<Long> = emptySet(),
    ) : SteamApi {
        private val gateSignal = CompletableDeferred<Unit>()
        private val calls = mutableListOf<Long>()
        private val schemas = mutableListOf<Long>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        val playerAchievementCalls: List<Long> get() = calls.toList()
        val schemaCalls: List<Long> get() = schemas.toList()

        fun release() = gateSignal.complete(Unit)

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
        ): PlayerAchievementsResponse {
            calls += appId
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                if (gate) gateSignal.await()
                if (appId in failPlayerAchievementsFor) throw IOException("per-game failure")
                return PlayerAchievementsResponse(
                    PlayerAchievementsResult(
                        success = true,
                        achievements = listOf(PlayerAchievementDto(apiName = "ACH_1", achieved = 1, unlocktime = 5)),
                    ),
                )
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override suspend fun getSchemaForGame(key: String, appId: Long): GameSchemaResponse {
            schemas += appId
            if (failSchema) throw IOException("schema unavailable")
            return GameSchemaResponse(
                GameSchemaResult(
                    availableGameStats = AvailableGameStatsDto(
                        achievements = schemaAchievements
                            ?: listOf(
                                com.example.backlogium.data.remote.dto.AchievementSchemaDto(
                                    name = "ACH_1",
                                    displayName = "First",
                                ),
                            ),
                    ),
                ),
            )
        }

        override suspend fun getGlobalAchievementPercentages(gameId: Long) =
            GlobalAchievementPercentagesResponse()

        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
        ): OwnedGamesResponse = error("not used")

        override suspend fun getSteamLevel(key: String, steamId: String): SteamLevelResponse =
            error("not used")

        override suspend fun getPlayerSummaries(key: String, steamIds: String): PlayerSummariesResponse =
            error("not used")

        override suspend fun resolveVanityUrl(key: String, vanityUrl: String): ResolveVanityResponse =
            error("not used")

        override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse =
            error("not used")
    }

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

        override suspend fun delete(appId: Long) {
            store.remove(appId)
        }
    }

    private class FakeAchievementDao : AchievementDao {
        private val store = mutableListOf<Achievement>()

        override suspend fun upsertAll(achievements: List<Achievement>) {
            achievements.forEach { incoming ->
                val index = store.indexOfFirst {
                    it.appId == incoming.appId && it.apiName == incoming.apiName
                }
                if (index >= 0) store[index] = incoming else store += incoming
            }
        }

        override fun observeForGame(appId: Long): Flow<List<Achievement>> = flowOf(emptyList())
        override suspend fun getForGame(appId: Long): List<Achievement> = store.filter { it.appId == appId }
        override suspend fun getOne(appId: Long, apiName: String): Achievement? =
            store.firstOrNull { it.appId == appId && it.apiName == apiName }

        override fun observeCounts(): Flow<List<AchievementCounts>> = flowOf(emptyList())
        override suspend fun getAllUnlocked(): List<Achievement> = store.filter { it.unlocked }
        override fun observeUnlockedRarity(): Flow<List<AchievementRarity>> = flowOf(emptyList())
        override fun observeUnlockedSince(cutoff: Long): Flow<List<AchievementUnlock>> = flowOf(emptyList())
    }

    private class FakeGameDao(private vararg val games: Game) : GameDao {
        override suspend fun upsertAll(games: List<Game>) = error("not used")
        override suspend fun upsert(game: Game) = error("not used")
        override fun observeLibrary(): Flow<List<Game>> = flowOf(games.toList())
        override fun observeGoalGames(): Flow<List<Game>> = error("not used")
        override fun observeBacklog(): Flow<List<Game>> = error("not used")
        override suspend fun allAppIds(): List<Long> = games.map { it.appId }
        override suspend fun getAll(): List<Game> = games.toList()
        override suspend fun getById(appId: Long): Game? = games.firstOrNull { it.appId == appId }
        override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) = error("not used")
        override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) = error("not used")
        override suspend fun count(): Int = games.size
        override suspend fun setBackfillMinutes(appId: Long, minutes: Int) = error("not used")
    }

    private class FixedTimeProvider(private val now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-11")
    }
}
