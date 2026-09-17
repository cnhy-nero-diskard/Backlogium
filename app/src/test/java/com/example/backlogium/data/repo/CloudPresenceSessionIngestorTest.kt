package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceCurrentState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.FakeHiddenGameDao
import com.example.backlogium.domain.FakeSettingsRepository
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.InMemoryProgressMarksStore
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.ProgressMarksStore
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class CloudPresenceSessionIngestorTest {
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
    fun sameWindowIsPositionedOnceAndCreditsNoAdditionalSession() = runTest {
        database.gameDao().upsert(sharedGame())
        val settings = FakeSettingsRepository()
        val ingestor = ingestor(settings)
        val snapshot = snapshot(endAt = 120_000L)

        val first = ingestor.ingest(snapshot)
        val second = ingestor.ingest(snapshot)

        assertTrue(first.processed)
        assertTrue(first.wrote)
        assertFalse(second.processed)
        val stored = database.sessionDao().getAll().single()
        assertEquals(1, database.sessionDao().getAll().size)
        assertEquals(0L, stored.startAt)
        assertEquals(120_000L, stored.endAt)
        assertEquals(2, stored.minutes)
        assertFalse(stored.open)
        assertEquals("120000", settings.cloudIngestPosition.first())
    }

    @Test
    fun nonterminalPagePersistsTheServerPositionRatherThanWindowEnd() = runTest {
        database.gameDao().upsert(sharedGame())
        val settings = FakeSettingsRepository()
        val position = java.time.Instant.ofEpochMilli(120_000L).toString()

        val result = ingestor(settings).ingest(
            snapshot(endAt = 120_000L).copy(
                windowEnd = 900_000L,
                nextPosition = position,
                hasMore = true,
            ),
        )

        assertTrue(result.processed)
        assertEquals(position, settings.cloudIngestPosition.first())
    }

    @Test
    fun positionedWindowDoesNotRecompute() = runTest {
        database.gameDao().upsert(sharedGame())
        val settings = FakeSettingsRepository()
        settings.setCloudIngestPosition(120000L.toString())

        val result = ingestor(settings).ingest(snapshot(endAt = 120_000L))

        assertFalse(result.processed)
        assertFalse(result.wrote)
        assertNull(database.playerProfileDao().get())
        assertTrue(database.sessionDao().getAll().isEmpty())
    }

    @Test
    fun cloudOverlapExtendsTheExistingPresenceSessionOnce() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 0L,
                endAt = 600_000L,
                minutes = 10,
                open = true,
            ),
        )
        val settings = FakeSettingsRepository()
        val ingestor = ingestor(settings)

        val result = ingestor.ingest(snapshot(endAt = 1_200_000L))
        val stored = database.sessionDao().getAll()

        assertTrue(result.wrote)
        assertEquals(1, stored.size)
        assertEquals(20, stored.single().minutes)
        assertFalse(stored.single().open)
    }

    @Test
    fun fullyCoveredOngoingCloudIntervalDoesNotCloseLiveSession() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 0L,
                endAt = 600_000L,
                minutes = 10,
                open = true,
            ),
        )
        // A verification read while the same game is still running reconstructs the live
        // state as an ongoing interval fully covered by the open session. It proves no
        // switch, so ingesting it must leave the live session alone rather than folding
        // a synthetic null boundary into it and closing it out of order.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 0L,
            windowEnd = 600_000L,
            readAt = 600_001L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 440L,
                    gameName = "Shared",
                    startAt = 0L,
                    endAt = 600_000L,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 2,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertFalse(result.wrote)
        val stored = database.sessionDao().getAll().single()
        assertEquals(0L, stored.startAt)
        assertEquals(600_000L, stored.endAt)
        assertEquals(10, stored.minutes)
        assertTrue(stored.open)
    }

    @Test
    fun fullyStoredHistoryDoesNotCloseNewerLiveSession() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 0L,
                endAt = 2L * MINUTE,
                minutes = 2,
                open = false,
            ),
        )
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        // An older interval already stored emits only a synthetic null boundary and no game
        // observation, so the ingest still seeds the newer live open. Folding that earlier
        // boundary from the open would out-of-order close the live session. The later fully
        // stored ongoing interval emits nothing and cannot repair it, so the earlier
        // boundary must be dropped: the live session stays open and nothing is written.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 0L,
            windowEnd = 12L * MINUTE,
            readAt = 12L * MINUTE + 1L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 440L,
                    gameName = "Shared",
                    startAt = 0L,
                    endAt = 2L * MINUTE,
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
                CloudPresenceInterval(
                    appId = 440L,
                    gameName = "Shared",
                    startAt = 10L * MINUTE,
                    endAt = 12L * MINUTE,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 3,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertFalse(result.wrote)
        val stored = database.sessionDao().getAll()
        assertEquals(2, stored.size)
        val history = stored.single { !it.open }
        assertEquals(0L, history.startAt)
        assertEquals(2L * MINUTE, history.endAt)
        assertEquals(2, history.minutes)
        val live = stored.single { it.open }
        assertEquals(10L * MINUTE, live.startAt)
        assertEquals(12L * MINUTE, live.endAt)
        assertEquals(2, live.minutes)
        assertTrue(live.open)
    }

    @Test
    fun uncoveredHistoryFollowedByFullyCoveredOngoingStillClosesPredecessor() = runTest {
        database.gameDao().upsert(sharedGame())
        database.gameDao().upsert(sharedGame().copy(appId = 441L, name = "Shared B"))
        database.sessionDao().insert(
            Session(
                appId = 441L,
                startAt = 4L * MINUTE,
                endAt = 8L * MINUTE,
                minutes = 4,
                open = true,
            ),
        )
        // Cloud A [0, 4m] is new play, B [4m, 8m ongoing] is already stored as the live
        // session. The ongoing interval still proves the A->B switch, so ingest must
        // close A rather than leaving it open alongside B.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 0L,
            windowEnd = 8L * MINUTE,
            readAt = 8L * MINUTE + 1L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 440L,
                    gameName = "Shared",
                    startAt = 0L,
                    endAt = 4L * MINUTE,
                    ongoing = false,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
                CloudPresenceInterval(
                    appId = 441L,
                    gameName = "Shared B",
                    startAt = 4L * MINUTE,
                    endAt = 8L * MINUTE,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 3,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertTrue(result.wrote)
        val stored = database.sessionDao().getAll()
        assertEquals(2, stored.size)
        val recovered = stored.single { it.appId == 440L }
        assertEquals(0L, recovered.startAt)
        assertEquals(4L * MINUTE, recovered.endAt)
        assertEquals(4, recovered.minutes)
        assertFalse(recovered.open)
        val live = stored.filter { it.appId == 441L }
        assertEquals(1, live.size)
        assertEquals(4L * MINUTE, live.single().startAt)
        assertEquals(8L * MINUTE, live.single().endAt)
        assertEquals(4, live.single().minutes)
        assertTrue(live.single().open)
    }

    @Test
    fun fullyCoveredOngoingDifferentAppClosesSeededOpen() = runTest {
        database.gameDao().upsert(sharedGame())
        database.gameDao().upsert(sharedGame().copy(appId = 441L, name = "Shared B"))
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        database.sessionDao().insert(
            Session(
                appId = 441L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = false,
            ),
        )
        // Room says A is live, but the cloud snapshot starts with fully covered ongoing B:
        // with no preceding admitted fragment the batch alone proves no switch, yet the
        // seeded Room open is also preceding state. The ongoing B still proves A is stale,
        // so ingest must close A without duplicating or losing the stored B span, and the
        // fully covered ongoing app must itself read open afterwards: the cloud still
        // reports B running, so leaving both rows closed would leave no live session.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 10L * MINUTE,
            windowEnd = 12L * MINUTE,
            readAt = 12L * MINUTE + 1L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 441L,
                    gameName = "Shared B",
                    startAt = 10L * MINUTE,
                    endAt = 12L * MINUTE,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 2,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertTrue(result.wrote)
        assertEquals(0, result.creditedMinutes)
        val stored = database.sessionDao().getAll()
        assertEquals(2, stored.size)
        val seeded = stored.single { it.appId == 440L }
        assertEquals(10L * MINUTE, seeded.startAt)
        assertEquals(12L * MINUTE, seeded.endAt)
        assertEquals(2, seeded.minutes)
        assertFalse(seeded.open)
        val current = stored.filter { it.appId == 441L }
        assertEquals(1, current.size)
        assertEquals(10L * MINUTE, current.single().startAt)
        assertEquals(12L * MINUTE, current.single().endAt)
        assertEquals(2, current.single().minutes)
        assertTrue(current.single().open)
    }

    @Test
    fun bothOpenStaleAndCurrentReconcilesToCurrent() = runTest {
        database.gameDao().upsert(sharedGame())
        database.gameDao().upsert(sharedGame().copy(appId = 441L, name = "Shared B"))
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        database.sessionDao().insert(
            Session(
                appId = 441L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        // Both A (stale) and B (current) are already open, so the fold seeds only one of
        // them: whichever single open row happened to be selected, the other would survive
        // without current-state reconciliation. The ongoing B proves A stale, so ingest must
        // close A and keep B open without duplicating either span.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 10L * MINUTE,
            windowEnd = 12L * MINUTE,
            readAt = 12L * MINUTE + 1L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 441L,
                    gameName = "Shared B",
                    startAt = 10L * MINUTE,
                    endAt = 12L * MINUTE,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = false,
                ),
            ),
            current = null,
            observationCount = 2,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertTrue(result.wrote)
        assertEquals(0, result.creditedMinutes)
        val stored = database.sessionDao().getAll()
        assertEquals(2, stored.size)
        val stale = stored.single { it.appId == 440L }
        assertFalse(stale.open)
        val current = stored.single { it.appId == 441L }
        assertEquals(10L * MINUTE, current.startAt)
        assertEquals(12L * MINUTE, current.endAt)
        assertEquals(2, current.minutes)
        assertTrue(current.open)
    }

    @Test
    fun terminalNoGameCurrentClosesStaleSharedSession() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 0L,
                endAt = 2L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        // A terminal read with no new transitions reconstructs zero intervals, but its
        // null-app current still proves the user is not in a game at its observation time,
        // so the still-open shared session must close without adding minutes.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 0L,
            windowEnd = 5L * MINUTE,
            readAt = 5L * MINUTE + 1L,
            intervals = emptyList(),
            current = CloudPresenceCurrentState(
                observedAt = 5L * MINUTE,
                appId = null,
                gameName = null,
                personastate = null,
                since = null,
                coverageLapseFrom = null,
                coverageLapseRecoveredAt = null,
                schemaVersion = null,
            ),
            observationCount = 0,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertTrue(result.wrote)
        assertEquals(0, result.creditedMinutes)
        val stored = database.sessionDao().getAll().single()
        assertEquals(0L, stored.startAt)
        assertEquals(2L * MINUTE, stored.endAt)
        assertEquals(2, stored.minutes)
        assertFalse(stored.open)
    }

    @Test
    fun terminalOwnedCurrentClosesStaleSharedSessionWithoutTouchingOwned() = runTest {
        database.gameDao().upsert(sharedGame())
        database.gameDao().upsert(sharedGame().copy(appId = 620L, name = "Owned", source = GameSource.STEAM_OWNED))
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        // Room holds open shared A last-observed at 12m while the terminal current proves
        // owned B has been running since 10m as observed at 15m. The owned interval expands
        // to a null boundary at 10m that the pre-seed filter drops as older than the seed,
        // so only the terminal current evidence can close A — without writing B itself.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 10L * MINUTE,
            windowEnd = 15L * MINUTE,
            readAt = 15L * MINUTE + 1L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 620L,
                    gameName = "Owned",
                    startAt = 10L * MINUTE,
                    endAt = 15L * MINUTE,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = true,
                ),
            ),
            current = CloudPresenceCurrentState(
                observedAt = 15L * MINUTE,
                appId = 620L,
                gameName = "Owned",
                personastate = null,
                since = 10L * MINUTE,
                coverageLapseFrom = null,
                coverageLapseRecoveredAt = null,
                schemaVersion = null,
            ),
            observationCount = 0,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertTrue(result.wrote)
        assertEquals(0, result.creditedMinutes)
        val stored = database.sessionDao().getAll()
        assertEquals(1, stored.size)
        val stale = stored.single()
        assertEquals(440L, stale.appId)
        assertEquals(10L * MINUTE, stale.startAt)
        assertEquals(12L * MINUTE, stale.endAt)
        assertEquals(2, stale.minutes)
        assertFalse(stale.open)
    }

    @Test
    fun terminalUnknownCurrentClosesStaleSharedSessionWithoutWritingUnknown() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 10L * MINUTE,
                endAt = 12L * MINUTE,
                minutes = 2,
                open = true,
            ),
        )
        // Same stale-open proof as the owned case, but the current game is unknown to the
        // library: it still acts as switch evidence for the shared open and still gains
        // no session of its own.
        val snapshot = CloudPresenceSnapshot(
            windowStart = 10L * MINUTE,
            windowEnd = 15L * MINUTE,
            readAt = 15L * MINUTE + 1L,
            intervals = listOf(
                CloudPresenceInterval(
                    appId = 730L,
                    gameName = "Unknown",
                    startAt = 10L * MINUTE,
                    endAt = 15L * MINUTE,
                    ongoing = true,
                    coverage = CloudCoverageState.CONTINUOUS,
                    observedUntil = null,
                    coverageLapseFrom = null,
                    coverageLapseRecoveredAt = null,
                    mayHaveStartedBefore = true,
                ),
            ),
            current = CloudPresenceCurrentState(
                observedAt = 15L * MINUTE,
                appId = 730L,
                gameName = "Unknown",
                personastate = null,
                since = 10L * MINUTE,
                coverageLapseFrom = null,
                coverageLapseRecoveredAt = null,
                schemaVersion = null,
            ),
            observationCount = 0,
            nextPosition = null,
            hasMore = false,
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot)

        assertTrue(result.processed)
        assertTrue(result.wrote)
        assertEquals(0, result.creditedMinutes)
        val stored = database.sessionDao().getAll()
        assertEquals(1, stored.size)
        val stale = stored.single()
        assertEquals(440L, stale.appId)
        assertEquals(10L * MINUTE, stale.startAt)
        assertEquals(12L * MINUTE, stale.endAt)
        assertEquals(2, stale.minutes)
        assertFalse(stale.open)
    }

    @Test
    fun retryAfterSessionWriteBeforeCursorAdvanceDoesNotRecredit() = runTest {
        database.gameDao().upsert(sharedGame())
        val settings = FakeSettingsRepository()
        val ingestor = ingestor(settings)
        val snapshot = snapshot(endAt = 120_000L)

        ingestor.ingest(snapshot)
        // Simulate the process ending after the Room write but before the cursor edit.
        settings.clearCloudIngestPosition()
        val retry = ingestor.ingest(snapshot)

        assertFalse(retry.wrote)
        assertEquals(1, database.sessionDao().getAll().size)
        assertEquals(2, database.sessionDao().getAll().single().minutes)
    }

    @Test
    fun deviceAndCloudOverlapIsCreditedOnce() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 0L,
                endAt = 120_000L,
                minutes = 2,
                open = false,
            ),
        )
        database.dailyProgressDao().upsert(DailyProgress(LocalDate.of(1970, 1, 1).toString(), 2, 0, false))

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot(endAt = 120_000L))

        assertTrue(result.processed)
        assertFalse(result.wrote)
        assertEquals(1, database.sessionDao().getAll().size)
        assertEquals(2, database.sessionDao().getAll().single().minutes)
        assertEquals(2, database.dailyProgressDao().getByDate(LocalDate.of(1970, 1, 1).toString())!!.minutesPlayed)
        assertNull(database.playerProfileDao().get())
    }

    @Test
    fun olderCloudGapIsRecoveredDespiteNewerLocalSession() = runTest {
        database.gameDao().upsert(sharedGame())
        database.sessionDao().insert(
            Session(
                appId = 440L,
                startAt = 600_000L,
                endAt = 720_000L,
                minutes = 2,
                open = false,
            ),
        )
        database.dailyProgressDao().upsert(DailyProgress(LocalDate.of(1970, 1, 1).toString(), 2, 0, false))

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot(startAt = 0L, endAt = 120_000L))

        assertTrue(result.processed)
        assertTrue(result.wrote)
        assertEquals(2, result.creditedMinutes)
        assertEquals(2, database.sessionDao().getAll().size)
        val recovered = database.sessionDao().getAll().first { it.startAt == 0L }
        assertEquals(120_000L, recovered.endAt)
        assertEquals(2, recovered.minutes)
        assertFalse(recovered.open)
        val newer = database.sessionDao().getAll().first { it.startAt == 600_000L }
        assertEquals(720_000L, newer.endAt)
        assertEquals(2, newer.minutes)
        assertEquals(4, database.dailyProgressDao().getByDate(LocalDate.of(1970, 1, 1).toString())!!.minutesPlayed)
    }

    @Test
    fun ownedGameSessionIsUntouchedByCloudIngest() = runTest {
        val owned = sharedGame().copy(appId = 620L, source = GameSource.STEAM_OWNED)
        database.gameDao().upsert(owned)
        database.sessionDao().insert(
            Session(
                appId = owned.appId,
                startAt = 0L,
                endAt = 120_000L,
                minutes = 2,
                open = false,
            ),
        )

        val result = ingestor(FakeSettingsRepository()).ingest(snapshot(appId = owned.appId, endAt = 120_000L))

        assertTrue(result.processed)
        assertFalse(result.wrote)
        val stored = database.sessionDao().getAll().single()
        assertEquals(owned.appId, stored.appId)
        assertEquals(0L, stored.startAt)
        assertEquals(120_000L, stored.endAt)
        assertEquals(2, stored.minutes)
        assertFalse(stored.open)
    }

    @Test
    fun recoveredPastPlayReevaluatesQuestAndRepairsTheStreak() = runTest {
        database.gameDao().upsert(sharedGame())
        val dayOne = LocalDate.of(2026, 9, 12)
        val recoveredDay = LocalDate.of(2026, 9, 13)
        val dayThree = LocalDate.of(2026, 9, 14)
        database.dailyProgressDao().upsert(DailyProgress(dayOne.toString(), 30, 0, true))
        database.dailyProgressDao().upsert(DailyProgress(recoveredDay.toString(), 0, 0, false))
        database.dailyProgressDao().upsert(DailyProgress(dayThree.toString(), 30, 0, true))
        database.playerProfileDao().upsert(PlayerProfile(level = 1))
        val marks = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 1, initialized = true),
        )
        val ingestor = ingestor(FakeSettingsRepository(), marks)
        val startAt = recoveredDay.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val cloudSnapshot = snapshot(startAt = startAt, endAt = startAt + 30L * MINUTE)

        val result = ingestor.ingest(cloudSnapshot)

        assertTrue(result.wrote)
        assertEquals(30, result.creditedMinutes)
        assertEquals(30, database.sessionDao().getAll().single().minutes)
        val recoveredProgress = database.dailyProgressDao().getByDate(recoveredDay.toString())!!
        assertEquals(30, recoveredProgress.minutesPlayed)
        assertTrue(recoveredProgress.questMet)
        assertEquals(3, database.playerProfileDao().get()!!.currentStreak)
        assertTrue(database.playerProfileDao().get()!!.totalXp > 0L)
        assertEquals(1, database.sessionDao().observeBetween(startAt, startAt + 24L * 60 * MINUTE).first().size)
        assertEquals(30, database.sessionDao().observeMinutesByGameSince(startAt).first().single().minutes)
        assertTrue(marks.read().pendingQuestDates.isEmpty())
        assertNull(marks.read().pendingStreakBreak)
    }

    private fun ingestor(
        settings: FakeSettingsRepository,
        marksStore: ProgressMarksStore = InMemoryProgressMarksStore(),
    ) = CloudPresenceSessionIngestor(
        gameDao = database.gameDao(),
        sessionDao = database.sessionDao(),
        sessionActionWriter = SessionActionWriter(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hiddenGameDao = database.hiddenGameDao(),
            time = FixedTime,
            transaction = RoomDatabaseTransactionScope(database),
        ),
        gamificationUpdater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = FakeHiddenGameDao(),
            progressMarksStore = marksStore,
        ),
        settings = settings,
        derivedStateWrites = DerivedStateWriteCoordinator(),
        time = FixedTime,
    )

    private fun sharedGame() = Game(
        appId = 440L,
        name = "Shared",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
        source = GameSource.FAMILY_SHARED,
    )

    private fun snapshot(appId: Long = 440L, startAt: Long = 0L, endAt: Long) = CloudPresenceSnapshot(
        windowStart = startAt,
        windowEnd = endAt,
        readAt = endAt + 1L,
        intervals = listOf(
            CloudPresenceInterval(
                appId = appId,
                gameName = "Shared",
                startAt = startAt,
                endAt = endAt,
                ongoing = false,
                coverage = CloudCoverageState.CONTINUOUS,
                observedUntil = null,
                coverageLapseFrom = null,
                coverageLapseRecoveredAt = null,
                mayHaveStartedBefore = false,
            ),
        ),
        current = null,
        observationCount = 2,
        nextPosition = null,
        hasMore = false,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 1_750_000_000_000L
        override fun zone() = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 9, 15)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
