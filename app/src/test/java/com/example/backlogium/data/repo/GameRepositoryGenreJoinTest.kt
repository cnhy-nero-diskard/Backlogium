package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreAppDetails
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * The shared library join, exercised with **every network path broken**: the Store data source and
 * the Steam API both fail on contact. Genres a previous sync cached must still reach every
 * consumer of `library`/`goalGames`/`backlog`, because the app is offline-first — a genre that only
 * appears while connected would be worse than no genre at all.
 *
 * The join must also be *additive*. Genre data is metadata about a game, not a precondition for
 * showing it: a game with no cache row, or with a row this app can no longer parse, appears
 * exactly as it did before genres existed.
 */
@RunWith(RobolectricTestRunner::class)
class GameRepositoryGenreJoinTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var repository: GameRepository

    private val action = GameGenre("1", "Action")
    private val indie = GameGenre("23", "Indie")
    private val casual = GameGenre("4", "Casual")

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = GameRepository(
            gameDao = db.gameDao(),
            hltbRepository = HltbRepository(
                dataSource = OfflineHltb,
                hltbDataDao = db.hltbDataDao(),
                json = Json,
                time = OfflineTime,
            ),
            gameGenreRepository = GameGenreRepository(
                cacheDao = db.gameGenreCacheDao(),
                store = SteamStoreGenreDataSource(OfflineStore),
                time = OfflineTime,
            ),
            steamApi = OfflineSteamApi,
            sessionRepository = SessionRepository(db.sessionDao()),
            time = OfflineTime,
        )
    }

    @After fun tearDown() = db.close()

    @Test
    fun cachedGenresReachEverySectionOffline_inStoreOrder() = runTest {
        db.gameDao().upsertAll(listOf(game(1, isGoal = true), game(2)))
        // Deliberately not alphabetical: the Store's own order is the one that must survive.
        cache(1, listOf(indie, action, casual))
        cache(2, listOf(action))

        assertEquals(
            mapOf(1L to listOf(indie, action, casual), 2L to listOf(action)),
            repository.library.first().associate { it.appId to it.genres },
        )
        assertEquals(listOf(indie, action, casual), repository.goalGames.first().single().genres)
        assertEquals(listOf(action), repository.backlog.first().single().genres)
    }

    @Test
    fun aGameWithNoCacheRowIsStillListed_withNoGenres() = runTest {
        db.gameDao().upsertAll(listOf(game(1), game(2)))
        cache(1, listOf(action))

        val byAppId = repository.library.first().associateBy { it.appId }

        assertEquals(setOf(1L, 2L), byAppId.keys)
        assertEquals(listOf(action), byAppId.getValue(1L).genres)
        assertEquals(emptyList<GameGenre>(), byAppId.getValue(2L).genres)
        // Everything else about the un-enriched game is unchanged.
        assertEquals("Game 2", byAppId.getValue(2L).name)
    }

    @Test
    fun anUnparseableCacheRowHidesNeitherTheGameNorItsOtherFacts() = runTest {
        db.gameDao().upsertAll(listOf(game(1), game(2)))
        db.gameGenreCacheDao().upsert(GameGenreCache(1, "{not-a-list}", checkedAt = 1_000))
        // A well-formed list whose entries are junk degrades the same way: no genres, not no game.
        db.gameGenreCacheDao().upsert(GameGenreCache(2, "[{\"id\":\"\",\"label\":\"\"}]", checkedAt = 1_000))

        val games = repository.library.first()

        assertEquals(listOf(1L, 2L), games.map { it.appId }.sorted())
        assertEquals(listOf(emptyList<GameGenre>(), emptyList()), games.map { it.genres })
        assertEquals(listOf(120, 120), games.map { it.playtimeForever })
    }

    private suspend fun cache(appId: Long, genres: List<GameGenre>) =
        db.gameGenreCacheDao().upsert(
            GameGenreCache(appId, GameGenreCodec.encode(genres), checkedAt = 1_000),
        )

    private fun game(appId: Long, isGoal: Boolean = false) = Game(
        appId = appId, name = "Game $appId", iconUrl = "", playtimeForever = 120,
        playtime2Weeks = 0, lastPlaytime = 0, isGoal = isGoal,
    )

    private object OfflineHltb : HltbDataSource {
        override suspend fun search(name: String): List<HltbCandidate> = throw IOException("offline")
    }

    private object OfflineStore : SteamStoreApi {
        override suspend fun appDetails(
            appId: Long,
            language: String,
        ): Response<Map<String, StoreAppDetails>> = throw IOException("offline")
    }

    private object OfflineTime : TimeProvider {
        override fun nowMillis(): Long = 2_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-08")
    }

    /** Nothing in the join may reach the network; every call is a test failure if made. */
    private object OfflineSteamApi : SteamApi {
        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
            scope: SyncRunRecorder.RunScope?,
        ): OwnedGamesResponse = error("the library join must not call Steam")

        override suspend fun getRecentlyPlayedGames(
            key: String,
            steamId: String,
            count: Int,
            scope: SyncRunRecorder.RunScope?,
        ): RecentlyPlayedGamesResponse = error("the library join must not call Steam")

        override suspend fun getSteamLevel(
            key: String,
            steamId: String,
            scope: SyncRunRecorder.RunScope?,
        ): SteamLevelResponse = error("the library join must not call Steam")

        override suspend fun getPlayerSummaries(
            key: String,
            steamIds: String,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerSummariesResponse = error("the library join must not call Steam")

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerAchievementsResponse = error("the library join must not call Steam")

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GlobalAchievementPercentagesResponse = error("the library join must not call Steam")

        override suspend fun getSchemaForGame(
            key: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): GameSchemaResponse = error("the library join must not call Steam")

        override suspend fun resolveVanityUrl(
            key: String,
            vanityUrl: String,
        ): ResolveVanityResponse = error("the library join must not call Steam")

        override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse =
            error("the library join must not call Steam")
    }
}
