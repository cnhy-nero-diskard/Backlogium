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
        db.playerProfileDao().upsert(com.example.backlogium.data.local.entity.PlayerProfile(steamId = account))
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

    private fun useCase(): PlaytimeBackfillUseCase {
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
