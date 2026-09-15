package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.local.dao.CloudReadDao
import com.example.backlogium.data.local.entity.CloudReadRecord
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.domain.FakeAchievementDao
import com.example.backlogium.domain.FakeDailyProgressDao
import com.example.backlogium.domain.FakeGameDao
import com.example.backlogium.domain.FakeHiddenGameDao
import com.example.backlogium.domain.FakeHltbDataDao
import com.example.backlogium.domain.FakePlayerProfileDao
import com.example.backlogium.domain.FakeSessionDao
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.InMemoryProgressMarksStore
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.ProgressTransitionCoordinator
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.testGame
import com.example.backlogium.domain.testRepository
import com.example.backlogium.domain.testSession
import com.example.backlogium.domain.testUpdater
import com.example.backlogium.gamification.RuleConfig
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tasks 6.1/6.2 of `add-cloud-presence-reader`: a cloud read must never touch derived
 * gamification state, and the sync path must never depend on the cloud endpoint being configured
 * or reachable. [CloudPresenceRepository] and the gamification pipeline are deliberately built
 * from independent fakes throughout, mirroring production wiring where the two share no DAO.
 */
class CloudPresenceReadOnlyGuaranteeTest {

    @Test
    fun completedReadLeavesLedgerDailyProgressAndProgressEventsUnchanged() = runTest {
        val marksStore = InMemoryProgressMarksStore(ProgressMarks(lastCelebratedLevel = 4, initialized = true))
        val coordinator = ProgressTransitionCoordinator()
        val dailyDao = FakeDailyProgressDao(
            listOf(DailyProgress("2026-09-14", minutesPlayed = 45, questMet = true)),
        )
        val profileDao = FakePlayerProfileDao(
            PlayerProfile(level = 7, totalXp = 900, currentStreak = 3, longestStreak = 5),
        )
        val updater = testUpdater(marksStore, coordinator, profileDao, dailyDao)
        val progressEvents = testRepository(marksStore, coordinator, profileDao, dailyDao)

        // A real earned event sits pending, so "unchanged" is a meaningful claim rather than a
        // vacuous one about two stores that were never going to touch either way.
        val expectedPending = listOf(ProgressEvent.LevelUp(4, 7))
        assertEquals(expectedPending, progressEvents.pendingEvents.first())
        val computedBefore = updater.compute(today = LocalDate.parse("2026-09-15"), config = RuleConfig())

        val cloudPresenceRepository = CloudPresenceRepository(
            api = FakeCloudPresenceApi(),
            credentialsStore = FakeCloudCredentialsStore(
                CloudCredentials("https://reader.example.com/read", "reader-secret"),
            ),
            credentialsProvider = FakeCredentials(ACCOUNT),
            settings = FakeSettingsRepository(),
            cloudReadDao = FakeCloudReadDao(),
            time = FixedTimeProvider(),
        )

        assertTrue(cloudPresenceRepository.read() is CloudReadResult.Success)

        assertEquals(0, dailyDao.upsertCount)
        assertEquals(0, dailyDao.questUpdateCount)
        assertEquals(0, profileDao.upsertCount)
        assertEquals(
            DailyProgress("2026-09-14", minutesPlayed = 45, questMet = true),
            dailyDao.getByDate("2026-09-14"),
        )
        assertEquals(7, profileDao.get()!!.level)
        assertEquals(expectedPending, progressEvents.pendingEvents.first())
        assertEquals(
            computedBefore,
            updater.compute(today = LocalDate.parse("2026-09-15"), config = RuleConfig()),
        )
    }

    @Test
    fun unconfiguredReadMakesNoRequestWhileASyncRunsConcurrently() = runTest {
        val api = FakeCloudPresenceApi()
        val cloudPresenceRepository = CloudPresenceRepository(
            api = api,
            credentialsStore = FakeCloudCredentialsStore(credentials = null),
            credentialsProvider = FakeCredentials(ACCOUNT),
            settings = FakeSettingsRepository(),
            cloudReadDao = FakeCloudReadDao(),
            time = FixedTimeProvider(),
        )

        val controlProfileDao = FakePlayerProfileDao()
        recompute(controlProfileDao)

        val profileDao = FakePlayerProfileDao()
        var readResult: CloudReadResult? = null
        val cloudJob = launch { readResult = cloudPresenceRepository.read() }
        val syncJob = launch { recompute(profileDao) }
        cloudJob.join()
        syncJob.join()

        assertEquals(CloudReadResult.Unconfigured, readResult)
        assertTrue(api.requests.isEmpty())
        assertEquals(controlProfileDao.get(), profileDao.get())
    }

    @Test
    fun unreachableEndpointDoesNotFailOrAlterTheSync() = runTest {
        val api = FakeCloudPresenceApi(failWith = IOException("simulated unreachable"))
        val cloudPresenceRepository = CloudPresenceRepository(
            api = api,
            credentialsStore = FakeCloudCredentialsStore(
                CloudCredentials("https://reader.example.com/read", "reader-secret"),
            ),
            credentialsProvider = FakeCredentials(ACCOUNT),
            settings = FakeSettingsRepository(),
            cloudReadDao = FakeCloudReadDao(),
            time = FixedTimeProvider(),
        )

        val controlProfileDao = FakePlayerProfileDao()
        recompute(controlProfileDao)

        val profileDao = FakePlayerProfileDao()
        var readResult: CloudReadResult? = null
        val cloudJob = launch { readResult = cloudPresenceRepository.read() }
        val syncJob = launch { recompute(profileDao) }
        cloudJob.join()
        syncJob.join()

        assertEquals(CloudReadResult.Failed(CloudReadFailure.UNREACHABLE), readResult)
        // The sync's outcome is identical to a control run the cloud call never sat beside.
        assertEquals(controlProfileDao.get(), profileDao.get())
    }

    /** A stand-in "sync": recomputes derived state for one 200-minute game, same as `steam-sync`. */
    private suspend fun recompute(profileDao: FakePlayerProfileDao) {
        GamificationUpdater(
            sessionDao = FakeSessionDao(listOf(testSession(minutes = 200))),
            dailyProgressDao = FakeDailyProgressDao(emptyList()),
            playerProfileDao = profileDao,
            hltbDataDao = FakeHltbDataDao(),
            achievementDao = FakeAchievementDao(emptyList()),
            gameDao = FakeGameDao(listOf(testGame(appId = 1L))),
            hiddenGameDao = FakeHiddenGameDao(),
        ).recompute(today = LocalDate.parse("2026-09-15"), source = RecomputeSource.SYNC, config = RuleConfig())
    }

    private class FakeCloudPresenceApi(
        var answer: CloudPresenceResponseDto = sampleResponse(),
        private val failWith: Exception? = null,
    ) : CloudPresenceApi {
        data class Request(val endpoint: String, val authorization: String, val position: String?)

        val requests = mutableListOf<Request>()

        override suspend fun read(
            endpoint: String,
            authorization: String,
            position: String?,
        ): CloudPresenceResponseDto {
            requests += Request(endpoint, authorization, position)
            failWith?.let { throw it }
            return answer
        }
    }

    private class FakeCloudCredentialsStore(
        var credentials: CloudCredentials?,
    ) : CloudCredentialsStore {
        override suspend fun readCloudCredentials(): CloudCredentials? = credentials

        override suspend fun writeCloudCredentials(endpoint: String, token: String) {
            credentials = CloudCredentials(endpoint, token)
        }

        override suspend fun clearCloudCredentials() {
            credentials = null
        }
    }

    private class FakeCloudReadDao : CloudReadDao {
        private var nextId = 1L
        val records = MutableStateFlow<List<CloudReadRecord>>(emptyList())

        override suspend fun insert(record: CloudReadRecord): Long {
            val stored = record.copy(id = nextId++)
            records.value = listOf(stored) + records.value
            return stored.id
        }

        override fun observeRecords(): Flow<List<CloudReadRecord>> = records

        override suspend fun prune(limit: Int) {
            records.value = records.value.take(limit)
        }
    }

    private class FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_750_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.of(2026, 9, 15)
    }

    private companion object {
        const val ACCOUNT = "76561198000000001"

        fun sampleResponse() = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 2,
                    t = "2026-09-15T00:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-15T00:10:00Z",
                gameid = "10",
                gameName = "Portal",
                personastate = 1,
            ),
            windowStart = "2026-09-15T00:00:00Z",
            windowEnd = "2026-09-15T00:10:00Z",
            readAt = "2026-09-15T00:11:00Z",
        )
    }
}
