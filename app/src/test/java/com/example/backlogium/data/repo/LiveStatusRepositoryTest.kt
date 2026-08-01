package com.example.backlogium.data.repo

import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.PlayerSummaryDto
import com.example.backlogium.data.remote.dto.PlayerSummariesResult
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers the behavior this rework adds on top of the plain poll-and-emit: that presence
 * transitions (none -> in game -> same game -> different game -> not in game) drive the
 * persisted session-start writes correctly, that a failed fetch retains the last emitted value
 * rather than clearing it, and that stopping the poll clears both in-memory and persisted state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveStatusRepositoryTest {

    @Test
    fun presenceTransitions_driveStartTimeWritesCorrectly() = runTest {
        val steamApi = FakeSteamApi()
        val settings = FakeSettingsRepository()
        val time = FakeTimeProvider(1_000L)
        val repo = repository(steamApi = steamApi, settings = settings, time = time, scope = this)

        // none -> in game: a fresh session starts at "now".
        steamApi.setInGame(gameId = 10L, name = "Portal")
        var status = repo.checkNow()
        assertEquals(1_000L, status.sessionStartedAt)
        assertEquals(LiveSessionState(appId = 10L, startedAt = 1_000L), settings.session.value)

        // same game observed again later: the original start time is kept, not bumped.
        time.now = 31_000L
        status = repo.checkNow()
        assertEquals(1_000L, status.sessionStartedAt)
        assertEquals(LiveSessionState(appId = 10L, startedAt = 1_000L), settings.session.value)

        // different game: the session resets to the new game at the new "now".
        time.now = 61_000L
        steamApi.setInGame(gameId = 20L, name = "Hades")
        status = repo.checkNow()
        assertEquals(20L, (status.nowPlaying as NowPlaying.InGame).gameId)
        assertEquals(61_000L, status.sessionStartedAt)
        assertEquals(LiveSessionState(appId = 20L, startedAt = 61_000L), settings.session.value)

        // not in game: the session clears.
        time.now = 91_000L
        steamApi.setNotInGame()
        status = repo.checkNow()
        assertEquals(NowPlaying.NotPlaying, status.nowPlaying)
        assertNull(status.sessionStartedAt)
        assertEquals(LiveSessionState(), settings.session.value)
    }

    @Test
    fun failedFetch_retainsLastEmittedValue_andDoesNotDisturbTheSession() = runTest {
        val steamApi = FakeSteamApi()
        val settings = FakeSettingsRepository()
        val time = FakeTimeProvider(1_000L)
        val repo = repository(steamApi = steamApi, settings = settings, time = time, scope = this)

        steamApi.setInGame(gameId = 10L, name = "Portal")
        val first = repo.checkNow()

        time.now = 31_000L
        steamApi.throwOnNextCall = true
        val second = repo.checkNow()

        // Same game, same session start — a transient error didn't clear or reset anything.
        assertEquals(first.nowPlaying, second.nowPlaying)
        assertEquals(first.sessionStartedAt, second.sessionStartedAt)
        assertEquals(LiveSessionState(appId = 10L, startedAt = 1_000L), settings.session.value)
    }

    @Test
    fun stopPolling_clearsInMemoryAndPersistedSession() = runTest {
        val steamApi = FakeSteamApi()
        val settings = FakeSettingsRepository()
        val repo = repository(
            steamApi = steamApi,
            settings = settings,
            time = FakeTimeProvider(1_000L),
            scope = this,
        )

        steamApi.setInGame(gameId = 10L, name = "Portal")
        repo.checkNow()
        assertEquals(10L, settings.session.value.appId)

        repo.stopPolling()
        // The write is launched on the application scope, not awaited by stopPolling() itself.
        advanceUntilIdle()
        assertEquals(LiveStatus(), repo.liveStatus.value)
        assertEquals(LiveSessionState(), settings.session.value)

        // Safe to call again once already stopped.
        repo.stopPolling()
    }

    @Test
    fun startPolling_isIdempotent() = runTest {
        val steamApi = FakeSteamApi()
        val repo = repository(
            steamApi = steamApi,
            settings = FakeSettingsRepository(),
            time = FakeTimeProvider(1_000L),
            scope = this,
        )

        repo.startPolling()
        assertTrue(repo.isPolling)
        repo.startPolling() // second call while already running is a no-op

        // Let the loop run its first (zero-delay) checkNow() and suspend on delay(POLL_INTERVAL_MS).
        // Not advanceUntilIdle(): the loop reschedules delay() forever, so "idle" never arrives.
        runCurrent()
        assertEquals(1, steamApi.callCount)

        // Only one loop running means exactly one more fetch per elapsed poll interval, not two.
        advanceTimeBy(LiveStatusRepository.POLL_INTERVAL_MS + 1)
        assertEquals(2, steamApi.callCount)

        repo.stopPolling()
        assertFalse(repo.isPolling)
    }

    private fun repository(
        steamApi: FakeSteamApi,
        settings: FakeSettingsRepository,
        time: FakeTimeProvider,
        scope: CoroutineScope,
    ) = LiveStatusRepository(
        steamApi = steamApi,
        gameDao = FakeGameDao(),
        profileDao = FakePlayerProfileDao(),
        credentials = FakeCredentialsProvider(),
        settings = settings,
        time = time,
        scope = scope,
    )

    private class FakeCredentialsProvider : CredentialsProvider {
        override suspend fun currentCredentials() =
            CredentialsState.Configured(apiKey = "key", steamId = "1")
    }

    /** Only [getById] is exercised (icon lookup); every other member is unused by this test. */
    private class FakeGameDao : GameDao {
        override suspend fun upsertAll(games: List<Game>) = error("not used")
        override suspend fun upsert(game: Game) = error("not used")
        override fun observeLibrary(): Flow<List<Game>> = error("not used")
        override fun observeGoalGames(): Flow<List<Game>> = error("not used")
        override fun observeBacklog(): Flow<List<Game>> = error("not used")
        override suspend fun allAppIds(): List<Long> = error("not used")
        override suspend fun getAll(): List<Game> = error("not used")
        override suspend fun getById(appId: Long): Game? = null
        override suspend fun setGoal(appId: Long, isGoal: Boolean, targetMinutes: Int?) = error("not used")
        override suspend fun setGoalFlag(appId: Long, isGoal: Boolean) = error("not used")
        override suspend fun count(): Int = error("not used")
        override suspend fun setBackfillMinutes(appId: Long, minutes: Int) = error("not used")
    }

    /** In-memory single-row profile, matching the real DAO's "id = 0 singleton" contract. */
    private class FakePlayerProfileDao : PlayerProfileDao {
        private var stored: PlayerProfile? = null
        override suspend fun upsert(profile: PlayerProfile) {
            stored = profile
        }
        override fun observe(): Flow<PlayerProfile?> = error("not used")
        override suspend fun get(): PlayerProfile? = stored
    }

    /** Configurable player-summary responses; [throwOnNextCall] simulates a transient failure. */
    private class FakeSteamApi : SteamApi {
        private var players: List<PlayerSummaryDto> = emptyList()
        var throwOnNextCall = false
        var callCount = 0
            private set

        fun setInGame(gameId: Long, name: String) {
            players = listOf(PlayerSummaryDto(gameId = gameId.toString(), gameExtraInfo = name))
        }

        fun setNotInGame() {
            players = listOf(PlayerSummaryDto())
        }

        override suspend fun getPlayerSummaries(key: String, steamIds: String): PlayerSummariesResponse {
            callCount++
            if (throwOnNextCall) {
                throwOnNextCall = false
                throw java.io.IOException("transient failure")
            }
            return PlayerSummariesResponse(PlayerSummariesResult(players))
        }

        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
        ): OwnedGamesResponse = error("not used")

        override suspend fun getSteamLevel(key: String, steamId: String): SteamLevelResponse =
            error("not used")

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
        ): PlayerAchievementsResponse = error("not used")

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
        ): GlobalAchievementPercentagesResponse = error("not used")

        override suspend fun getSchemaForGame(key: String, appId: Long): GameSchemaResponse =
            error("not used")

        override suspend fun resolveVanityUrl(key: String, vanityUrl: String): ResolveVanityResponse =
            error("not used")

        override suspend fun getNumberOfCurrentPlayers(appId: Long): CurrentPlayersResponse =
            error("not used")
    }

    /** In-memory stand-in for the DataStore-backed implementation (only [session] is exercised). */
    private class FakeSettingsRepository : SettingsRepository {
        val session = MutableStateFlow(LiveSessionState())
        override val liveSession: Flow<LiveSessionState> = session
        override suspend fun setLiveSession(appId: Long?, startedAt: Long) {
            session.value = LiveSessionState(appId, startedAt)
        }
        override suspend fun clearLiveSession() {
            session.value = LiveSessionState()
        }

        override val ruleConfig: Flow<RuleConfig> = MutableStateFlow(RuleConfig())
        override suspend fun setRuleConfig(config: RuleConfig) = error("not used")
        override val librarySort: Flow<LibrarySortPrefs> = MutableStateFlow(LibrarySortPrefs())
        override suspend fun setFocusSort(key: LibrarySortKey) = error("not used")
        override suspend fun setLibrarySort(key: LibrarySortKey) = error("not used")
        override val autoSnapshotSettings: Flow<AutoSnapshotSettings> =
            MutableStateFlow(AutoSnapshotSettings())
        override suspend fun setAutoSnapshotEnabled(enabled: Boolean) = error("not used")
        override suspend fun setSnapshotRetentionCount(count: Int) = error("not used")
        override suspend fun setSnapshotIntervalHours(hours: Int) = error("not used")
    }

    private class FakeTimeProvider(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-27")
    }
}
