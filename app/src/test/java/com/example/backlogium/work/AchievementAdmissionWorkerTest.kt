package com.example.backlogium.work

import android.content.Context
import androidx.room.Room
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.example.backlogium.data.achievement.AchievementObservationStore
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.AchievementAdmissionWork
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameAchievementSync
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.AchievementSchemaDto
import com.example.backlogium.data.remote.dto.AvailableGameStatsDto
import com.example.backlogium.data.remote.dto.GameSchemaResponse
import com.example.backlogium.data.remote.dto.GameSchemaResult
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentageDto
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResponse
import com.example.backlogium.data.remote.dto.GlobalAchievementPercentagesResult
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.data.remote.dto.PlayerAchievementsResponse
import com.example.backlogium.data.remote.dto.PlayerAchievementsResult
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.SingleGameRefresh
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementAdmissionWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
        .allowMainThreadQueries().build()
    private val marker = AccountChangeMarkerStore(context)
    private val syncCoordinator = SteamSyncCoordinator()
    private var now = 100_000L
    private val time = object : TimeProvider {
        override fun nowMillis() = now
        override fun zone() = ZoneOffset.UTC
        override fun today() = Instant.ofEpochMilli(now).atOffset(zone()).toLocalDate()
    }
    private val credentials = object : CredentialsProvider {
        override suspend fun currentCredentials() = CredentialsState.Configured(KEY, ACCOUNT)
    }
    private val unusedSteamApi = Proxy.newProxyInstance(
        SteamApi::class.java.classLoader,
        arrayOf(SteamApi::class.java),
    ) { _, method, _ -> throw IOException("No fixture for ${method.name}") } as SteamApi
    private val api = AdmissionSteamApi()
    private val scheduled = mutableListOf<Triple<String, Long, Long>>()
    private val scheduler = object : AchievementAdmissionWorkScheduler {
        override suspend fun enqueue(steamId: String, generation: Long, delayMillis: Long) {
            scheduled += Triple(steamId, generation, delayMillis)
        }
        override suspend fun recoverPending() = Unit
    }
    private val hiddenGamesRepository = HiddenGamesRepository(
        database.hiddenGameDao(), database.gameDao(), database.gameGenreCacheDao(), time,
    )
    private val achievementRepository = AchievementRepository(
        steamApi = api,
        achievementDao = database.achievementDao(),
        gameAchievementSyncDao = database.gameAchievementSyncDao(),
        gameDao = database.gameDao(),
        hiddenGamesRepository = hiddenGamesRepository,
        time = time,
        observationStore = AchievementObservationStore(database, credentials, marker, syncCoordinator),
    )

    @After
    fun close() {
        database.close()
    }

    @Test
    fun backlogLargerThanThreeCapsFinishesThroughSerializedBoundedRuns() = runTest {
        val generation = prepare()
        val total = AchievementAdmissionWorker.PER_RUN_CAP * 3 + 1
        seedAdmissions(total, generation)

        repeat(4) { runIndex ->
            assertTrue(worker(generation).doWork() is ListenableWorker.Result.Success)
            val completed = minOf(total, (runIndex + 1) * AchievementAdmissionWorker.PER_RUN_CAP)
            assertEquals((1L..completed.toLong()).toList(), api.playerCalls.toList())
            val state = database.achievementAdmissionDao().state(ACCOUNT, generation)
            assertEquals(total - completed, state.pendingCount)
            if (state.pendingCount > 0) assertEquals(0L, scheduled.last().third)
        }

        assertEquals(total, (1L..total.toLong()).sumOf { database.achievementDao().getForGame(it).size })
        assertTrue(database.achievementWatchDao().getAll(ACCOUNT).isEmpty())
        assertEquals(3, scheduled.size)
    }

    @Test
    fun retryAfterIsPersistedAndNoRequestRunsBeforeItsDeadline() = runTest {
        val generation = prepare()
        seedAdmissions(1, generation)
        api.failOnceFor[1L] = tooManyRequests("120")

        assertTrue(worker(generation).doWork() is ListenableWorker.Result.Success)
        val queued = database.achievementAdmissionDao().get(ACCOUNT, generation, 1L)!!
        assertEquals(now + 120_000L, queued.nextEligibleAt)
        assertEquals(120_000L, scheduled.last().third)
        assertEquals(listOf(1L), api.playerCalls)

        now = queued.nextEligibleAt
        assertTrue(worker(generation).doWork() is ListenableWorker.Result.Success)
        assertEquals(listOf(1L, 1L), api.playerCalls)
        assertEquals(0, database.achievementAdmissionDao().state(ACCOUNT, generation).pendingCount)
    }

    @Test
    fun catalogueCanHydrateWhenPlayerProgressIsUnavailable() = runTest {
        val generation = prepare()
        seedAdmissions(1, generation)
        api.noStatsFor += 1L

        assertTrue(worker(generation).doWork() is ListenableWorker.Result.Success)

        assertEquals(0, database.achievementAdmissionDao().state(ACCOUNT, generation).pendingCount)
        assertEquals(null, database.gameAchievementSyncDao().get(1L)?.playerStateFetchedAt)
        assertEquals("ACH_1", database.achievementCatalogueDao().entries(1L).single().apiName)
        assertTrue(database.achievementDao().getForGame(1L).isEmpty())
    }

    @Test
    fun overlappingDetailRefreshSharesTheAdmissionFetchAndMerge() = runTest {
        val generation = prepare()
        seedAdmissions(1, generation)
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val firstPlayerCall = api.expectNextPlayerCall()

        // Let admission establish the shared player fetch first, then start detail while that
        // fetch is suspended. This proves the detail caller joins the in-flight request.
        val admission = async(start = CoroutineStart.UNDISPATCHED) { worker(generation).doWork() }
        assertEquals(1L, firstPlayerCall.await())
        val detail = async(start = CoroutineStart.UNDISPATCHED) {
            achievementRepository.refreshOne(KEY, ACCOUNT, 1L)
        }
        withTimeout(5_000L) {
            while (inFlightSubscriberCount(achievementRepository) < 2) {
                runCurrent()
                yield()
            }
        }
        assertEquals(1, api.playerCalls.size)
        gate.complete(Unit)

        assertTrue(admission.await() is ListenableWorker.Result.Success)
        assertTrue(detail.await() is SingleGameRefresh.Persisted)
        assertEquals(listOf(1L), api.playerCalls)
        assertEquals(1, database.achievementDao().getForGame(1L).size)
        assertEquals(12.5, database.achievementDao().getForGame(1L).single().snapshotPercent!!, 0.0)
    }

    @Test
    fun accountReplacementDuringFetchDropsTheOldResultAndQueue() = runTest {
        val generation = prepare()
        seedAdmissions(1, generation)
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val firstPlayerCall = api.expectNextPlayerCall()
        val admission = async { worker(generation).doWork() }
        assertEquals(1L, firstPlayerCall.await())

        marker.markPending(REPLACEMENT)
        try {
            com.example.backlogium.data.repo.AccountRoomReset(database).resetForAccountChange(REPLACEMENT)
            gate.complete(Unit)
            assertTrue(admission.await() is ListenableWorker.Result.Success)
            assertEquals(REPLACEMENT, database.playerProfileDao().get()?.steamId)
            assertTrue(database.achievementDao().getForGame(1L).isEmpty())
            assertEquals(0, database.achievementAdmissionDao().state(ACCOUNT, generation).pendingCount)
        } finally {
            marker.clear()
        }
    }

    @Test
    fun cancellationStopsTheDrainAndLeavesDurableWorkForRecovery() = runTest {
        val generation = prepare()
        seedAdmissions(2, generation)
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val firstPlayerCall = api.expectNextPlayerCall()
        val admission = async { worker(generation).doWork() }
        assertEquals(1L, firstPlayerCall.await())

        admission.cancelAndJoin()

        assertEquals(listOf(1L), api.playerCalls)
        assertEquals(2, database.achievementAdmissionDao().state(ACCOUNT, generation).pendingCount)
    }

    private suspend fun prepare(): Long {
        marker.clear()
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateSteamIdentity(ACCOUNT, 0, null, null, null)
        return marker.generation()
    }

    private suspend fun seedAdmissions(count: Int, generation: Long) {
        val games = (1L..count.toLong()).map { Game(it, "Game $it", "", 0, 0, 0) }
        database.gameDao().upsertAll(games)
        // A fresh schema lookup per game exercises catalogue and player work together.
        database.gameAchievementSyncDao().upsertAll(games.map {
            GameAchievementSync(it.appId, null, null, null, 0L)
        })
        database.achievementAdmissionDao().insertAll(games.mapIndexed { index, game ->
            AchievementAdmissionWork(ACCOUNT, generation, game.appId, index + 1L)
        })
    }

    private fun worker(generation: Long): AchievementAdmissionWorker =
        TestListenableWorkerBuilder<AchievementAdmissionWorker>(
            context,
            inputData = workDataOf(
                AchievementAdmissionWorker.KEY_STEAM_ID to ACCOUNT,
                AchievementAdmissionWorker.KEY_GENERATION to generation,
            ),
        ).setId(UUID.randomUUID()).setWorkerFactory(object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                AchievementAdmissionWorker(
                    appContext, workerParameters, credentials, marker, database,
                    database.achievementAdmissionDao(), database.gameDao(), database.playerProfileDao(),
                    database.gameAchievementSyncDao(), hiddenGamesRepository, achievementRepository,
                    scheduler, syncCoordinator, time,
                )
        }).build()

    /** Wait until detail has actually joined the suspended flight, past its Room reads. */
    private fun inFlightSubscriberCount(repository: AchievementRepository): Int {
        val monitorField = repository.javaClass.getDeclaredField("fetchMonitor").apply { isAccessible = true }
        val monitor = requireNotNull(monitorField.get(repository))
        return synchronized(monitor) {
            val flightsField = repository.javaClass.getDeclaredField("inFlightGameFetches").apply { isAccessible = true }
            val flights = flightsField.get(repository) as Map<*, *>
            val flight = flights.values.singleOrNull() ?: return@synchronized 0
            val subscribersField = flight.javaClass.getDeclaredField("subscribers").apply { isAccessible = true }
            subscribersField.getInt(flight)
        }
    }

    private inner class AdmissionSteamApi : SteamApi by unusedSteamApi {
        val playerCalls = mutableListOf<Long>()
        val failOnceFor = mutableMapOf<Long, Exception>()
        val noStatsFor = mutableSetOf<Long>()
        var gate: CompletableDeferred<Unit>? = null
        private var nextPlayerCall: CompletableDeferred<Long>? = null

        fun expectNextPlayerCall() = CompletableDeferred<Long>().also { nextPlayerCall = it }

        override suspend fun getSchemaForGame(
            key: String,
            appId: Long,
            scope: com.example.backlogium.data.diagnostics.SyncRunRecorder.RunScope?,
        ) = GameSchemaResponse(GameSchemaResult(AvailableGameStatsDto(listOf(
            AchievementSchemaDto("ACH_1", "First", "icon"),
        ))))

        override suspend fun getPlayerAchievements(
            key: String,
            steamId: String,
            appId: Long,
            scope: com.example.backlogium.data.diagnostics.SyncRunRecorder.RunScope?,
        ): PlayerAchievementsResponse {
            playerCalls += appId
            nextPlayerCall?.complete(appId)
            nextPlayerCall = null
            gate?.await()
            failOnceFor.remove(appId)?.let { throw it }
            if (appId in noStatsFor) return PlayerAchievementsResponse(PlayerAchievementsResult(success = false))
            return PlayerAchievementsResponse(PlayerAchievementsResult(
                success = true,
                achievements = listOf(PlayerAchievementDto("ACH_1", achieved = 1, unlocktime = 5L)),
            ))
        }

        override suspend fun getGlobalAchievementPercentages(
            gameId: Long,
            scope: com.example.backlogium.data.diagnostics.SyncRunRecorder.RunScope?,
        ) = GlobalAchievementPercentagesResponse(GlobalAchievementPercentagesResult(listOf(
            GlobalAchievementPercentageDto("ACH_1", 12.5),
        )))
    }

    private fun tooManyRequests(seconds: String): HttpException {
        val raw = okhttp3.Response.Builder()
            .request(Request.Builder().url("https://api.steampowered.com").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", seconds)
            .build()
        return HttpException(Response.error<PlayerAchievementsResponse>("throttled".toResponseBody(), raw))
    }

    private companion object {
        const val ACCOUNT = "76561198000000001"
        const val REPLACEMENT = "76561198000000002"
        const val KEY = "fixture-key"
    }
}
