package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CloudCoverageState
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
