package com.example.backlogium.data.repo

import com.example.backlogium.data.achievement.AchievementFreshness
import com.example.backlogium.data.diagnostics.SyncRunRecorder
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Serial fetching is a deliberate choice (see `fetchGames`), so it is asserted rather than left
     * to drift: the Steam client has no retry, backoff, or 429 handling, and after tiering there is
     * no pass large enough for concurrency to speed up. A future change that parallelises this
     * should have to update this test and say why.
     */
    @Test
    fun `fetches are issued one at a time`() = runTest {
        val api = FakeSteamApi(gate = true)
        val repo = repository(api)
        val games = (1L..12L).map { ownedGame(it, forever = 100, weeks = 10) }

        val job = launch {
            repo.syncLibraryGames(KEY, STEAM_ID, games, emptyMap())
        }
        runCurrent()

        assertEquals("only the first game should have started", 1, api.playerAchievementCalls.size)
        api.release()
        job.join()

        assertEquals(12, api.playerAchievementCalls.size)
        assertEquals("no request may overlap another", 1, api.maxInFlight.get())
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

    /**
     * A private profile / no-stats response is a successful HTTP call that resolves to "nothing to
     * write" — `syncGame` used to return normally either way, so `fetchGames` counted it as a
     * refresh regardless. A reconciliation pass over an all-private-profile library would then
     * report "N/N refreshed" while having updated zero `GameAchievementSync` rows.
     */
    @Test
    fun `a private-profile response is not counted as a refresh`() = runTest {
        val api = FakeSteamApi(noStatsFor = setOf(1L, 2L))
        val gameDao = FakeGameDao(game(1, forever = 100, weeks = 0), game(2, forever = 200, weeks = 0))
        val syncDao = FakeGameAchievementSyncDao()
        val repo = repository(api, gameDao = gameDao, syncDao = syncDao)

        val result = repo.reconcileLibraryGames(KEY, STEAM_ID)

        assertEquals("both were attempted", 2, result.total)
        assertEquals("neither actually updated anything", 0, result.refreshed)
        assertEquals("no metadata row is written for a game Steam reported nothing for", null, syncDao.get(1))
        assertEquals(null, syncDao.get(2))
    }

    /**
     * The cost claim the whole change rests on, pinned as arithmetic rather than left to an
     * on-device stopwatch: in steady state (every game already has sync metadata, so the
     * missing-data override is empty) a sync costs requests proportional to games *played*, not
     * games *owned*. The old whole-library sweep cost ~3 requests per owned game.
     */
    @Test
    fun `a steady-state sync costs two orders of magnitude fewer requests than a full sweep`() = runTest {
        val api = FakeSteamApi()
        val syncDao = FakeGameAchievementSyncDao()
        val library = (1L..500L).map { appId ->
            // Only the first three have been played recently; the rest are cold.
            if (appId <= 3L) ownedGame(appId, forever = 100, weeks = 10) else ownedGame(appId, forever = 100, weeks = 0)
        }
        // Steady state: every game has been reconciled at some point, so nothing is missing data.
        library.forEach { syncDao.upsert(syncRow(it.appId, playerStateFetchedAt = NOW, schemaFetchedAt = NOW)) }
        val repo = repository(api, syncDao = syncDao)

        repo.syncLibraryGames(KEY, STEAM_ID, library, emptyMap())

        assertEquals(listOf(1L, 2L, 3L), api.playerAchievementCalls)
        assertEquals("a fresh schema costs nothing", emptyList<Long>(), api.schemaCalls)
        // 3 player-state + 3 global-percentage requests, against ~1500 for a 500-game sweep.
        val tiered = api.totalRequests()
        val fullSweep = library.size * 3
        assertTrue(
            "expected a >=100x drop, got $tiered vs $fullSweep",
            fullSweep / tiered >= 100,
        )
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
    fun `reconciliation commits a completed refresh before cancellation`() = runTest {
        val api = FakeSteamApi()
        val gameDao = FakeGameDao(
            game(1, forever = 100, weeks = 0),
            game(2, forever = 200, weeks = 0),
        )
        val syncDao = FakeGameAchievementSyncDao()
        val repo = repository(api, gameDao = gameDao, syncDao = syncDao)
        var cancelled = false

        try {
            repo.fetchReconciliationGames(
                apiKey = KEY,
                steamId = STEAM_ID,
                onRefresh = { refresh ->
                    repo.applyRefreshes(listOf(refresh))
                    throw CancellationException("constraints changed")
                },
            )
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue("the cancellation must reach the worker", cancelled)
        assertEquals(
            "completed work is durable before the sweep propagates cancellation",
            NOW,
            syncDao.get(1)?.playerStateFetchedAt,
        )
        assertNull("the next game was not reached", syncDao.get(2))
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

    @Test
    fun `full reconciliation retires absent rows and a later observation reinstates them`() = runTest {
        val old = Achievement(
            appId = 1L,
            apiName = "OLD",
            unlocked = true,
            snapshotPercent = 7.5,
            fetchedAt = 10L,
        )
        val achievementDao = FakeAchievementDao(listOf(old))
        val repo = repository(FakeSteamApi(), achievementDao = achievementDao)

        repo.applyRefreshes(
            listOf(
                AchievementRefresh(
                    appId = 1L,
                    fetchedAt = 15L,
                    achievements = listOf(PlayerAchievementDto("NEW", achieved = 1, unlocktime = 2L)),
                    globalPercentByName = emptyMap(),
                    schemaByName = emptyMap(),
                    schemaFetchedAt = null,
                ),
            ),
        )
        assertFalse("partial refresh absence must not retire a row", achievementDao.row(1L, "OLD")!!.retired)

        repo.applyRefreshes(
            listOf(
                AchievementRefresh(
                    appId = 1L,
                    fetchedAt = 20L,
                    achievements = listOf(PlayerAchievementDto("NEW", achieved = 1, unlocktime = 2L)),
                    globalPercentByName = emptyMap(),
                    schemaByName = emptyMap(),
                    schemaFetchedAt = null,
                    fullReconciliation = true,
                ),
            ),
        )
        assertTrue(achievementDao.row(1L, "OLD")!!.retired)
        assertEquals(7.5, achievementDao.row(1L, "OLD")!!.snapshotPercent!!, 0.0)

        repo.applyRefreshes(
            listOf(
                AchievementRefresh(
                    appId = 1L,
                    fetchedAt = 30L,
                    achievements = listOf(PlayerAchievementDto("NEW", achieved = 1, unlocktime = 2L)),
                    globalPercentByName = emptyMap(),
                    schemaByName = emptyMap(),
                    schemaFetchedAt = null,
                ),
            ),
        )
        assertTrue("partial refresh must not retire another absent row", achievementDao.row(1L, "OLD")!!.retired)

        repo.applyRefreshes(
            listOf(
                AchievementRefresh(
                    appId = 1L,
                    fetchedAt = 40L,
                    achievements = listOf(PlayerAchievementDto("OLD", achieved = 1, unlocktime = 2L)),
                    globalPercentByName = emptyMap(),
                    schemaByName = emptyMap(),
                    schemaFetchedAt = null,
                ),
            ),
        )
        val reinstated = achievementDao.row(1L, "OLD")!!
        assertFalse(reinstated.retired)
        assertEquals(7.5, reinstated.snapshotPercent!!, 0.0)
    }

    @Test
    fun `a late older merge cannot overwrite a newer observation`() = runTest {
        val achievementDao = FakeAchievementDao()
        val repo = repository(FakeSteamApi(), achievementDao = achievementDao)
        val newer = AchievementRefresh(
            appId = 1L,
            fetchedAt = 20L,
            achievements = listOf(PlayerAchievementDto("ACH", achieved = 1, unlocktime = 2L)),
            globalPercentByName = mapOf("ACH" to 5.0),
            schemaByName = emptyMap(),
            schemaFetchedAt = null,
        )
        val older = newer.copy(
            fetchedAt = 10L,
            achievements = listOf(PlayerAchievementDto("ACH", achieved = 0, unlocktime = 0L)),
        )

        repo.applyRefreshes(listOf(newer))
        repo.applyRefreshes(listOf(older))

        val stored = achievementDao.row(1L, "ACH")!!
        assertTrue(stored.unlocked)
        assertEquals(5.0, stored.snapshotPercent!!, 0.0)
        assertEquals(20L, stored.fetchedAt)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun repository(
        api: FakeSteamApi,
        gameDao: GameDao = FakeGameDao(),
        syncDao: GameAchievementSyncDao = FakeGameAchievementSyncDao(),
        achievementDao: FakeAchievementDao = FakeAchievementDao(),
    ) = AchievementRepository(
        steamApi = api,
        achievementDao = achievementDao,
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
        private val noStatsFor: Set<Long> = emptySet(),
    ) : SteamApi {
        private val gateSignal = CompletableDeferred<Unit>()
        private val calls = mutableListOf<Long>()
        private val schemas = mutableListOf<Long>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        private var globalPercentageCalls = 0

        val playerAchievementCalls: List<Long> get() = calls.toList()
        val schemaCalls: List<Long> get() = schemas.toList()

        /** Every Steam request this pass made, across all three achievement endpoints. */
        fun totalRequests(): Int = calls.size + schemas.size + globalPercentageCalls

        fun release() = gateSignal.complete(Unit)

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerAchievementsResponse {
            calls += appId
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                if (gate) gateSignal.await()
                if (appId in failPlayerAchievementsFor) throw IOException("per-game failure")
                if (appId in noStatsFor) {
                    return PlayerAchievementsResponse(PlayerAchievementsResult(success = false))
                }
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

        override suspend fun getSchemaForGame(
            key: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GameSchemaResponse {
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

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GlobalAchievementPercentagesResponse {
            globalPercentageCalls++
            return GlobalAchievementPercentagesResponse()
        }

        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
            scope: SyncRunRecorder.RunScope?,
        ): OwnedGamesResponse = error("not used")

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

        override suspend fun deleteAll() = store.clear()
        override suspend fun delete(appId: Long) {
            store.remove(appId)
        }
    }

    private class FakeAchievementDao(initial: List<Achievement> = emptyList()) : AchievementDao {
        private val store = initial.toMutableList()

        fun row(appId: Long, apiName: String): Achievement? =
            store.firstOrNull { it.appId == appId && it.apiName == apiName }

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
        override suspend fun deleteAll() = store.clear()
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
        override suspend fun insertSteamGameIfMissing(appId: Long, name: String, iconUrl: String, playtimeForever: Int, playtime2Weeks: Int, lastPlaytime: Int, lastSyncedAt: Long, firstSeenAt: Long?, lastPlayedAt: Long?) = error("not used")
        override suspend fun updateSteamFields(appId: Long, name: String, iconUrl: String, playtimeForever: Int, playtime2Weeks: Int, lastPlaytime: Int, lastSyncedAt: Long, lastPlayedAt: Long?, returnedToPlayAt: Long?) = error("not used")
        override fun observeLibrary(): Flow<List<Game>> = flowOf(games.toList())
        override fun observeGoalGames(): Flow<List<Game>> = error("not used")
        override fun observeBacklog(): Flow<List<Game>> = error("not used")
        override suspend fun allAppIds(): List<Long> = games.map { it.appId }
        override suspend fun getAll(): List<Game> = games.toList()
        override suspend fun getById(appId: Long): Game? = games.firstOrNull { it.appId == appId }
        override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) = error("not used")
        override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) = error("not used")
        override suspend fun count(): Int = games.size
        override suspend fun deleteAll() = error("not used")
        override suspend fun setBackfillMinutes(appId: Long, minutes: Int) = error("not used")
        override suspend fun setRecencyFromBackup(appId: Long, firstSeenAt: Long?, lastPlayedAt: Long?, returnedToPlayAt: Long?) = error("not used")
    }

    private class FixedTimeProvider(private val now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-11")
    }
}
