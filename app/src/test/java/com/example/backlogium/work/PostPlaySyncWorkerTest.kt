package com.example.backlogium.work

import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.CurrentPlayersResponse
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.OwnedGamesResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerSummariesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGameDto
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResponse
import com.example.backlogium.data.remote.dto.RecentlyPlayedGamesResult
import com.example.backlogium.data.remote.dto.ResolveVanityResponse
import com.example.backlogium.data.remote.dto.SteamLevelResponse
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.PlaySessionEnd
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.data.repo.RecentPlaytimeRepository
import com.example.backlogium.data.repo.SessionEndOutbox
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.PostPlayGenerations
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.SyncDerivedStateWriter
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drives [PostPlaySyncWorker.doWork] against a real in-memory database, the real
 * [PlaytimeObservationCommitter] the periodic poll commits through, and a real [WorkManager] for
 * the successor enqueues — only Steam itself and the generation store are faked. The point of the
 * feature is what ends up stored, and a fake commit path could not show that.
 *
 * Attempts are driven one at a time: WorkManager never runs them here (its constraints are not
 * satisfied under [WorkManagerTestInitHelper]), so each `doWork()` call is one attempt and the
 * request count is exactly the number of attempts that happened.
 */
@RunWith(RobolectricTestRunner::class)
class PostPlaySyncWorkerTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val sessionEndAt = Instant.parse("2026-07-27T20:00:00Z").toEpochMilli()
    private val lastSyncAt = Instant.parse("2026-07-27T19:45:00Z").toEpochMilli()

    private lateinit var context: Context
    private lateinit var db: BacklogiumDatabase
    private lateinit var workManager: WorkManager
    private lateinit var steamApi: FakeSteamApi
    private lateinit var credentials: FakeCredentialsProvider
    private lateinit var accountChangeMarker: AccountChangeMarkerStore
    private lateinit var generations: FakeGenerations
    private lateinit var coordinator: PostPlayGenerationCoordinator
    private lateinit var scheduler: PostPlaySyncScheduler
    private lateinit var time: FakeTimeProvider
    private lateinit var schedulerScope: CoroutineScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        credentials = FakeCredentialsProvider()
        accountChangeMarker = AccountChangeMarkerStore(context)
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        steamApi = FakeSteamApi()
        generations = FakeGenerations()
        coordinator = PostPlayGenerationCoordinator(generations)
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = PostPlaySyncScheduler(
            coordinator = coordinator,
            sessionEnds = PlaySessionEndPublisher(),
            sessionEndOutbox = object : SessionEndOutbox {
                override val pendingSessionEnds =
                    kotlinx.coroutines.flow.flowOf(emptyList<PlaySessionEnd>())
                override suspend fun recordSessionEnd(
                    sessionEnd: PlaySessionEnd,
                    nextLiveSession: LiveSessionState,
                ) = Unit
                override suspend fun acknowledgeSessionEnd(sessionEnd: PlaySessionEnd) = Unit
            },
            workEnqueuer = WorkManagerPostPlayWorkEnqueuer(context),
            scope = schedulerScope,
        )
        time = FakeTimeProvider(now = sessionEndAt + 30_000L)
    }

    @After
    fun tearDown() {
        schedulerScope.cancel()
        db.close()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `an increase on the first attempt is recorded once and enqueues no successor`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 130)

        val result = runAttempt(attempt = 0)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("one attempt, one request", 1, steamApi.callCount)

        val sessions = db.sessionDao().getAll()
        assertEquals(1, sessions.size)
        assertEquals(30, sessions.single().minutes)
        // The session ends when the play ended, not when the attempt ran.
        assertEquals(sessionEndAt, sessions.single().endAt)
        assertEquals(130, db.gameDao().getById(APP_ID)?.lastPlaytime)
        assertEquals(30, db.dailyProgressDao().getByDate(dateOf(lastSyncAt))?.minutesPlayed)

        // Terminating the chain is the absence of an action: nothing to cancel, nothing appended.
        assertEquals(0, pendingAttempts())
    }

    @Test
    fun `an increase seen by a late attempt still reports the same session end`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 100)

        runAttempt(attempt = 0)
        runAttempt(attempt = 1)
        // Eight minutes after the play, Steam finally publishes it.
        time.now = sessionEndAt + 480_000L
        steamApi.answer = observation(playtimeForever = 145)
        runAttempt(attempt = 2)

        assertEquals("three attempts, three requests, no fourth", 3, steamApi.callCount)
        val session = db.sessionDao().getAll().single()
        assertEquals(
            "a late attempt must not record the play minutes late",
            sessionEndAt,
            session.endAt,
        )
        assertEquals(45, session.minutes)
    }

    @Test
    fun `a schedule that observes nothing exhausts quietly and never asks for a retry`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 100)

        var last: ListenableWorker.Result? = null
        repeat(PostPlaySyncScheduler.ATTEMPT_COUNT) { attempt ->
            last = runAttempt(attempt = attempt)
        }

        assertEquals(PostPlaySyncScheduler.ATTEMPT_COUNT, steamApi.callCount)
        assertTrue(
            "an exhausted schedule is an ordinary outcome; a retry would re-run a concluded schedule",
            last is ListenableWorker.Result.Success,
        )
        assertTrue("a zero-minute session records nothing", db.sessionDao().getAll().isEmpty())
        // Three successors across four attempts — the last attempt appends nothing.
        assertEquals(PostPlaySyncScheduler.ATTEMPT_COUNT - 1, pendingAttempts())
    }

    @Test
    fun `a response naming a different game attributes nothing`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(appId = 999L, playtimeForever = 500)

        runAttempt(attempt = 0)

        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals("the stored baseline is untouched", 100, db.gameDao().getById(APP_ID)?.lastPlaytime)
        assertEquals(100, db.gameDao().getById(APP_ID)?.playtimeForever)
        // Unproductive, so the schedule carries on.
        assertEquals(1, pendingAttempts())
    }

    @Test
    fun `the requested game is selected when it is not the first recent game`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answers = listOf(
            observation(appId = 999L, playtimeForever = 500),
            observation(playtimeForever = 130),
        )

        runAttempt(attempt = 0)

        assertEquals(RecentPlaytimeRepository.RECENT_GAME_COUNT, steamApi.requestedCount)
        assertEquals(1, db.sessionDao().getAll().size)
        assertEquals(30, db.sessionDao().getAll().single().minutes)
        assertEquals(130, db.gameDao().getById(APP_ID)?.lastPlaytime)
        assertEquals(0, pendingAttempts())
    }

    @Test
    fun `an increase already committed by the periodic poll is not credited twice`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)

        // The periodic poll gets there first, through the same commit path.
        committer().commit(
            observed = listOf(
                PlaytimeObservationCommitter.ObservedGame(
                    appId = APP_ID,
                    name = "Portal",
                    iconUrl = "icon",
                    playtimeForever = 130,
                    playtime2Weeks = 130,
                ),
            ),
            observedPlayAt = sessionEndAt,
            syncedAt = sessionEndAt,
        )
        val afterPoll = db.sessionDao().getAll()
        val creditedAfterPoll = db.dailyProgressDao().getByDate(dateOf(lastSyncAt))?.minutesPlayed

        steamApi.answer = observation(playtimeForever = 130)
        runAttempt(attempt = 0)

        assertEquals("the same increase must not become a second session", afterPoll, db.sessionDao().getAll())
        assertEquals(
            "the same increase must not be credited twice",
            creditedAfterPoll,
            db.dailyProgressDao().getByDate(dateOf(lastSyncAt))?.minutesPlayed,
        )
        assertEquals(30, creditedAfterPoll)
    }

    @Test
    fun `a playtime decrease emits no session and no negative playtime`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 40)

        runAttempt(attempt = 0)

        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(
            "a decrease keeps the higher baseline so it cannot later double-count",
            100,
            db.gameDao().getById(APP_ID)?.lastPlaytime,
        )
        assertNull(db.dailyProgressDao().getByDate(dateOf(lastSyncAt)))
    }

    @Test
    fun `a failed request leaves stored data alone and does not end the schedule early`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.failWith = java.io.IOException("no network")

        val result = runAttempt(attempt = 0)

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(100, db.gameDao().getById(APP_ID)?.lastPlaytime)
        assertEquals("the schedule continues rather than aborting", 1, pendingAttempts())
    }

    @Test
    fun `a pending account change prevents an old post-play attempt from fetching`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 130)
        accountChangeMarker.markPending("account-b")

        try {
            val result = runAttempt(attempt = 0)

            assertTrue(result is ListenableWorker.Result.Success)
            assertEquals("the durable reset barrier stops old work before the request", 0, steamApi.callCount)
            assertTrue(db.sessionDao().getAll().isEmpty())
            assertEquals(0, pendingAttempts())
        } finally {
            accountChangeMarker.clear()
        }
    }

    @Test
    fun `an account switch during fetch prevents the old attempt from committing or appending`() =
        runTest {
            seedLibrary(playtime = 100)
            generations.set(APP_ID, 1L)
            steamApi.answer = observation(playtimeForever = 130)
            steamApi.onCall = { credentials.steamId = "account-b" }

            runAttempt(attempt = 0)

            assertEquals("the old schedule may finish its request but cannot write", 1, steamApi.callCount)
            assertTrue(db.sessionDao().getAll().isEmpty())
            assertEquals(100, db.gameDao().getById(APP_ID)?.lastPlaytime)
            assertEquals(0, pendingAttempts())
        }

    @Test
    fun `an attempt superseded before it runs spends no request`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 2L)
        steamApi.answer = observation(playtimeForever = 130)

        val result = runAttempt(attempt = 0, generation = 1L)

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals("a concluded schedule must not pay for an attempt", 0, steamApi.callCount)
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(0, pendingAttempts())
    }

    @Test
    fun `an increase observed after being superseded mid-flight is discarded`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 130)
        // The player quit the same game again while this attempt's request was in flight.
        steamApi.onCall = { generations.set(APP_ID, 2L) }

        runAttempt(attempt = 0, generation = 1L)

        assertTrue(
            "a superseded attempt must not commit, even holding an increase",
            db.sessionDao().getAll().isEmpty(),
        )
        assertEquals(100, db.gameDao().getById(APP_ID)?.lastPlaytime)
        assertEquals("and must not append into the schedule that replaced it", 0, pendingAttempts())
    }

    @Test
    fun `a no-increase attempt superseded mid-flight appends no successor`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 100)
        steamApi.onCall = { generations.set(APP_ID, 2L) }

        runAttempt(attempt = 0, generation = 1L)

        assertEquals(0, pendingAttempts())
        assertTrue(db.sessionDao().getAll().isEmpty())
    }

    @Test
    fun `every attempt is recorded as its own run, naming the game it was scoped to`() = runTest {
        seedLibrary(playtime = 100)
        generations.set(APP_ID, 1L)
        steamApi.answer = observation(playtimeForever = 100)

        repeat(PostPlaySyncScheduler.ATTEMPT_COUNT) { attempt -> runAttempt(attempt = attempt) }

        val runs = db.diagnosticsDao().observeRuns().first()
        assertEquals(
            "an attempt that observed nothing must still be recorded — a schedule that found " +
                "nothing has to be attributable rather than silent",
            PostPlaySyncScheduler.ATTEMPT_COUNT,
            runs.size,
        )
        assertTrue(runs.all { it.trigger.startsWith("post_play:$APP_ID") })
        assertEquals(
            (1..PostPlaySyncScheduler.ATTEMPT_COUNT).map { "post_play:$APP_ID#$it" }.toSet(),
            runs.map { it.trigger }.toSet(),
        )
    }

    private suspend fun runAttempt(attempt: Int, generation: Long = 1L): ListenableWorker.Result =
        buildWorker(attempt = attempt, generation = generation).doWork()

    private fun buildWorker(attempt: Int, generation: Long): PostPlaySyncWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ) = PostPlaySyncWorker(
                appContext = appContext,
                params = workerParameters,
                recentPlaytime = RecentPlaytimeRepository(steamApi, FakeCredentialsProvider()),
                database = db,
                gameDao = db.gameDao(),
                profileDao = db.playerProfileDao(),
                committer = committer(),
                derivedStateWriter = SyncDerivedStateWriter(
                    settings = SettingsDataStore(context),
                    gamificationUpdater = GamificationUpdater(
                        db.sessionDao(),
                        db.dailyProgressDao(),
                        db.playerProfileDao(),
                        db.hltbDataDao(),
                        db.achievementDao(),
                        db.gameDao(),
                    ),
                    derivedStateWrites = DerivedStateWriteCoordinator(),
                ),
                generations = coordinator,
                scheduler = scheduler,
                diagnostics = SyncRunRecorder(db.diagnosticsDao(), time),
                time = time,
                syncCoordinator = SteamSyncCoordinator(),
                credentials = credentials,
                accountChangeMarker = accountChangeMarker,
            )
        }
        return TestListenableWorkerBuilder<PostPlaySyncWorker>(
            context,
            inputData = workDataOf(
                PostPlaySyncWorker.KEY_APP_ID to APP_ID,
                PostPlaySyncWorker.KEY_ATTEMPT to attempt,
                PostPlaySyncWorker.KEY_SESSION_END_AT to sessionEndAt,
                PostPlaySyncWorker.KEY_GENERATION to generation,
                PostPlaySyncWorker.KEY_STEAM_ID to credentials.steamId,
            ),
        ).setWorkerFactory(factory).build()
    }

    private fun committer() = PlaytimeObservationCommitter(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        profileDao = db.playerProfileDao(),
        differ = SessionDiffer(),
        time = time,
    )

    /** A library with one game already baselined, and a poll history to diff against. */
    private suspend fun seedLibrary(playtime: Int) {
        db.gameDao().upsert(
            Game(
                appId = APP_ID,
                name = "Portal",
                iconUrl = "icon",
                playtimeForever = playtime,
                playtime2Weeks = playtime,
                lastPlaytime = playtime,
            ),
        )
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().updateSyncStatus(lastSyncAt = lastSyncAt, lastSyncError = null)
    }

    /** Attempts sitting in WorkManager for this game — successors that were actually appended. */
    private fun pendingAttempts(): Int = workManager
        .getWorkInfosForUniqueWork(PostPlaySyncScheduler.uniqueWorkName(APP_ID))
        .get()
        .count { it.state != WorkInfo.State.CANCELLED }

    private fun dateOf(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    private fun observation(appId: Long = APP_ID, playtimeForever: Int) = RecentlyPlayedGameDto(
        appid = appId,
        name = "Portal",
        playtimeForever = playtimeForever,
        playtime2Weeks = playtimeForever,
    )

    private class FakeGenerations : PostPlayGenerations {
        private val values = mutableMapOf<Long, Long>()
        fun set(appId: Long, generation: Long) { values[appId] = generation }
        override suspend fun advance(appId: Long): Long {
            val next = (values[appId] ?: 0L) + 1
            values[appId] = next
            return next
        }

        override suspend fun current(appId: Long): Long = values[appId] ?: 0L
    }

    private class FakeCredentialsProvider(var steamId: String = "account-a") : CredentialsProvider {
        override suspend fun currentCredentials() =
            CredentialsState.Configured(apiKey = "key", steamId = steamId)
    }

    /** Only the recently-played endpoint is reachable; anything else is a test failure. */
    private class FakeSteamApi : SteamApi {
        var answer: RecentlyPlayedGameDto? = null
        var answers: List<RecentlyPlayedGameDto>? = null
        var failWith: Exception? = null
        var onCall: (() -> Unit)? = null
        var callCount = 0
        var requestedCount: Int? = null

        override suspend fun getRecentlyPlayedGames(
            key: String,
            steamId: String,
            count: Int,
            scope: SyncRunRecorder.RunScope?,
        ): RecentlyPlayedGamesResponse {
            callCount++
            requestedCount = count
            onCall?.invoke()
            failWith?.let { throw it }
            val games = answers ?: answer?.let { listOf(it) } ?: emptyList()
            return RecentlyPlayedGamesResponse(RecentlyPlayedGamesResult(games.size, games))
        }

        override suspend fun getOwnedGames(
            key: String,
            steamId: String,
            includeAppInfo: Int,
            includePlayedFreeGames: Int,
            scope: SyncRunRecorder.RunScope?,
        ): OwnedGamesResponse = error("the targeted fetch must not read the library")

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

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: SyncRunRecorder.RunScope?,
        ): PlayerAchievementsResponse = error("not used")

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

    private class FakeTimeProvider(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-27")
    }

    private companion object {
        const val APP_ID = 440L
    }
}
