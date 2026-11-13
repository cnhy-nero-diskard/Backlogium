package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PlaytimeBackfillResetResult
import com.example.backlogium.domain.PlaytimeBackfillUseCase
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * Frozen lifetime-backfill subtraction against the canonical credited quantity
 * (recover-missed-playtime 5.2/design.md §6-7): a recovery bucket is never double-counted against
 * the opt-in Steam-history offset, and the imported figure stays frozen afterwards.
 */
@RunWith(RobolectricTestRunner::class)
class PlaytimeBackfillRecoveryTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var settings: SettingsDataStore

    private val zone = ZoneId.of("UTC")
    private val account = "76561198000000001"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = SettingsDataStore(RuntimeEnvironment.getApplication())
        com.example.backlogium.data.credentials.AccountChangeMarkerStore(RuntimeEnvironment.getApplication()).clear()
        db.playerProfileDao().upsert(com.example.backlogium.data.local.entity.PlayerProfile(steamId = account, lastSyncAt = 1L))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun backfillSubtractsCanonicalCreditedMinutesIncludingRecoveredAllocations() = runTest {
        val appId = 10L
        db.gameDao().upsertAll(listOf(game(appId, playtimeForever = 120)))
        db.sessionDao().insert(session(appId, LocalDate.of(2026, 6, 6), minutes = 40))
        // 30 recovered minutes that live only as a dated allocation, not a session.
        db.playtimeRecoveryDao().insertContribution(contribution("c-backfill", appId, authorized = 30))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a-backfill", "c-backfill", appId, LocalDate.of(2026, 6, 7), 30)),
        )

        val useCase = useCase()
        assertTrue(useCase())

        // tracked = 40 session + 30 recovered = 70 credited; backfill = 120 - 70 = 50.
        assertEquals(50, db.gameDao().getById(appId)!!.backfillMinutes)
        assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
        // The recompute fed the canonical 70 credited minutes (not raw 40): 50 backfill + 70
        // credited = 120 cumulative, so the XP engine saw the full Steam total.
        assertTrue(db.playerProfileDao().get()!!.totalXp > 0L)
    }

    @Test
    fun importFreezesTheOffsetAndLaterRecoveryCannotReimport() = runTest {
        val appId = 20L
        db.gameDao().upsertAll(listOf(game(appId, playtimeForever = 90)))
        db.sessionDao().insert(session(appId, LocalDate.of(2026, 6, 6), minutes = 40))
        db.playtimeRecoveryDao().insertContribution(contribution("c-freeze", appId, authorized = 30))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a-freeze", "c-freeze", appId, LocalDate.of(2026, 6, 7), 30)),
        )

        val useCase = useCase()
        assertTrue(useCase())
        assertEquals(90 - 70, db.gameDao().getById(appId)!!.backfillMinutes)

        // A second run is a no-op and does not re-freeze against a changed total.
        assertFalse(useCase())
        assertEquals(20, db.gameDao().getById(appId)!!.backfillMinutes)
    }

    @Test
    fun resetClearsTheFrozenOffsetAndItsRecoveryAwareRecompute() = runTest {
        val appId = 30L
        db.gameDao().upsertAll(listOf(game(appId, playtimeForever = 60)))
        db.sessionDao().insert(session(appId, LocalDate.of(2026, 6, 6), minutes = 40))
        db.playtimeRecoveryDao().insertContribution(contribution("c-reset", appId, authorized = 20))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a-reset", "c-reset", appId, LocalDate.of(2026, 6, 7), 20)),
        )

        val useCase = useCase()
        assertTrue(useCase())
        assertEquals(60 - 60, db.gameDao().getById(appId)!!.backfillMinutes)

        assertEquals(PlaytimeBackfillResetResult.RESET, useCase.reset())
        assertEquals(0, db.gameDao().getById(appId)!!.backfillMinutes)
        assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
    }

    @Test
    fun noSnapshotCannotBeImportedButAConfirmedEmptySnapshotCan() = runTest {
        val profile = db.playerProfileDao().get()!!
        db.playerProfileDao().upsert(profile.copy(lastSyncAt = 0L))
        assertTrue(runCatching { useCase()() }.exceptionOrNull() is IllegalStateException)
        assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
        db.playerProfileDao().upsert(profile)
        assertTrue(useCase()())
        assertTrue(db.playerProfileDao().get()!!.playtimeBackfilled)
        assertEquals(0, db.playerProfileDao().get()!!.totalXp.toInt())
    }

    @Test
    fun failedRawCaptureRollsBackOffsetsFlagAndPendingMarker() = runTest {
        db.gameDao().upsertAll(listOf(game(40L, 120)))
        val failing = object : DatabaseTransactionScope {
            override suspend fun <R> run(block: suspend () -> R): R = RoomDatabaseTransactionScope(db).run {
                block()
                error("fixture interruption during raw capture")
            }
        }
        assertTrue(runCatching { useCase(failing)() }.isFailure)
        assertEquals(0, db.gameDao().getById(40L)!!.backfillMinutes)
        assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
        assertFalse(db.playerProfileDao().get()!!.pendingImportRecompute)
        assertTrue(useCase()())
    }

    @Test
    fun interruptionAfterCommitResumesDerivedWorkWithoutRecapturingOffsets() = runTest {
        db.gameDao().upsertAll(listOf(game(50L, 120)))
        val interrupted = object : DatabaseTransactionScope {
            override suspend fun <R> run(block: suspend () -> R): R {
                RoomDatabaseTransactionScope(db).run(block)
                error("fixture interruption before recompute")
            }
        }
        assertTrue(runCatching { useCase(interrupted)() }.isFailure)
        assertTrue(db.playerProfileDao().get()!!.pendingImportRecompute)
        assertEquals(120, db.gameDao().getById(50L)!!.backfillMinutes)
        val imported = db.gameDao().getById(50L)!!
        db.gameDao().upsertAll(listOf(imported.copy(playtimeForever = 140)))
        assertFalse(useCase()())
        assertFalse(db.playerProfileDao().get()!!.pendingImportRecompute)
        assertTrue(db.playerProfileDao().get()!!.totalXp > 0)
        assertEquals(120, db.gameDao().getById(50L)!!.backfillMinutes)
    }

    @Test
    fun duplicateRequestsCreditHistoryOnce() = runTest {
        db.gameDao().upsertAll(listOf(game(60L, 120)))
        val importer = useCase()
        val results = listOf(async { importer() }, async { importer() }).awaitAll()
        assertEquals(1, results.count { it })
        assertEquals(120, db.gameDao().getById(60L)!!.backfillMinutes)
        assertFalse(db.playerProfileDao().get()!!.pendingImportRecompute)
    }

    @Test
    fun trackedCreditOnEitherSideOfImportKeepsTheSameTotal() = runTest {
        for (before in listOf(true, false)) {
            val appId = if (before) 70L else 71L
            db.playerProfileDao().updatePlaytimeBackfilled(false)
            db.gameDao().upsertAll(listOf(game(appId, if (before) 140 else 120)))
            if (before) db.sessionDao().insert(session(appId, LocalDate.of(2026, 6, 8), 20))
            useCase()()
            if (!before) {
                db.sessionDao().insert(session(appId, LocalDate.of(2026, 6, 8), 20))
                val imported = db.gameDao().getById(appId)!!
                db.gameDao().upsertAll(listOf(imported.copy(playtimeForever = 140)))
            }
            val tracked = db.sessionDao().creditedMinutesByGame().single { it.appId == appId }.minutes
            assertEquals(140, db.gameDao().getById(appId)!!.backfillMinutes + tracked)
        }
    }

    @Test
    fun accountMismatchOrPendingResetCannotCaptureHistory() = runTest {
        val profile = db.playerProfileDao().get()!!
        db.playerProfileDao().upsert(profile.copy(steamId = "76561198000000002"))
        assertTrue(runCatching { useCase()() }.isFailure)
        db.playerProfileDao().upsert(profile)
        val marker = com.example.backlogium.data.credentials.AccountChangeMarkerStore(RuntimeEnvironment.getApplication())
        marker.markPending("76561198000000002")
        try {
            assertTrue(runCatching { useCase()() }.isFailure)
            assertFalse(db.playerProfileDao().get()!!.playtimeBackfilled)
        } finally { marker.clear() }
    }

    private fun useCase(transaction: DatabaseTransactionScope = RoomDatabaseTransactionScope(db)): PlaytimeBackfillUseCase {
        val updater = GamificationUpdater(
            sessionDao = db.sessionDao(),
            dailyProgressDao = db.dailyProgressDao(),
            playerProfileDao = db.playerProfileDao(),
            hltbDataDao = db.hltbDataDao(),
            achievementDao = db.achievementDao(),
            gameDao = db.gameDao(),
            hiddenGameDao = db.hiddenGameDao(),
        )
        return PlaytimeBackfillUseCase(
            gameDao = db.gameDao(),
            sessionDao = db.sessionDao(),
            playerProfileDao = db.playerProfileDao(),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = DerivedStateWriteCoordinator(),
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            transaction = transaction,
            credentials = object : CredentialsProvider {
                override suspend fun currentCredentials() = CredentialsState.Configured("fixture-key", account)
            },
            accountChangeMarker = com.example.backlogium.data.credentials.AccountChangeMarkerStore(RuntimeEnvironment.getApplication()),
        )
    }

    private fun game(appId: Long, playtimeForever: Int) = Game(
        appId = appId,
        name = "Game $appId",
        iconUrl = "",
        playtimeForever = playtimeForever,
        playtime2Weeks = 0,
        lastPlaytime = playtimeForever,
    )

    private fun session(appId: Long, date: LocalDate, minutes: Int) = Session(
        appId = appId,
        startAt = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        endAt = date.atStartOfDay(zone).toInstant().toEpochMilli() + minutes * 60_000L,
        minutes = minutes,
        open = false,
    )

    private fun contribution(id: String, appId: Long, authorized: Long) = PlaytimeContribution(
        contributionId = id, account = account, appId = appId, baselineEpoch = 1L,
        fromMinutes = 0, toMinutes = authorized, windowEndAt = 0L,
        authorizedMinutes = authorized, revision = 1L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY, createdAt = 0L,
    )

    private fun allocation(placementId: String, contributionId: String, appId: Long, date: LocalDate, minutes: Long) =
        UnknownTimeAllocation(
            placementId = placementId, contributionId = contributionId, account = account, appId = appId,
            revision = 1L, date = date.toString(), minutes = minutes,
            method = RecoveryAllocationMethods.SINGLE_DAY, zone = "UTC",
            pendingDate = false, createdAt = 0L,
        )

    private object FixedTime : TimeProvider {
        private val zone: ZoneId = ZoneId.of("UTC")
        override fun nowMillis(): Long =
            LocalDate.of(2026, 6, 8).atStartOfDay(zone).toInstant().toEpochMilli()
        override fun zone(): ZoneId = zone
        override fun today(): LocalDate = LocalDate.of(2026, 6, 8)
    }
}
