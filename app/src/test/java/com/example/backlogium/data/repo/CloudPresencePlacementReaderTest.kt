package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.CloudReadDao
import com.example.backlogium.data.local.entity.CloudReadRecord
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresencePlaytimePlacement
import com.example.backlogium.domain.CloudPresenceReconstruction
import com.example.backlogium.domain.CloudReaderIdentity
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.data.local.entity.Game
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
    fun routineBeforeSteamDeltaRetainsOwnedIntervalAndCombinesItWithUnreadSuffix() = runBlocking {
        val periodStart = "2026-09-14T00:00:00Z"
        val opening = "2026-09-15T00:00:00Z"
        val firstClose = "2026-09-15T02:00:00Z"
        val suffixStart = "2026-09-17T00:00:00Z"
        val suffixClose = "2026-09-17T02:00:00Z"
        val periodEnd = "2026-09-18T00:00:00Z"
        val owned = "10"
        val firstPage = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(v = 3, t = opening, gameid = owned, gameName = "Owned", personastate = 1),
                CloudPresenceTransitionDto(v = 3, t = firstClose, gameid = "20", gameName = "Other",
                    personastate = 1, prevLastObservedAt = "2026-09-15T01:59:00Z"),
            ),
            current = CloudPresenceCurrentDto(v = 2, lastObservedAt = "2026-09-16T00:00:00Z",
                gameid = "20", gameName = "Other", personastate = 1),
            nextPosition = firstClose, hasMore = false, windowStart = periodStart,
            windowEnd = "2026-09-16T00:00:00Z", readAt = "2026-09-16T00:01:00Z",
        )
        val suffix = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(v = 3, t = suffixStart, gameid = owned,
                    gameName = "Owned", personastate = 1),
                CloudPresenceTransitionDto(v = 3, t = suffixClose, gameid = "20", gameName = "Other",
                    personastate = 1, prevLastObservedAt = "2026-09-17T01:59:00Z"),
            ),
            current = CloudPresenceCurrentDto(v = 2, lastObservedAt = periodEnd,
                gameid = "20", gameName = "Other", personastate = 1),
            nextPosition = suffixClose, hasMore = false, windowStart = firstClose,
            windowEnd = periodEnd, readAt = "2026-09-18T00:01:00Z",
        )
        val api = FakeCloudPresenceApi { position ->
            when (position) {
                null -> firstPage
                firstClose -> suffix
                else -> error("Unexpected position $position")
            }
        }
        val settings = FakeSettingsRepository()
        val (reader, placement) = readerPair(api, settings)
        database.gameDao().upsert(Game(10L, "Owned", "", 100, 0, 100))
        database.playerProfileDao().insertIfMissing()
        val startAt = Instant.parse(periodStart).toEpochMilli()
        val endAt = Instant.parse(periodEnd).toEpochMilli()
        database.playerProfileDao().updateSyncStatus(startAt, null)

        assertEquals(CloudCatchUpResult.Complete(1, 2, false), reader.readRoutineCatchUp { })
        assertEquals(firstClose, settings.cloudReadPosition.first())
        val evidence = database.pendingCloudEvidenceDao()
        assertEquals(Instant.parse(opening).toEpochMilli(),
            evidence.intervals(ACCOUNT, 0L).first { it.appId == 10L }.startAt)
        assertTrue(database.sessionDao().getAll().isEmpty()) // acquisition is not attribution

        val combined = placement.read(CloudReadTrigger.SYNC, startAt, endAt)!!
        assertEquals(startAt, combined.windowStart)
        assertEquals(listOf(Instant.parse(opening).toEpochMilli(), Instant.parse(suffixStart).toEpochMilli()),
            combined.intervals.filter { it.appId == 10L }.map { it.startAt })

        val committer = PlaytimeObservationCommitter(
            gameDao = database.gameDao(), sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(), profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(), differ = SessionDiffer(),
            time = FixedTimeProvider(), sessionActionWriter = SessionActionWriter(
                database.sessionDao(), database.dailyProgressDao(), database.hiddenGameDao(), FixedTimeProvider(),
            ),
            pendingEvidencePruner = CloudPendingEvidencePruner(
                database.pendingCloudEvidenceDao(), FakeCredentials(ACCOUNT), settings,
            ),
        )
        val observed = listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 130, 0))
        try {
            database.withTransaction {
                committer.commit(observed, endAt, endAt,
                    CloudPresencePlaytimePlacement.Input(combined.intervals, combined.readerIdentity),
                    pruningIdentity = combined.readerIdentity)
                error("simulate failed Steam baseline commit")
            }
        } catch (_: IllegalStateException) {
            // The Room rollback must leave both the baseline and acquired evidence retryable.
        }
        assertEquals(100, database.gameDao().getById(10L)!!.lastPlaytime)
        assertEquals(2, evidence.intervals(ACCOUNT, 0L).count { it.appId == 10L })

        val committed = database.withTransaction {
            val result = committer.commit(observed, endAt, endAt,
                CloudPresencePlaytimePlacement.Input(combined.intervals, combined.readerIdentity),
                pruningIdentity = combined.readerIdentity)
            database.playerProfileDao().updateSyncStatus(endAt, null)
            result
        }
        assertEquals(30, committed.playedDeltaByAppId[10L])
        assertEquals(30, database.sessionDao().getAll().filter { it.appId == 10L }.sumOf { it.minutes })
        assertTrue(evidence.intervals(ACCOUNT, 0L).none { it.appId == 10L })
    }

    @Test
    fun placementReadPersistsEvidenceAcrossFailedCommitAndRetryClipsToNewDiffWindow() = runBlocking {
        val initial = "2026-09-14T00:00:00Z"
        val opening = "2026-09-15T00:00:00Z"
        val nextBaseline = "2026-09-16T00:00:00Z"
        val close = "2026-09-17T00:00:00Z"
        val end = "2026-09-18T00:00:00Z"
        val first = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(v = 3, t = opening, gameid = "10", gameName = "Owned", personastate = 1),
                CloudPresenceTransitionDto(v = 3, t = close, gameid = "20", gameName = "Other",
                    personastate = 1, prevLastObservedAt = "2026-09-16T23:59:00Z"),
            ),
            current = CloudPresenceCurrentDto(v = 2, lastObservedAt = end, gameid = "20",
                gameName = "Other", personastate = 1),
            nextPosition = close, hasMore = false, windowStart = initial,
            windowEnd = end, readAt = "2026-09-18T00:01:00Z",
        )
        val terminal = first.copy(transitions = emptyList(), windowStart = close)
        val api = FakeCloudPresenceApi { position ->
            when (position) {
                null -> first
                close -> terminal
                else -> error("unexpected cursor: $position")
            }
        }
        val settings = FakeSettingsRepository()
        val (_, placement) = readerPair(api, settings)
        val startAt = Instant.parse(initial).toEpochMilli()
        val nextBaselineAt = Instant.parse(nextBaseline).toEpochMilli()
        val endAt = Instant.parse(end).toEpochMilli()
        database.gameDao().upsert(Game(10L, "Owned", "", 100, 0, 100))
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateSyncStatus(startAt, null)
        val evidence = database.pendingCloudEvidenceDao()

        val firstRead = placement.read(CloudReadTrigger.SYNC, startAt, endAt)!!
        assertEquals(close, settings.cloudReadPosition.first())
        assertEquals(1, evidence.intervals(ACCOUNT, 0L).count { it.appId == 10L })
        val committer = PlaytimeObservationCommitter(
            gameDao = database.gameDao(), sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(), profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(), differ = SessionDiffer(), time = FixedTimeProvider(),
            sessionActionWriter = SessionActionWriter(database.sessionDao(), database.dailyProgressDao(),
                database.hiddenGameDao(), FixedTimeProvider()),
            pendingEvidencePruner = CloudPendingEvidencePruner(evidence, FakeCredentials(ACCOUNT), settings),
        )
        val observed = listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 130, 0))
        try {
            database.withTransaction {
                committer.commit(observed, endAt, endAt,
                    CloudPresencePlaytimePlacement.Input(firstRead.intervals, firstRead.readerIdentity),
                    pruningIdentity = firstRead.readerIdentity)
                error("simulate failed Steam commit")
            }
        } catch (_: IllegalStateException) { /* Room rolled back the baseline and prune. */ }
        assertEquals(1, evidence.intervals(ACCOUNT, 0L).count { it.appId == 10L })
        assertEquals(100, database.gameDao().getById(10L)!!.lastPlaytime)

        // Another no-delta baseline may advance before this retry. Evidence past it stays,
        // but the retry must not attribute minutes to the interval's earlier half.
        database.withTransaction {
            committer.commit(listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 100, 0)),
                nextBaselineAt, nextBaselineAt)
            database.playerProfileDao().updateSyncStatus(nextBaselineAt, null)
        }
        assertEquals(1, evidence.intervals(ACCOUNT, 0L).count { it.appId == 10L })
        val retry = placement.read(CloudReadTrigger.SYNC, nextBaselineAt, endAt)!!
        assertEquals(Instant.parse(opening).toEpochMilli(),
            retry.intervals.first { it.appId == 10L }.startAt)
        database.withTransaction {
            committer.commit(observed, endAt, endAt,
                CloudPresencePlaytimePlacement.Input(retry.intervals, retry.readerIdentity),
                pruningIdentity = retry.readerIdentity)
            database.playerProfileDao().updateSyncStatus(endAt, null)
        }
        val sessions = database.sessionDao().getAll().filter { it.appId == 10L }
        assertEquals(30, sessions.sumOf { it.minutes })
        assertTrue(sessions.all { it.startAt >= nextBaselineAt })
        assertTrue(evidence.intervals(ACCOUNT, 0L).none { it.appId == 10L })
    }

    @Test
    fun terminalOngoingBoundaryIsRefinedByCloseOnlyPageAcrossTwoSteamDeltas() = runBlocking {
        val start = "2026-09-15T00:00:00Z"
        val opening = "2026-09-15T00:10:00Z"
        val firstBaseline = "2026-09-15T00:30:00Z"
        val closing = "2026-09-15T00:50:00Z"
        val secondBaseline = "2026-09-15T01:00:00Z"
        val openingPage = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(CloudPresenceTransitionDto(v = 3, t = opening,
                gameid = "10", gameName = "Owned", personastate = 1)),
            current = CloudPresenceCurrentDto(v = 2, lastObservedAt = firstBaseline,
                since = opening, gameid = "10", gameName = "Owned", personastate = 1),
            nextPosition = opening, hasMore = false, windowStart = start,
            windowEnd = firstBaseline, readAt = firstBaseline,
        )
        val closingPage = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(CloudPresenceTransitionDto(v = 3, t = closing,
                gameid = "20", gameName = "Other", personastate = 1,
                prevLastObservedAt = "2026-09-15T00:49:00Z")),
            current = CloudPresenceCurrentDto(v = 2, lastObservedAt = secondBaseline,
                gameid = "20", gameName = "Other", personastate = 1),
            nextPosition = closing, hasMore = false, windowStart = firstBaseline,
            windowEnd = secondBaseline, readAt = secondBaseline,
        )
        val api = FakeCloudPresenceApi { position -> when (position) {
            null -> openingPage
            opening -> closingPage
            closing -> closingPage.copy(transitions = emptyList(), windowStart = closing)
            else -> error("Unexpected position $position")
        } }
        val settings = FakeSettingsRepository()
        val (reader, placement) = readerPair(api, settings)
        val evidence = database.pendingCloudEvidenceDao()
        database.gameDao().upsert(Game(10L, "Owned", "", 100, 0, 100))
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateSyncStatus(Instant.parse(start).toEpochMilli(), null)

        assertTrue(reader.read() is CloudReadResult.Success)
        val provisional = evidence.intervals(ACCOUNT, 0L).single { it.appId == 10L }
        assertTrue(provisional.ongoing)
        assertEquals(Instant.parse(opening).toEpochMilli(), provisional.startAt)
        assertTrue(reader.read() is CloudReadResult.Success)
        val closed = evidence.intervals(ACCOUNT, 0L).filter { it.appId == 10L }
        assertEquals(1, closed.size)
        assertFalse(closed.single().ongoing)
        assertEquals(Instant.parse(closing).toEpochMilli(), closed.single().endAt)

        val committer = PlaytimeObservationCommitter(
            gameDao = database.gameDao(), sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(), profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(), differ = SessionDiffer(), time = FixedTimeProvider(),
            sessionActionWriter = SessionActionWriter(database.sessionDao(), database.dailyProgressDao(),
                database.hiddenGameDao(), FixedTimeProvider()),
            pendingEvidencePruner = CloudPendingEvidencePruner(evidence, FakeCredentials(ACCOUNT), settings),
        )
        val firstAt = Instant.parse(firstBaseline).toEpochMilli()
        val secondAt = Instant.parse(secondBaseline).toEpochMilli()
        val firstEvidence = placement.read(CloudReadTrigger.SYNC, Instant.parse(start).toEpochMilli(), firstAt)!!
        database.withTransaction {
            committer.commit(listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 120, 0)),
                firstAt, firstAt,
                CloudPresencePlaytimePlacement.Input(firstEvidence.intervals, firstEvidence.readerIdentity),
                pruningIdentity = firstEvidence.readerIdentity)
            database.playerProfileDao().updateSyncStatus(firstAt, null)
        }
        assertEquals(1, evidence.intervals(ACCOUNT, 0L).count { it.appId == 10L })

        val secondEvidence = placement.read(CloudReadTrigger.SYNC, firstAt, secondAt)!!
        assertEquals(1, secondEvidence.intervals.count { it.appId == 10L })
        val secondCommit = database.withTransaction {
            val commit = committer.commit(
                listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 140, 0)),
                secondAt, secondAt,
                CloudPresencePlaytimePlacement.Input(secondEvidence.intervals, secondEvidence.readerIdentity),
                pruningIdentity = secondEvidence.readerIdentity,
            )
            database.playerProfileDao().updateSyncStatus(secondAt, null)
            commit
        }
        assertEquals(20, secondCommit.playedDeltaByAppId[10L])
        assertEquals(40, database.sessionDao().getAll().filter { it.appId == 10L }.sumOf { it.minutes })
        assertTrue(evidence.intervals(ACCOUNT, 0L).none { it.appId == 10L })
    }

    @Test
    fun zeroDeltaBaselinePrunesOnlyClosedIntervalsThatCannotEnterFutureWindows() = runBlocking {
        val origin = Instant.parse("2026-09-15T00:00:00Z").toEpochMilli()
        val minute = 60_000L
        val first = CloudPresenceInterval(
            appId = 10L, gameName = "Owned", startAt = origin + 10 * minute,
            endAt = origin + 30 * minute, ongoing = false,
            coverage = CloudCoverageState.CONTINUOUS, observedUntil = null,
            coverageLapseFrom = null, coverageLapseRecoveredAt = null, mayHaveStartedBefore = false,
        )
        val future = first.copy(startAt = origin + 40 * minute, endAt = origin + 70 * minute)
        val settings = FakeSettingsRepository()
        val pending = RoomCloudPendingEvidence(database.pendingCloudEvidenceDao(), database.gameDao(),
            RoomDatabaseTransactionScope(database))
        database.gameDao().upsert(Game(10L, "Owned", "", 100, 0, 100))
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateSyncStatus(origin + minute, null)
        pending.retain(ACCOUNT, 0L, origin, listOf(first, future), null)
        val dao = database.pendingCloudEvidenceDao()
        val committer = PlaytimeObservationCommitter(
            gameDao = database.gameDao(), sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(), profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(), differ = SessionDiffer(), time = FixedTimeProvider(),
            sessionActionWriter = SessionActionWriter(database.sessionDao(), database.dailyProgressDao(),
                database.hiddenGameDao(), FixedTimeProvider()),
            pendingEvidencePruner = CloudPendingEvidencePruner(dao, FakeCredentials(ACCOUNT), settings),
        )
        val at50 = origin + 50 * minute
        val at80 = origin + 80 * minute
        committer.withValidatedPlacement(null) { _, pruningIdentity ->
            database.withTransaction {
                val result = committer.commit(
                    listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 100, 0)),
                    at50, at50, pruningIdentity = pruningIdentity,
                )
                assertFalse(result.recordedPlay)
                database.playerProfileDao().updateSyncStatus(at50, null)
            }
        }
        assertEquals(listOf(future.startAt), dao.intervals(ACCOUNT, 0L).map { it.startAt })

        val terminalAt = Instant.ofEpochMilli(at80).toString()
        val (_, placement) = readerPair(FakeCloudPresenceApi { position ->
            assertNull(position)
            CloudPresenceResponseDto(
                account = ACCOUNT, transitions = emptyList(), current = null,
                nextPosition = terminalAt, hasMore = false,
                windowStart = Instant.ofEpochMilli(at50).toString(),
                windowEnd = terminalAt, readAt = terminalAt,
            )
        }, settings, pending)
        val snapshot = placement.read(CloudReadTrigger.SYNC, at50, at80)!!
        assertEquals(listOf(future.startAt), snapshot.intervals.filter { it.appId == 10L }.map { it.startAt })
        committer.withValidatedPlacement(null) { _, pruningIdentity ->
            database.withTransaction {
                committer.commit(
                    listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 100, 0)),
                    at80, at80, pruningIdentity = pruningIdentity,
                )
                database.playerProfileDao().updateSyncStatus(at80, null)
            }
        }
        assertTrue(dao.intervals(ACCOUNT, 0L).none { it.appId == 10L })
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
    fun periodicCommitRejectsPlacementFromReaderGenerationPromotedAfterAcquisition() = runBlocking {
        val startAt = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli()
        val placementStart = startAt + 60L * 60 * 1_000
        val placementEnd = placementStart + 30L * 60 * 1_000
        val endAt = Instant.parse("2026-09-18T00:00:00Z").toEpochMilli()
        val opening = Instant.ofEpochMilli(placementStart).toString()
        val closing = Instant.ofEpochMilli(placementEnd).toString()
        val terminal = CloudPresenceResponseDto(
            account = ACCOUNT,
            transitions = listOf(
                CloudPresenceTransitionDto(v = 3, t = opening, gameid = "10", gameName = "Owned", personastate = 1),
                CloudPresenceTransitionDto(v = 3, t = closing, gameid = "20", gameName = "Other", personastate = 1,
                    prevLastObservedAt = Instant.ofEpochMilli(placementEnd - 60_000L).toString()),
            ),
            current = CloudPresenceCurrentDto(v = 2, lastObservedAt = Instant.ofEpochMilli(endAt).toString(),
                gameid = "20", gameName = "Other", personastate = 1),
            nextPosition = closing,
            hasMore = false,
            windowStart = Instant.ofEpochMilli(startAt).toString(),
            windowEnd = Instant.ofEpochMilli(endAt).toString(),
            readAt = Instant.ofEpochMilli(endAt + 60_000L).toString(),
        )
        val settings = FakeSettingsRepository()
        val pending = RoomCloudPendingEvidence(
            database.pendingCloudEvidenceDao(), database.gameDao(), RoomDatabaseTransactionScope(database),
        )
        database.gameDao().upsert(Game(10L, "Owned", "", 100, 0, 100))
        database.playerProfileDao().insertIfMissing()
        database.playerProfileDao().updateSyncStatus(startAt, null)
        val placementReader = readerPair(
            FakeCloudPresenceApi { position ->
                assertNull(position)
                terminal
            },
            settings,
            pending,
        ).second

        val acquired = placementReader.read(CloudReadTrigger.SYNC, startAt, endAt)!!
        assertEquals(CloudReaderIdentity(ACCOUNT, 0L), acquired.readerIdentity)
        // Verification promotes the replacement reader and retains its closed interval before
        // this worker reaches the Steam transaction.
        settings.simulateLegacyGenerationAdvance()
        val replacement = acquired.intervals.single { it.appId == 10L }.copy(
            startAt = placementStart + 60L * 60 * 1_000,
            endAt = placementEnd + 60L * 60 * 1_000,
        )
        pending.retain(ACCOUNT, 1L, startAt, listOf(replacement), null)

        val committer = PlaytimeObservationCommitter(
            gameDao = database.gameDao(), sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(), profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(), differ = SessionDiffer(),
            time = FixedTimeProvider(), sessionActionWriter = SessionActionWriter(
                database.sessionDao(), database.dailyProgressDao(), database.hiddenGameDao(), FixedTimeProvider(),
            ),
            pendingEvidencePruner = CloudPendingEvidencePruner(
                database.pendingCloudEvidenceDao(), FakeCredentials(ACCOUNT), settings,
            ),
        )
        val commit = committer.withValidatedPlacement(
            CloudPresencePlaytimePlacement.Input(acquired.intervals, acquired.readerIdentity),
        ) { validatedPlacement, pruningIdentity ->
            database.withTransaction {
                val result = committer.commit(
                    observed = listOf(PlaytimeObservationCommitter.ObservedGame(10L, "Owned", "", 130, 0)),
                    observedPlayAt = endAt,
                    syncedAt = endAt,
                    placement = validatedPlacement,
                    pruningIdentity = pruningIdentity,
                )
                database.playerProfileDao().updateSyncStatus(endAt, null)
                result
            }
        }

        val session = database.sessionDao().getAll().single { it.appId == 10L }
        assertEquals(30, commit.playedDeltaByAppId[10L])
        assertEquals(30, session.minutes)
        assertEquals(TimingInformedSteamPlayState.NONE, session.timingInformedSteamPlay)
        assertTrue(session.startAt != placementStart)
        assertEquals(1, database.pendingCloudEvidenceDao().intervals(ACCOUNT, 1L).size)
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
    ): RepositoryCloudPresencePlacementReader = readerPair(api, settings).second

    private fun readerPair(
        api: CloudPresenceApi,
        settings: FakeSettingsRepository,
        pending: CloudPendingEvidence? = null,
    ): Pair<CloudPresenceRepository, RepositoryCloudPresencePlacementReader> {
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
            pendingEvidence = pending ?: RoomCloudPendingEvidence(
                database.pendingCloudEvidenceDao(), database.gameDao(), RoomDatabaseTransactionScope(database),
            ),
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
        return repository to RepositoryCloudPresencePlacementReader(repository, ingestor)
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
