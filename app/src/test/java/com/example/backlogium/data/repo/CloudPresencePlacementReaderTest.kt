package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.CloudReadDao
import com.example.backlogium.data.local.entity.CloudReadRecord
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresencePlaytimePlacement
import com.example.backlogium.domain.CloudPresenceReconstruction
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * The placement reader must not treat one shared-cursor page as the whole Steam diff window.
 *
 * The cloud cursor is also advanced by Settings/manual reads, so a stale Steam sync may resume
 * inside its own diff window and observe only the suffix; distributing the entire Steam delta
 * across that suffix would make attribution depend on which reader consumed the cursor first.
 * Likewise a `hasMore` page must not be discarded after its cursor was already persisted, or
 * the Steam baseline advances past a delta its later pages could have placed.
 */
@RunWith(RobolectricTestRunner::class)
class CloudPresencePlacementReaderTest {
    private lateinit var database: BacklogiumDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun cursorAdvancedInsideSteamPeriodRefusesSuffix() = runBlocking {
        val periodStart = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli()
        val cursor = "2026-09-16T00:00:00Z"
        val periodEnd = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        // A manual cloud read already consumed Monday->Wednesday, so the stale Friday sync
        // resumes at Wednesday. The server reports the resumed window starting there.
        val suffix = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = cursor,
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-18T00:00:00Z",
                    prevLastObservedAt = "2026-09-17T23:59:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = null,
            nextPosition = "2026-09-18T00:00:00Z",
            hasMore = false,
            windowStart = cursor,
            windowEnd = "2026-09-18T00:00:00Z",
            readAt = "2026-09-18T00:01:00Z",
        )
        val api = FakeCloudPresenceApi { position ->
            assertEquals(cursor, position)
            suffix
        }
        val settings = FakeSettingsRepository()
        settings.setCloudReadPosition(cursor)
        val placement = placementReader(api, settings)

        val result = placement.read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        // The suffix alone would place the whole Monday->Friday delta across Wednesday->Friday.
        assertNull(result)
    }

    @Test
    fun staleTerminalWindowEndBeforePeriodEndRefusesPlacement() = runBlocking {
        val periodStart = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli()
        val periodEnd = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        // Steam's diff window is Monday->Friday, but the poller stopped Wednesday: the terminal
        // drain starts Monday yet its latest observation is Wednesday, with one confirmed Tuesday
        // interval. Accepting it would proportionally assign the whole Monday->Friday Steam delta
        // to Tuesday; the unobserved Wednesday->Friday tail carries no rejected interval for the
        // placement rule to refuse, so the gate must refuse and leave the sync on its unaided path.
        val stale = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-15T00:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-15T02:00:00Z",
                    prevLastObservedAt = "2026-09-15T01:59:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-16T00:00:00Z",
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = "2026-09-15T02:00:00Z",
            hasMore = false,
            windowStart = "2026-09-14T00:00:00Z",
            windowEnd = "2026-09-16T00:00:00Z",
            readAt = "2026-09-18T00:01:00Z",
        )
        val api = FakeCloudPresenceApi { position ->
            assertNull(position)
            stale
        }
        val settings = FakeSettingsRepository()
        val placement = placementReader(api, settings)

        val result = placement.read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        // Null keeps the sync on its ordinary unaided attribution; the Wednesday->Friday tail
        // must not be placed onto Tuesday.
        assertNull(result)

        // The gate is what prevents the misplacement: the Tuesday span alone would have placed
        // the full Monday->Friday delta.
        val hazard = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = 10L,
                diffedMinutes = 60,
                periodStartAt = periodStart,
                periodEndAt = periodEnd,
                intervals = listOf(
                    CloudPresenceInterval(
                        appId = 10L,
                        gameName = "Portal",
                        startAt = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli(),
                        endAt = Instant.parse("2026-09-15T02:00:00Z").toEpochMilli(),
                        ongoing = false,
                        coverage = CloudCoverageState.CONTINUOUS,
                        observedUntil = null,
                        coverageLapseFrom = null,
                        coverageLapseRecoveredAt = null,
                        mayHaveStartedBefore = false,
                    ),
                ),
            ),
        )
        assertTrue(hazard != null)
        assertEquals(60, hazard!!.sumOf { it.addedMinutes })
    }

    @Test
    fun healthyTerminalSnapshotOnePollIntervalBeforePeriodEndAdmitsPlacement() = runBlocking {
        val periodStart = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli()
        val periodEnd = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        // The periodic worker captures `periodEndAt = now` before this read while the cloud
        // poller observes once per minute (cloud-presence-poller spec), so a healthy terminal
        // snapshot normally lags `periodEndAt` by up to one polling interval. The right-edge
        // gate must tolerate that lag: an exact gate would admit placement only when a poll
        // happened to land between the worker's `now` capture and this read.
        val windowEnd = Instant.ofEpochMilli(periodEnd - 60_000L).toString()
        val healthy = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-14T00:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = windowEnd,
                    prevLastObservedAt = Instant.ofEpochMilli(periodEnd - 120_000L).toString(),
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = windowEnd,
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = windowEnd,
            hasMore = false,
            windowStart = "2026-09-14T00:00:00Z",
            windowEnd = windowEnd,
            readAt = "2026-09-18T00:01:00Z",
        )
        val api = FakeCloudPresenceApi { position ->
            assertNull(position)
            healthy
        }
        val settings = FakeSettingsRepository()
        val placement = placementReader(api, settings)

        val result = placement.read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        assertTrue(result != null)
        assertFalse(result!!.hasMore)
        assertEquals(periodEnd - 60_000L, result.windowEnd)
    }

    @Test
    fun tailBeyondCloudTailToleranceRefusesPlacement() = runBlocking {
        val periodStart = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli()
        val periodEnd = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        // Same healthy shape as above, but the unobserved tail exceeds the cloud tail tolerance
        // (one-minute schedule plus execution jitter, not the 10-minute session-gap tolerance):
        // the gate must still refuse rather than proportionally assigning the whole Steam delta
        // to the earlier confirmed span.
        val lag = CloudPresenceReconstruction.DEFAULT_TAIL_TOLERANCE_MILLIS + 60_000L
        val windowEnd = Instant.ofEpochMilli(periodEnd - lag).toString()
        val staleTail = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-14T00:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = windowEnd,
                    prevLastObservedAt = "2026-09-14T00:01:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = windowEnd,
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = windowEnd,
            hasMore = false,
            windowStart = "2026-09-14T00:00:00Z",
            windowEnd = windowEnd,
            readAt = "2026-09-18T00:01:00Z",
        )
        val api = FakeCloudPresenceApi { position ->
            assertNull(position)
            staleTail
        }
        val settings = FakeSettingsRepository()
        val placement = placementReader(api, settings)

        val result = placement.read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        assertNull(result)
    }

    @Test
    fun missedPollTailCrossingMidnightRefusesWhileHealthyLagAdmits() = runBlocking {
        val periodStart = Instant.parse("2026-09-17T00:00:00Z").toEpochMilli()
        val periodEnd = Instant.parse("2026-09-18T00:05:00Z").toEpochMilli()
        // Boundary around the cloud tail tolerance: a healthy ~1-minute lag is ordinary
        // scheduler skew and must admit placement, while a 10-minute missed-poll tail crossing
        // midnight must fall back. Ten minutes equals the old 10-minute session-gap tolerance,
        // so the stale case below passed before the fix: a Steam delta possibly earned after
        // midnight would be allocated entirely onto the pre-midnight interval.
        val staleWindowEnd = "2026-09-17T23:55:00Z"
        val stale = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-17T23:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-17T23:30:00Z",
                    prevLastObservedAt = "2026-09-17T23:29:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = staleWindowEnd,
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = staleWindowEnd,
            hasMore = false,
            windowStart = "2026-09-17T00:00:00Z",
            windowEnd = staleWindowEnd,
            readAt = "2026-09-18T00:06:00Z",
        )
        val staleResult = placementReader(
            FakeCloudPresenceApi { position ->
                assertNull(position)
                stale
            },
            FakeSettingsRepository(),
        ).read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        assertNull(staleResult)

        // The gate is what prevents the misattribution: the pre-midnight span alone would have
        // placed the full delta spanning midnight.
        val hazard = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = 10L,
                diffedMinutes = 60,
                periodStartAt = periodStart,
                periodEndAt = periodEnd,
                intervals = listOf(
                    CloudPresenceInterval(
                        appId = 10L,
                        gameName = "Portal",
                        startAt = Instant.parse("2026-09-17T23:00:00Z").toEpochMilli(),
                        endAt = Instant.parse("2026-09-17T23:30:00Z").toEpochMilli(),
                        ongoing = false,
                        coverage = CloudCoverageState.CONTINUOUS,
                        observedUntil = null,
                        coverageLapseFrom = null,
                        coverageLapseRecoveredAt = null,
                        mayHaveStartedBefore = false,
                    ),
                ),
            ),
        )
        assertTrue(hazard != null)
        assertEquals(60, hazard!!.sumOf { it.addedMinutes })

        // Same window with a healthy one-poll-interval lag admits placement.
        val healthyWindowEnd = Instant.ofEpochMilli(periodEnd - 60_000L).toString()
        val healthy = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-17T23:00:00Z",
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = "2026-09-17T23:30:00Z",
                    prevLastObservedAt = "2026-09-17T23:29:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = healthyWindowEnd,
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = healthyWindowEnd,
            hasMore = false,
            windowStart = "2026-09-17T00:00:00Z",
            windowEnd = healthyWindowEnd,
            readAt = "2026-09-18T00:06:00Z",
        )
        val healthyResult = placementReader(
            FakeCloudPresenceApi { position ->
                assertNull(position)
                healthy
            },
            FakeSettingsRepository(),
        ).read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        assertTrue(healthyResult != null)
        assertFalse(healthyResult!!.hasMore)
        assertEquals(periodEnd - 60_000L, healthyResult.windowEnd)
    }

    @Test
    fun multiPageUnreadHistoryAccumulatesWithoutLosingEarlierPages() = runBlocking {
        val first = "2026-09-14T00:00:00Z"
        val boundary = "2026-09-16T00:00:00Z"
        val last = "2026-09-18T00:00:00Z"
        val page1 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = first,
                    gameid = "10",
                    gameName = "Portal",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T23:59:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
            ),
            current = CloudPresenceCurrentDto(
                v = 2,
                lastObservedAt = "2026-09-18T00:00:00Z",
                gameid = "20",
                gameName = "Other",
                personastate = 1,
            ),
            nextPosition = boundary,
            hasMore = true,
            windowStart = first,
            windowEnd = boundary,
            readAt = "2026-09-16T00:01:00Z",
        )
        val page2 = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(
                    v = 3,
                    t = boundary,
                    prevLastObservedAt = "2026-09-15T23:59:00Z",
                    gameid = "20",
                    gameName = "Other",
                    personastate = 1,
                ),
                CloudPresenceTransitionDto(
                    v = 3,
                    t = last,
                    prevLastObservedAt = "2026-09-17T23:59:00Z",
                    gameid = "30",
                    gameName = "Third",
                    personastate = 1,
                ),
            ),
            current = null,
            nextPosition = last,
            hasMore = false,
            windowStart = first,
            windowEnd = last,
            readAt = "2026-09-18T00:01:00Z",
        )
        val requests = mutableListOf<String?>()
        val api = FakeCloudPresenceApi { position ->
            requests += position
            if (position == null) page1 else page2
        }
        val settings = FakeSettingsRepository()
        val placement = placementReader(api, settings)
        val periodStart = Instant.parse(first).toEpochMilli()
        val periodEnd = Instant.parse(last).toEpochMilli()

        val result = placement.read(CloudReadTrigger.SYNC, periodStart, periodEnd)

        // A single-page reader would have persisted the next cursor and then discarded page 1
        // once `hasMore` was observed, leaving the Steam commit to baseline past a delta its
        // later pages could have placed. The drain accumulates both pages instead.
        assertTrue(result != null)
        assertFalse(result!!.hasMore)
        assertEquals(listOf(10L, 20L), result.intervals.map { it.appId })
        assertEquals(4, result.observationCount)
        assertEquals(periodStart, result.windowStart)
        assertEquals(2, requests.size)
        assertEquals(last, settings.cloudReadPosition.first())
    }

    private fun placementReader(
        api: CloudPresenceApi,
        settings: FakeSettingsRepository,
    ): RepositoryCloudPresencePlacementReader {
        val store = FakeCloudCredentialsStore(
            CloudCredentials("https://reader.example.com/read", "secret"),
        )
        val records = FakeCloudReadDao()
        val repository = CloudPresenceRepository(
            api = api,
            credentialsStore = store,
            credentialsProvider = FakeCredentials(ACCOUNT),
            settings = settings,
            cloudReadDao = records,
            time = FixedTimeProvider(),
        )
        val ingestor = CloudPresenceSessionIngestor(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            sessionActionWriter = SessionActionWriter(
                sessionDao = database.sessionDao(),
                dailyProgressDao = database.dailyProgressDao(),
                hiddenGameDao = database.hiddenGameDao(),
                time = FixedTimeProvider(),
            ),
            gamificationUpdater = GamificationUpdater(
                sessionDao = database.sessionDao(),
                dailyProgressDao = database.dailyProgressDao(),
                playerProfileDao = database.playerProfileDao(),
                hltbDataDao = database.hltbDataDao(),
                achievementDao = database.achievementDao(),
                gameDao = database.gameDao(),
                hiddenGameDao = database.hiddenGameDao(),
            ),
            settings = settings,
            derivedStateWrites = DerivedStateWriteCoordinator(),
            time = FixedTimeProvider(),
        )
        return RepositoryCloudPresencePlacementReader(repository, ingestor)
    }

    private class FakeCloudPresenceApi(
        private val answer: (position: String?) -> CloudPresenceResponseDto,
    ) : CloudPresenceApi {
        override suspend fun read(
            endpoint: String,
            authorization: String,
            position: String?,
        ): CloudPresenceResponseDto = answer(position)
    }

    private class FakeCloudCredentialsStore(
        var credentials: CloudCredentials? = null,
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

        override suspend fun deleteAll() {
            records.value = emptyList()
        }
    }

    private class FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_750_000_000_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.of(2026, 9, 18)
    }

    private companion object {
        const val ACCOUNT = "76561198000000001"
    }
}
