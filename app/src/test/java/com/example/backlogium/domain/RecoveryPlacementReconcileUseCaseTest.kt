package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end task 6.2/6.3 verification against the real Room schema: a retained 105-minute
 * contribution upgraded with 35 minutes of late timing keeps exactly 105 minutes through atomic
 * revision switching, conservation, daily-credit movement, journal finalization, duplicate replay
 * and a mapped synthetic-session split that preserves its legacy remainder.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryPlacementReconcileUseCaseTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var useCase: RecoveryPlacementReconcileUseCase
    private lateinit var updater: GamificationUpdater

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = DataStoreSettingsRepository(SettingsDataStore(RuntimeEnvironment.getApplication()))
        updater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            progressMarksStore = InMemoryProgressMarksStore(),
        )
        runBlocking {
            database.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT))
            database.gameDao().upsert(game(GAME))
            // All authored graph writes go through the atomic single-commit DAO surface.
        }
        useCase = RecoveryPlacementReconcileUseCase(
            recoveryDao = database.playtimeRecoveryDao(),
            sessionDao = database.sessionDao(),
            dailyCredit = RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
            settings = settings,
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
    }

    @After
    fun tearDown() {
        runBlocking { settings.clearCloudPresenceRefiling() }
        database.close()
    }

    @Test
    fun lateEvidenceMoves35Of105ConservingQuantityAndDailyTotalsAcrossReplay() = runBlocking {
        val contribution = contribution()
        val allocations = estimates27_26_26_26()
        database.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = emptyList(),
            allocations = allocations,
        )
        seedDaily(allocations)
        updater.recompute(FixedTime.today(), RecomputeSource.SYNC)
        val xpBefore = database.playerProfileDao().get()!!.totalXp
        val creditedBefore = database.sessionDao().creditedMinutesByGame().single().minutes

        val outcome = useCase.reconcileContribution(contribution, listOf(evidenceWeds35))

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE, outcome.operation)
        assertEquals(35L, outcome.movedToEvidenceMinutes)
        assertEquals(1L, outcome.priorRevision)
        assertEquals(2L, outcome.currentRevision)

        val dao = database.playtimeRecoveryDao()
        val id = contribution.contributionId
        assertEquals(2L, dao.contribution(id)?.revision)
        assertTrue("authorized graph must conserve", dao.conservationHolds(id))
        assertTrue(dao.validateGraph(id))
        assertEquals(35L, dao.sumActiveTimedMinutes(id))
        assertEquals(70L, dao.sumActiveAllocatedMinutes(id))
        assertEquals(listOf(18L, 18L, 17L, 17L), dao.activeAllocations(id).map { it.minutes })
        assertTrue(
            dao.activeAllocations(id).all { it.method == RecoveryAllocationMethods.MULTI_DAY_ESTIMATE },
        )

        // One evidence-informed session was created for the 35-minute share.
        val sessions = database.sessionDao().getAll()
        assertEquals(1, sessions.size)
        assertEquals(35, sessions.single().minutes)
        assertEquals(TimingInformedSteamPlayState.FULL, sessions.single().timingInformedSteamPlay)
        assertTrue(sessions.single().startAt == WEDS_10_00 && sessions.single().endAt == WEDS_10_35)

        // Daily totals moved with the placement: 18 / 53 / 17 / 17, net zero across the window.
        assertEquals(18, daily("2026-09-01"))
        assertEquals(53, daily("2026-09-02"))
        assertEquals(17, daily("2026-09-03"))
        assertEquals(17, daily("2026-09-04"))

        // Steam quantity and XP are conserved: canonical credited minutes stay 105 and the level
        // transition baseline is untouched by the normalization of daily totals.
        assertEquals(105, database.sessionDao().creditedMinutesByGame().single().minutes)
        assertEquals(creditedBefore, database.sessionDao().creditedMinutesByGame().single().minutes)
        assertEquals(xpBefore, database.playerProfileDao().get()!!.totalXp)

        val journal = dao.journalsForContribution(id).single()
        assertEquals(RecoveryJournalStatus.FINALIZED, journal.derivedStatus)
        assertEquals(4, journal.replacedPlacementIdsCsv!!.split(",").size)

        // Replay with the same evidence: the funded span is consumed, nothing changes twice.
        val replay = useCase.reconcileContribution(contribution, listOf(evidenceWeds35))
        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, replay.operation)
        assertEquals(1, dao.journalsForContribution(id).size)
        assertEquals(1, database.sessionDao().getAll().size)
        assertEquals(18, daily("2026-09-01"))
        assertEquals(53, daily("2026-09-02"))
        assertEquals(17, daily("2026-09-03"))
        assertEquals(17, daily("2026-09-04"))
        assertTrue(dao.conservationHolds(id))
    }

    @Test
    fun mappedSyntheticSessionShareSplitsOutPreservingItsLegacyRemainder() = runBlocking {
        // A diff-synthesized session of 105 minutes hosts 35 funded minutes plus 70 unlinked
        // legacy minutes that were never part of any contribution graph.
        val contribution = contribution(from = 500L, to = 535L, authorized = 35L)
        val legacyId = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = WEDS_10_00,
                endAt = WEDS_10_35,
                minutes = 105,
                open = false,
            ),
        )
        database.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contribution.contributionId, 1L, "ev-legacy", "2026-09-02", legacyId,
                    ),
                    contributionId = contribution.contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    sessionId = legacyId,
                    minutes = 35L,
                    creditedDate = "2026-09-02",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.ESTIMATE,
                    createdAt = 1L,
                ),
            ),
            allocations = emptyList(),
        )
        seedDaily(listOf(allocation("2026-09-02", 35L)))

        val outcome = useCase.reconcileContribution(contribution, listOf(evidenceWeds35))

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE, outcome.operation)
        assertTrue(
            "only the mapped funded share moves; the unlinked legacy remainder stays on the row",
            database.sessionDao().getAll().any { it.id == legacyId && it.minutes == 70 },
        )
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contribution.contributionId))
        assertEquals(35L, database.playtimeRecoveryDao().sumActiveTimedMinutes(contribution.contributionId))
        assertEquals(2, database.sessionDao().getAll().size) // reduced legacy row + evidence session
    }

    @Test
    fun fullyFundedSessionIsRetiredWhenNoIndependentStateReferencesIt() = runBlocking {
        val contribution = contribution(from = 500L, to = 535L, authorized = 35L)
        val onlyId = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = WEDS_10_00,
                endAt = WEDS_10_35,
                minutes = 35,
                open = false,
            ),
        )
        database.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contribution.contributionId, 1L, "ev-synth", "2026-09-02", onlyId,
                    ),
                    contributionId = contribution.contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    sessionId = onlyId,
                    minutes = 35L,
                    creditedDate = "2026-09-02",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.ESTIMATE,
                    createdAt = 1L,
                ),
            ),
            allocations = emptyList(),
        )
        seedDaily(listOf(allocation("2026-09-02", 35L)))

        useCase.reconcileContribution(contribution, listOf(evidenceWeds35))

        val sessions = database.sessionDao().getAll()
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().id != onlyId) // the empty synthetic row was retired
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contribution.contributionId))
    }

    @Test
    fun protectedManualPlacementSurvivesAnUpgradeUntouched() = runBlocking {
        val contribution = contribution()
        // Authorized 105 = 20 protected on Friday + 85 unlocked estimates on the other dates.
        val protectedAllocation = allocation("2026-09-04", 20L).copy(protected = true)
        val unlocked = listOf(
            allocation("2026-09-01", 29L),
            allocation("2026-09-02", 28L),
            allocation("2026-09-03", 28L),
        )
        database.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = emptyList(),
            allocations = listOf(protectedAllocation) + unlocked,
        )
        seedDaily(listOf(protectedAllocation) + unlocked)

        useCase.reconcileContribution(contribution, listOf(evidenceWeds35))

        val dao = database.playtimeRecoveryDao()
        // The protected row is re-declared with its minutes and flag intact.
        val kept = dao.activeAllocations(contribution.contributionId)
            .firstOrNull { it.date == "2026-09-04" }
        assertTrue(kept != null && kept.minutes == 20L && kept.protected)
        assertTrue(dao.conservationHolds(contribution.contributionId))
        assertEquals(20, daily("2026-09-04"))
    }

    // ------------------------------------------------------------------ fixtures

    private fun contribution(
        from: Long = 500L,
        to: Long = 605L,
        authorized: Long = to - from,
    ) = PlaytimeContribution(
        contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, from, to),
        account = ACCOUNT,
        appId = GAME,
        baselineEpoch = 1L,
        fromMinutes = from,
        toMinutes = to,
        windowStartAt = MON_00_00,
        windowEndAt = FRI_20_00,
        authorizedMinutes = authorized,
        revision = 1L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY,
        zone = "UTC",
        createdAt = 1L,
    )

    private fun estimates27_26_26_26() = listOf(
        allocation("2026-09-01", 27L),
        allocation("2026-09-02", 26L),
        allocation("2026-09-03", 26L),
        allocation("2026-09-04", 26L),
    )

    private fun allocation(date: String, minutes: Long): UnknownTimeAllocation {
        val id = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 500L, 605L)
        return UnknownTimeAllocation(
            placementId = PlaytimeRecoveryIdentity.allocationPlacementId(id, 1L, date),
            contributionId = id,
            account = ACCOUNT,
            appId = GAME,
            revision = 1L,
            date = date,
            minutes = minutes,
            method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            zone = "UTC",
            windowStartAt = MON_00_00,
            windowEndAt = FRI_20_00,
            createdAt = 1L,
        )
    }

    private suspend fun seedDaily(allocations: List<UnknownTimeAllocation>) {
        allocations.forEach { row ->
            val date = row.date ?: return@forEach
            database.dailyProgressDao().ensureDate(date)
            database.dailyProgressDao().addMinutes(
                date,
                row.minutes.toInt(),
                if (row.goalAtCredit) row.minutes.toInt() else 0,
            )
        }
    }

    private suspend fun daily(date: String): Int =
        database.dailyProgressDao().getByDate(date)?.minutesPlayed ?: 0

    private fun game(appId: Long = GAME) = Game(
        appId = appId,
        name = "Portal",
        iconUrl = "",
        playtimeForever = 605,
        playtime2Weeks = 0,
        lastPlaytime = 605,
        source = GameSource.STEAM_OWNED,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = Instant.parse("2026-09-06T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneOffset = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 9, 6)
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val GAME = 440L

        val MON_00_00 = Instant.parse("2026-08-31T00:00:00Z").toEpochMilli()
        val FRI_20_00 = Instant.parse("2026-09-04T20:00:00Z").toEpochMilli()
        val WEDS_10_00 = Instant.parse("2026-09-02T10:00:00Z").toEpochMilli()
        val WEDS_10_35 = Instant.parse("2026-09-02T10:35:00Z").toEpochMilli()

        val evidenceWeds35 = PlaytimeTimingEvidence(
            evidenceId = "ev-1",
            appId = GAME,
            account = ACCOUNT,
            source = PlaytimeEvidenceSource.LOCAL,
            startAt = WEDS_10_00,
            endAt = WEDS_10_35,
        )
    }
}
