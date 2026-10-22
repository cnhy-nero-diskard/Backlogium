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
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.LibraryGame
import com.example.backlogium.data.repo.SessionRepository
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.ui.gamedetail.Content
import com.example.backlogium.ui.gamedetail.toSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private lateinit var progressMarksStore: InMemoryProgressMarksStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        settings = DataStoreSettingsRepository(SettingsDataStore(RuntimeEnvironment.getApplication()))
        progressMarksStore = InMemoryProgressMarksStore()
        updater = GamificationUpdater(
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            gameDao = database.gameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            progressMarksStore = progressMarksStore,
        )
        runBlocking {
            database.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT))
            database.gameDao().upsert(game(GAME))
            // All authored graph writes go through the atomic single-commit DAO surface.
        }
        useCase = newUseCase()
    }

    private fun newUseCase() = RecoveryPlacementReconcileUseCase(
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
    fun delayedUpgradeLeavesAnotherContributionsFundedShareOnTheSameSessionIntact() = runBlocking {
        val first = contribution(from = 500L, to = 520L, authorized = 20L)
        val second = contribution(from = 520L, to = 540L, authorized = 20L)
        val sessionStart = MON_00_00
        val sessionId = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = sessionStart,
                endAt = sessionStart + 40 * 60_000L,
                minutes = 40,
                open = false,
            ),
        )
        val firstShare = TimedContributionPlacement(
            placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                first.contributionId, 1L, "first-estimate", "2026-08-31", sessionId,
            ),
            contributionId = first.contributionId,
            account = ACCOUNT,
            appId = GAME,
            revision = 1L,
            sessionId = sessionId,
            minutes = 20L,
            creditedDate = "2026-08-31",
            zone = "UTC",
            evidenceQuality = RecoveryEvidenceQuality.ESTIMATE,
            goalAtCredit = true,
            createdAt = 1L,
        )
        val secondShare = TimedContributionPlacement(
            placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                second.contributionId, 1L, "second-verified", "2026-08-31", sessionId,
            ),
            contributionId = second.contributionId,
            account = ACCOUNT,
            appId = GAME,
            revision = 1L,
            sessionId = sessionId,
            minutes = 20L,
            creditedDate = "2026-08-31",
            zone = "UTC",
            evidenceIdentity = "other|$sessionStart|${sessionStart + 20 * 60_000L}",
            evidenceQuality = RecoveryEvidenceQuality.LOCAL,
            goalAtCredit = true,
            createdAt = 1L,
        )
        val dao = database.playtimeRecoveryDao()
        dao.commitAllocatedGraph(first, timed = listOf(firstShare), allocations = emptyList())
        dao.commitAllocatedGraph(second, timed = listOf(secondShare), allocations = emptyList())
        database.dailyProgressDao().ensureDate("2026-08-31")
        database.dailyProgressDao().addMinutes("2026-08-31", 40, 40)
        assertTrue(dao.validateGraph(first.contributionId))
        assertTrue(dao.validateGraph(second.contributionId))

        val outcome = useCase.reconcileContribution(first, listOf(evidenceWeds35))

        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE, outcome.operation)
        val retainedSession = database.sessionDao().getAll().single { it.id == sessionId }
        assertEquals(20, retainedSession.minutes)
        assertEquals(listOf(secondShare), dao.activeTimedSharesForSession(sessionId))
        assertTrue(dao.validateGraph(first.contributionId))
        assertTrue(dao.validateGraph(second.contributionId))
        assertEquals(40, database.sessionDao().creditedMinutesByGame().single().minutes)
        val dailyTotals = database.dailyProgressDao().getAllOrdered().associate { it.date to it.minutesPlayed }
        assertEquals(20, dailyTotals["2026-08-31"])
        assertEquals(20, dailyTotals["2026-09-02"])
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

    @Test
    fun materializedRecipeUpgradePreservesProtectedLegacyBackfillFocusAndConsumerTotals() = runBlocking {
        val dao = database.playtimeRecoveryDao()
        val main = contribution()
        val firstRecipeRow = allocation("2026-09-01", 27L).copy(goalAtCredit = true)
        val recipe = UnknownTimeAllocationRecipe(
            contributionId = main.contributionId,
            account = ACCOUNT,
            appId = GAME,
            revision = 1L,
            method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26L,
            remainderCount = 1L,
            dateStart = "2026-09-01",
            dateEnd = "2026-09-04",
            eligibleCount = 4,
            zone = "UTC",
            windowStartAt = MON_00_00,
            windowEndAt = FRI_20_00,
            goalAtCredit = true,
            updatedAt = 1L,
        )
        dao.commitAllocatedGraph(
            contribution = main,
            timed = emptyList(),
            allocations = listOf(firstRecipeRow),
            recipe = recipe,
        )

        val protectedContribution = contribution(from = 605L, to = 625L, authorized = 20L)
        val protectedRow = allocationFor(
            contribution = protectedContribution,
            date = "2026-09-04",
            minutes = 20L,
            method = RecoveryAllocationMethods.SINGLE_DAY,
            protected = true,
            goalAtCredit = true,
        )
        dao.commitAllocatedGraph(
            contribution = protectedContribution,
            timed = emptyList(),
            allocations = listOf(protectedRow),
        )

        // This independent contribution hosts a mixed row: 35 funded minutes plus 70 old/unlinked
        // minutes. Replacing main's estimate must leave both the legacy remainder and its share.
        val legacyContribution = contribution(from = 625L, to = 660L, authorized = 35L)
        val legacyStart = WEDS_10_00 + 60 * 60_000L
        val legacySessionId = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = legacyStart,
                endAt = legacyStart + 35 * 60_000L,
                minutes = 105,
                open = false,
            ),
        )
        val legacyShare = TimedContributionPlacement(
            placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                legacyContribution.contributionId, 1L, "mixed-legacy", "2026-09-02", legacySessionId,
            ),
            contributionId = legacyContribution.contributionId,
            account = ACCOUNT,
            appId = GAME,
            revision = 1L,
            sessionId = legacySessionId,
            minutes = 35L,
            creditedDate = "2026-09-02",
            zone = "UTC",
            evidenceQuality = RecoveryEvidenceQuality.ESTIMATE,
            goalAtCredit = true,
            createdAt = 1L,
        )
        dao.commitAllocatedGraph(
            contribution = legacyContribution,
            timed = listOf(legacyShare),
            allocations = emptyList(),
        )

        val ownedBefore = game().copy(
            playtimeForever = 660,
            lastPlaytime = 660,
            isGoal = true,
            backfillMinutes = 40,
        )
        val sharedBefore = Game(
            appId = GAME + 1L,
            name = "Shared library item",
            iconUrl = "",
            playtimeForever = 0,
            playtime2Weeks = 0,
            lastPlaytime = 0,
            source = com.example.backlogium.domain.GameSource.FAMILY_SHARED,
            manualSharedMinutes = 55,
        )
        database.gameDao().upsert(ownedBefore)
        database.gameDao().upsert(sharedBefore)
        database.playerProfileDao().upsert(database.playerProfileDao().get()!!.copy(longestStreak = 22))
        seedDaily(listOf(firstRecipeRow, protectedRow))
        database.dailyProgressDao().ensureDate("2026-09-02")
        database.dailyProgressDao().addMinutes("2026-09-02", 105, 105)

        val deferred = useCase.reconcileContribution(main, listOf(evidenceWeds35))
        assertEquals(
            "logical recipe tail is not revised before it is materialized",
            RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE,
            deferred.operation,
        )
        assertEquals(1L, dao.contribution(main.contributionId)!!.revision)
        assertTrue(dao.journalsForContribution(main.contributionId).isEmpty())

        // Recreate the materializer between bounded pages, as a process restart would. Its cursor
        // lives in Room, so a fresh instance must continue after the persisted prefix.
        repeat(2) { page ->
            val restartedMaterializer = UnknownTimeMaterializer(
                database = database,
                recoveryDao = dao,
                dailyCredit = RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
            )
            val slice = restartedMaterializer.materialize(main.contributionId, now = 1L, pageSize = 2)
            assertTrue("expected persisted materialization page $page", slice != null)
            assertEquals(listOf(79L, 105L)[page], dao.recipe(main.contributionId)!!.materializedMinutes)
        }
        assertEquals(105L, dao.sumActiveDatedForContribution(main.contributionId))
        assertTrue(dao.conservationHolds(main.contributionId))
        updater.recompute(FixedTime.today(), RecomputeSource.SYNC)

        val hiddenRepository = HiddenGamesRepository(
            hiddenGameDao = database.hiddenGameDao(),
            gameDao = database.gameDao(),
            storeCacheDao = database.gameGenreCacheDao(),
            time = FixedTime,
        )
        val sessionRepository = SessionRepository(
            sessionDao = database.sessionDao(),
            hiddenGamesRepository = hiddenRepository,
            time = FixedTime,
            database = database,
        )
        val trackedBefore = sessionRepository.trackedMinutesByGame.first()
        assertEquals(mapOf(GAME to 230), trackedBefore)
        val datedBefore = sessionRepository.datedCreditedTotalsBetween(
            ZoneOffset.UTC, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-05"),
        ).first().associate { it.date.toString() to it.minutes }
        assertEquals(
            mapOf("2026-09-01" to 27L, "2026-09-02" to 131L, "2026-09-03" to 26L, "2026-09-04" to 46L),
            datedBefore,
        )
        val dailyBefore = database.dailyProgressDao().getAllOrdered()
        val dailyCreditBefore = dailyBefore.associate { it.date to (it.minutesPlayed to it.goalMinutesPlayed) }
        val minutesBefore = dailyBefore.sumOf { it.minutesPlayed }
        val focusBefore = dailyBefore.sumOf { it.goalMinutesPlayed }
        assertEquals(
            mapOf(
                "2026-09-01" to (27 to 27),
                "2026-09-02" to (131 to 131),
                "2026-09-03" to (26 to 26),
                "2026-09-04" to (46 to 46),
            ),
            dailyCreditBefore.filterKeys { it in setOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04") },
        )
        val profileBefore = database.playerProfileDao().get()!!
        val xpBefore = profileBefore.totalXp
        val progressMarksBefore = progressMarksStore.read()
        val quantitiesBefore = dao.contributionsForGame(ACCOUNT, GAME).map {
            Triple(it.contributionId, it.authorizedMinutes, it.provenance to it.baselineEpoch)
        }.sortedBy { it.first }
        val contributionBefore = dao.contribution(main.contributionId)!!
        val protectedBefore = dao.activeAllocations(protectedContribution.contributionId).single()
        val legacySessionBefore = database.sessionDao().getAll().single { it.id == legacySessionId }
        val legacyShareBefore = dao.activeTimedPlacements(legacyContribution.contributionId).single()

        val replacement = useCase.reconcileContribution(main, listOf(evidenceWeds35))
        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.UPGRADE, replacement.operation)
        assertEquals(1L, replacement.priorRevision)
        assertEquals(2L, replacement.currentRevision)
        assertEquals(35L, replacement.movedToEvidenceMinutes)
        val contributionAfter = dao.contribution(main.contributionId)!!
        assertEquals(main.contributionId, contributionAfter.contributionId)
        assertEquals(contributionBefore.authorizedMinutes, contributionAfter.authorizedMinutes)
        assertEquals(contributionBefore.fromMinutes, contributionAfter.fromMinutes)
        assertEquals(contributionBefore.toMinutes, contributionAfter.toMinutes)
        assertEquals(contributionBefore.baselineEpoch, contributionAfter.baselineEpoch)
        assertEquals(contributionBefore.provenance, contributionAfter.provenance)
        assertEquals(contributionBefore.copy(revision = 2L), contributionAfter)
        assertEquals(
            quantitiesBefore,
            dao.contributionsForGame(ACCOUNT, GAME).map {
                Triple(it.contributionId, it.authorizedMinutes, it.provenance to it.baselineEpoch)
            }.sortedBy { it.first },
        )
        assertTrue(dao.conservationHolds(main.contributionId))
        assertNull(
            "row-materialized replacement removes the obsolete compact recipe",
            dao.recipe(main.contributionId),
        )
        assertEquals(protectedBefore, dao.activeAllocations(protectedContribution.contributionId).single())
        assertEquals(legacySessionBefore, database.sessionDao().getAll().single { it.id == legacySessionId })
        assertEquals(legacyShareBefore, dao.activeTimedPlacements(legacyContribution.contributionId).single())
        assertEquals(105L, dao.conservedAllocatedMinutes(main.contributionId))
        assertEquals(35L, dao.sumActiveTimedMinutes(main.contributionId))
        assertEquals(70L, dao.sumActiveAllocatedMinutes(main.contributionId))

        val trackedAfter = sessionRepository.trackedMinutesByGame.first()
        assertEquals(trackedBefore, trackedAfter)
        val datedAfter = sessionRepository.datedCreditedTotalsBetween(
            ZoneOffset.UTC, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-05"),
        ).first().associate { it.date.toString() to it.minutes }
        assertEquals(
            mapOf("2026-09-01" to 18L, "2026-09-02" to 158L, "2026-09-03" to 17L, "2026-09-04" to 37L),
            datedAfter,
        )
        val dailyAfter = database.dailyProgressDao().getAllOrdered()
        val dailyCreditAfter = dailyAfter.associate { it.date to (it.minutesPlayed to it.goalMinutesPlayed) }
        assertEquals(minutesBefore, dailyAfter.sumOf { it.minutesPlayed })
        assertEquals(focusBefore, dailyAfter.sumOf { it.goalMinutesPlayed })
        assertEquals(
            mapOf(
                "2026-09-01" to (18 to 18),
                "2026-09-02" to (158 to 158),
                "2026-09-03" to (17 to 17),
                "2026-09-04" to (37 to 37),
            ),
            dailyCreditAfter.filterKeys { it in setOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04") },
        )
        assertEquals(
            dailyCreditBefore.keys,
            dailyCreditAfter.keys,
        )
        val profileAfter = database.playerProfileDao().get()!!
        assertEquals(xpBefore, profileAfter.totalXp)
        assertTrue(profileAfter.longestStreak >= profileBefore.longestStreak)
        assertEquals(
            "placement correction is silent to earned-event marks",
            progressMarksBefore,
            progressMarksStore.read(),
        )

        // Backfill and family-shared manual quantity remain outside the owned recovery revision.
        assertEquals(40, database.gameDao().getById(GAME)!!.backfillMinutes)
        assertEquals(sharedBefore, database.gameDao().getById(GAME + 1L))
        assertEquals(
            660,
            GameSource.STEAM_OWNED.displayedPlaytimeMinutes(
                steamPlaytimeMinutes = database.gameDao().getById(GAME)!!.playtimeForever,
                trackedMinutes = trackedAfter[GAME] ?: 0,
            ),
        )
        assertEquals(
            55,
            GameSource.FAMILY_SHARED.displayedPlaytimeMinutes(
                steamPlaytimeMinutes = 0,
                trackedMinutes = 0,
                manualSharedMinutes = database.gameDao().getById(GAME + 1L)!!.manualSharedMinutes,
            ),
        )
        fun detailSummary(trackedMinutes: Int) = Content(
            game = LibraryGame(
                appId = GAME,
                name = "Portal",
                iconUrl = "",
                playtimeForever = ownedBefore.playtimeForever,
                backfillMinutes = ownedBefore.backfillMinutes,
                isGoal = true,
            ),
            achievements = emptyList(),
            trackedMinutes = trackedMinutes,
            config = RuleConfig(),
        ).toSummary(rows = emptyList(), activePlayers = null)
        val detailBefore = detailSummary(trackedBefore[GAME] ?: 0)
        val detailAfter = detailSummary(trackedAfter[GAME] ?: 0)
        assertEquals(detailBefore, detailAfter)
        assertEquals(230, detailAfter.trackedMinutes)
        assertEquals(40, detailAfter.importedMinutes)
        fun collectionBanner(tracked: Map<Long, Int>) = CollectionSummary.derive(
            mode = CollectionMode.COMPLETION_GOAL,
            sort = CollectionSort.NAME,
            targetDate = null,
            members = listOf(
                CollectionMemberSignals(
                    appId = GAME,
                    name = "Portal",
                    playtimeMinutes = tracked[GAME] ?: 0,
                    completionistMinutes = 1_000,
                    achievementsUnlocked = 0,
                    achievementsTotal = 0,
                ),
            ),
            today = FixedTime.today(),
        )
        assertEquals(collectionBanner(trackedBefore), collectionBanner(trackedAfter))

        val replay = newUseCase().reconcileContribution(main, listOf(evidenceWeds35))
        assertEquals(RecoveryPlacementReconciler.RecoveryReconcileOperation.NO_CHANGE, replay.operation)
        assertEquals(1, dao.journalsForContribution(main.contributionId).size)
        assertTrue(dao.conservationHolds(main.contributionId))
        assertEquals(trackedAfter, sessionRepository.trackedMinutesByGame.first())
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

    private fun allocationFor(
        contribution: PlaytimeContribution,
        date: String,
        minutes: Long,
        method: String,
        protected: Boolean = false,
        goalAtCredit: Boolean = false,
    ) = UnknownTimeAllocation(
        placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
            contribution.contributionId, contribution.revision, date,
        ),
        contributionId = contribution.contributionId,
        account = contribution.account,
        appId = contribution.appId,
        revision = contribution.revision,
        date = date,
        minutes = minutes,
        method = method,
        zone = "UTC",
        windowStartAt = contribution.windowStartAt,
        windowEndAt = contribution.windowEndAt,
        goalAtCredit = goalAtCredit,
        protected = protected,
        createdAt = 1L,
    )

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
