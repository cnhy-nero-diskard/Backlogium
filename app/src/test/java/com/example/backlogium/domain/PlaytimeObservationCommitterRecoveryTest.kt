package com.example.backlogium.domain

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.dao.BaselineAdvance
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.repo.CloudPendingEvidencePruner
import com.example.backlogium.data.repo.FakeCredentials
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import java.time.ZoneOffset

/**
 * Shared atomic commit path integration tests for `recover-missed-playtime` tasks 4.1 (one
 * committer plan for periodic and targeted reads), 4.2 (transaction failpoints roll back every
 * raw state), 4.4 (independent per-game boundaries, stale-baseline rewind rejection), 4.5 (stale
 * open sessions close at their retained boundary without absorbing fallback minutes) and 6.1
 * (bounded cloud evidence with local residual, no-cloud recovery, reader fences, no snapshot
 * recovery for shared games).
 *
 * Every commit runs through the real [PlaytimeObservationCommitter] against a real in-memory
 * Room database; the only fakes are the time source and, for the fence tests, the credential/
 * settings sources that drive the existing reader-promotion fence. No process-level mutex wraps
 * these calls: exactly-once behavior is the database transaction's guarantee, which is what the
 * tests exercise.
 */
@RunWith(RobolectricTestRunner::class)
class PlaytimeObservationCommitterRecoveryTest {

    private val appId = 440L
    private val account = "76561198000000011" // Non-secret, synthetic test identity.
    private val minute = 60_000L

    private lateinit var db: BacklogiumDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // ----------------------------------------------------------------- 4.1 one shared path

    @Test
    fun concurrentPeriodicAndTargetedReadsCommitTheIncreaseOnceRegardlessOfOrder() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        val committer = committer()

        // Two observers of the same increase, launched without any process-level coordination:
        // either may commit first, and the database transaction must make the second a no-op.
        coroutineScope {
            val first = async {
                db.withTransaction { committer.commit(obs(570), wednesday, wednesday) }
            }
            val second = async {
                db.withTransaction { committer.commit(obs(570), wednesday, wednesday) }
            }
            val results = listOf(first.await(), second.await())

            assertEquals(1, results.count { it.recordedPlay })
            assertTrue(results.any { !it.recordedPlay })
        }

        val dao = db.playtimeRecoveryDao()
        val contributions = dao.contributionsForGame(account, appId)
        assertEquals(1, contributions.size)
        assertEquals(70L, contributions.single().authorizedMinutes)
        assertTrue("funded graph must stay conserved", dao.validateGraph(contributions.single().contributionId))
        assertEquals(70, allocatedMinutes(appId))
        assertEquals(570, db.gameDao().getById(appId)!!.lastPlaytime)
    }

    @Test
    fun independentPeriodicAndTargetedCoordinatorsCommitTheHighWaterOnceInEitherOrder() = runTest {
        val periodicFirstApp = appId + 1L
        val targetedFirstApp = appId + 2L
        seedGame(periodicFirstApp, playtime = 500, lastSyncAt = monday)
        seedGame(targetedFirstApp, playtime = 500, lastSyncAt = monday)
        val sharedCommitter = committer()
        val periodicCoordinator = com.example.backlogium.work.SteamSyncCoordinator()
        val targetedCoordinator = com.example.backlogium.work.SteamSyncCoordinator()

        val periodicFirst = periodicCoordinator.withLock {
            db.withTransaction { sharedCommitter.commit(obs(periodicFirstApp, 570), wednesday, wednesday) }
        }
        val targetedSecond = targetedCoordinator.withLock {
            db.withTransaction { sharedCommitter.commit(obs(periodicFirstApp, 570), wednesday, wednesday) }
        }
        val targetedFirst = targetedCoordinator.withLock {
            db.withTransaction { sharedCommitter.commit(obs(targetedFirstApp, 570), wednesday, wednesday) }
        }
        val periodicSecond = periodicCoordinator.withLock {
            db.withTransaction { sharedCommitter.commit(obs(targetedFirstApp, 570), wednesday, wednesday) }
        }

        assertTrue(periodicFirst.recordedPlay)
        assertFalse(targetedSecond.recordedPlay)
        assertTrue(targetedFirst.recordedPlay)
        assertFalse(periodicSecond.recordedPlay)
        for (targetApp in listOf(periodicFirstApp, targetedFirstApp)) {
            val contributions = db.playtimeRecoveryDao().contributionsForGame(account, targetApp)
            assertEquals(1, contributions.size)
            assertEquals(70L, contributions.single().authorizedMinutes)
            assertTrue(db.playtimeRecoveryDao().validateGraph(contributions.single().contributionId))
            assertEquals(570L, db.playtimeRecoveryDao().state(account, targetApp)!!.highWaterMinutes)
            assertEquals(570, db.gameDao().getById(targetApp)!!.lastPlaytime)
        }
    }

    @Test
    fun firstOwnedReadOnlyEstablishesBaselineAndDoesNotImportLifetimePlay() = runTest {
        db.playerProfileDao().upsert(PlayerProfile(steamId = account, lastSyncAt = 0L))

        val result = db.withTransaction {
            committer().commit(obs(appId, 605), friday, friday)
        }

        assertFalse(result.recordedPlay)
        assertTrue(result.playedDeltaByAppId.isEmpty())
        assertEquals(setOf(appId), result.baselineEstablishedAppIds)
        assertEquals(0, db.sessionDao().getAll().count { it.appId == appId })
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, appId).isEmpty())
        assertEquals(605L, db.playtimeRecoveryDao().state(account, appId)!!.highWaterMinutes)
        assertEquals(605, db.gameDao().getById(appId)!!.lastPlaytime)
        assertTrue(db.dailyProgressDao().getAllOrdered().isEmpty())
    }

    @Test
    fun rawWriteFailpointsRollbackEveryObservationBoundaryThenRetryCommitsOnce() = runTest {
        RawObservationFailpoint.values().forEachIndexed { index, failpoint ->
            val targetApp = appId + 10L + index
            seedGame(targetApp, playtime = 500, lastSyncAt = monday)

            val failingRecoveryDao = FailingRawObservationRecoveryDao(db.playtimeRecoveryDao(), failpoint)
            val failingSessionDao = FailingRawObservationSessionDao(db.sessionDao(), failpoint)
            val failingDailyDao = FailingRawObservationDailyDao(db.dailyProgressDao(), failpoint)
            val failingGameDao = FailingRawObservationGameDao(db.gameDao(), failpoint)
            val failingCommitter = committer(
                adapter = PlaytimeRecoveryCommitAdapter(failingRecoveryDao, failingSessionDao),
                gameDao = failingGameDao,
                sessionDao = failingSessionDao,
                dailyProgressDao = failingDailyDao,
            )

            val beforeGame = db.gameDao().getById(targetApp)!!
            val beforeSessions = db.sessionDao().getAll().filter { it.appId == targetApp }
            val beforeDaily = db.dailyProgressDao().getAllOrdered()
            val beforeOtherContributions = db.playtimeRecoveryDao().contributionsForGame(account, targetApp)

            val failure = runCatching {
                db.withTransaction {
                    failingCommitter.commit(
                        observed = obs(targetApp, 570),
                        observedPlayAt = friday,
                        syncedAt = friday,
                        placement = placement(targetApp),
                    )
                }
            }.exceptionOrNull()

            assertNotNull("$failpoint must interrupt the raw observation", failure)
            assertEquals(beforeGame, db.gameDao().getById(targetApp))
            assertEquals(beforeSessions, db.sessionDao().getAll().filter { it.appId == targetApp })
            assertEquals(beforeDaily, db.dailyProgressDao().getAllOrdered())
            assertNull(db.playtimeRecoveryDao().state(account, targetApp))
            assertEquals(beforeOtherContributions, db.playtimeRecoveryDao().contributionsForGame(account, targetApp))

            val retry = db.withTransaction {
                committer().commit(
                    observed = obs(targetApp, 570),
                    observedPlayAt = friday,
                    syncedAt = friday,
                    placement = placement(targetApp),
                )
            }
            assertTrue("$failpoint retry should still authorize the increase", retry.recordedPlay)
            val contributions = db.playtimeRecoveryDao().contributionsForGame(account, targetApp)
            assertEquals(1, contributions.size)
            assertEquals(70L, contributions.single().authorizedMinutes)
            assertTrue(db.playtimeRecoveryDao().validateGraph(contributions.single().contributionId))
            assertEquals(570L, db.playtimeRecoveryDao().state(account, targetApp)!!.highWaterMinutes)
            assertEquals(570, db.gameDao().getById(targetApp)!!.lastPlaytime)
        }
    }

    // ----------------------------------------------------------------- 4.2 atomic failpoint

    @Test
    fun failedPersistenceRollsBackSessionsGraphBaselineAndDailyCreditThenRetryCommitsOnce() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        val failing = PlaytimeRecoveryCommitAdapter(FailingRecoveryDao(db.playtimeRecoveryDao()), db.sessionDao())
        val failingCommitter = committer(failing)

        val thrown = runCatching {
            db.withTransaction { failingCommitter.commit(obs(605), friday, friday) }
        }.exceptionOrNull()
        assertNotNull("the failpoint must interrupt the commit", thrown)

        // Nothing from the failed attempt may survive: no baseline advance, no session, no graph,
        // no allocation, no daily credit.
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(500, db.gameDao().getById(appId)!!.lastPlaytime)
        assertEquals(0, allocatedMinutes(appId))
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, appId).isEmpty())
        assertNull(db.dailyProgressDao().getByDate("2026-07-22"))

        // A clean retry over the same un-advanced baseline credits the increase exactly once.
        val retry = db.withTransaction { committer().commit(obs(605), friday, friday) }
        assertTrue(retry.recordedPlay)
        assertEquals(105, allocatedMinutes(appId))
        assertEquals(1, db.playtimeRecoveryDao().contributionsForGame(account, appId).size)
        assertEquals(605, db.gameDao().getById(appId)!!.lastPlaytime)
    }

    // ----------------------------------------------------------------- 4.4 per-game boundaries

    @Test
    fun targetedReadOfOneGameDoesNotShortenAnotherGamesRecoveryWindow() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        val otherApp = 480L
        seedGame(otherApp, playtime = 500, lastSyncAt = monday)

        db.withTransaction { committer().commit(obs(570), wednesday, wednesday) }

        // Game A recovered its 70 minutes; game B saw no reading and stays untouched.
        assertEquals(70, allocatedMinutes(appId))
        assertEquals(0, allocatedMinutes(otherApp))
        // This targeted observation did not read game B, so it must not create or advance B's
        // recovery state. Its per-game legacy boundary remains on Game.lastSyncedAt for its own
        // later trustworthy read to seed conservatively.
        assertNull(db.playtimeRecoveryDao().state(account, otherApp))

        // B's full Monday -> Friday window must still be available: a later Friday read of B
        // alone produces the untouched 27/26/26/26 distribution, not a shortened one.
        db.withTransaction { committer().commit(obs(otherApp, 605), friday, friday) }
        assertEquals(
            mapOf("2026-07-21" to 27L, "2026-07-22" to 26L, "2026-07-23" to 26L, "2026-07-24" to 26L),
            allocationsByDate(otherApp),
        )
    }

    @Test
    fun staleOlderResponseStaysPendingAfterLaterUnchangedReadAcrossZoneAndEligibilityChanges() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = friday, isGoal = true) // boundary at Friday
        val time = MutableTimeProvider()
        val committer = committer(time = time)

        // Thursday is behind the usable boundary, yet Steamed published 570 by then: the truthful
        // total advances, the boundary does not rewind, and the minutes wait conservatively.
        db.withTransaction { committer.commit(obs(570), thursday, thursday) }

        val state = db.playtimeRecoveryDao().state(account, appId)!!
        assertEquals(570L, state.highWaterMinutes)
        assertEquals(friday, state.lastObservationAt)
        val contributions = db.playtimeRecoveryDao().contributionsForGame(account, appId)
        assertEquals(1, contributions.size)
        assertEquals(70L, contributions.single().authorizedMinutes)
        assertEquals(70L, db.playtimeRecoveryDao().sumActivePendingMinutes(account, appId))
        assertEquals(0, allocatedMinutes(appId)) // pending is not dated credit nor a session
        val originalPending = db.playtimeRecoveryDao()
            .activeAllocations(contributions.single().contributionId).single()
        assertEquals(friday, originalPending.windowStartAt)
        assertEquals(thursday, originalPending.windowEndAt)
        assertEquals(ZoneOffset.UTC.id, originalPending.zone)
        assertFalse(originalPending.hiddenAtCredit)
        assertTrue(originalPending.goalAtCredit)

        // A later unchanged read cannot claim that Thursday's already-reported quantity occurred
        // in Friday-Saturday. Current timezone/hidden/Focus settings do not rewrite its frozen facts.
        time.currentZone = ZoneId.of("Asia/Tokyo")
        db.gameDao().upsert(db.gameDao().getById(appId)!!.copy(isGoal = false))
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(appId, hiddenAt = saturday, fromBulkAction = false)))
        val laterRead = db.withTransaction { committer.commit(obs(570), saturday, saturday) }
        assertEquals(0, laterRead.recoveredContributions)
        assertEquals(70L, db.playtimeRecoveryDao().sumActivePendingMinutes(account, appId))
        assertEquals(0, allocatedMinutes(appId))
        assertEquals(1, db.playtimeRecoveryDao().contributionsForGame(account, appId).size)
        assertTrue(db.playtimeRecoveryDao().validateGraph(contributions.single().contributionId))
        val retainedPending = db.playtimeRecoveryDao()
            .activeAllocations(contributions.single().contributionId).single()
        assertEquals(originalPending.placementId, retainedPending.placementId)
        assertEquals(originalPending.windowStartAt, retainedPending.windowStartAt)
        assertEquals(originalPending.windowEndAt, retainedPending.windowEndAt)
        assertEquals(originalPending.zone, retainedPending.zone)
        assertFalse(retainedPending.hiddenAtCredit)
        assertTrue(retainedPending.goalAtCredit)
        assertNull(db.dailyProgressDao().getByDate("2026-07-25"))
    }

    // ----------------------------------------------------------------- 4.5 stale open sessions

    @Test
    fun staleOpenSessionIsClosedAtItsBoundaryAndCannotAbsorbMultiDayFallback() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        val staleStart = atUtc(2026, 7, 20, 8, 0) // Monday 08:00
        val staleEnd = atUtc(2026, 7, 20, 9, 0) // Monday 09:00
        db.sessionDao().insert(
            Session(appId = appId, startAt = staleStart, endAt = staleEnd, minutes = 60, open = true),
        )

        db.withTransaction { committer().commit(obs(605), friday, friday) }

        val sessions = db.sessionDao().getAll()
        assertEquals(1, sessions.size)
        val retained = sessions.single()
        assertFalse("the stale open row closes at its last trustworthy boundary", retained.open)
        assertEquals(staleEnd, retained.endAt)
        assertEquals(60, retained.minutes)

        // The 105-minute increase never touched the old row: it is fully dated allocations.
        assertEquals(105, allocatedMinutes(appId))
        assertEquals(105, allocationsByDate(appId).values.sum())
        assertEquals(
            mapOf("2026-07-21" to 27L, "2026-07-22" to 26L, "2026-07-23" to 26L, "2026-07-24" to 26L),
            allocationsByDate(appId),
        )
    }

    @Test
    fun ordinarySameDateShortGapKeepsItsTimestampedSessionFundedByASessionShare() = runTest {
        val start = atUtc(2026, 7, 20, 10, 0) // Monday 10:00
        val end = atUtc(2026, 7, 20, 10, 20)
        seedGame(appId, playtime = 500, lastSyncAt = start)

        db.withTransaction { committer().commit(obs(520), end, end) }

        val session = db.sessionDao().getAll().single()
        assertEquals(start, session.startAt)
        assertEquals(end, session.endAt)
        assertEquals(20, session.minutes)
        assertTrue(session.open)
        assertEquals(0, allocatedMinutes(appId))
        // The ordinary range is still one funded contribution, documented by a share linked to the
        // actual session row and carrying exactly its added minutes.
        val contributions = db.playtimeRecoveryDao().contributionsForGame(account, appId)
        assertEquals(1, contributions.size)
        assertEquals(20L, contributions.single().authorizedMinutes)
        val share = db.playtimeRecoveryDao().activeTimedSharesForSession(session.id).single()
        assertEquals(20L, share.minutes)
        assertTrue(db.playtimeRecoveryDao().validateGraph(contributions.single().contributionId))
    }

    @Test
    fun ordinaryDeltaThenExtendFundsEachRangeOnceAcrossTheSharedSessionRow() = runTest {
        val start = atUtc(2026, 7, 20, 10, 0)
        val firstEnd = atUtc(2026, 7, 20, 10, 20)
        val secondEnd = atUtc(2026, 7, 20, 10, 30)
        seedGame(appId, playtime = 500, lastSyncAt = start)

        db.withTransaction { committer().commit(obs(520), firstEnd, firstEnd) }
        db.playerProfileDao().updateSyncStatus(firstEnd, null)
        db.withTransaction { committer().commit(obs(530), secondEnd, secondEnd) }

        val session = db.sessionDao().getAll().single()
        assertEquals(30, session.minutes) // the shared row accumulates both deltas
        val contributions = db.playtimeRecoveryDao().contributionsForGame(account, appId)
        assertEquals(2, contributions.size)
        assertEquals(setOf(20L, 10L), contributions.map { it.authorizedMinutes }.toSet())
        // Funded shares across both contributions equal the row's minutes exactly once each.
        val funded = db.playtimeRecoveryDao().fundedMinutesBySession(listOf(session.id)).single().minutes
        assertEquals(30L, funded)
        assertEquals(session.minutes.toLong(), funded)
        contributions.forEach {
            assertTrue(db.playtimeRecoveryDao().validateGraph(it.contributionId))
        }
    }

    // ----------------------------------------------------------------- 6.1 cloud bounded evidence + no cloud

    @Test
    fun boundedCloudEvidencePlacesOnlyItsShareAndResidualRecoversTheRest() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        val interval = CloudPresenceInterval(
            appId = appId, gameName = "Owned",
            startAt = atUtc(2026, 7, 22, 17, 0), endAt = atUtc(2026, 7, 22, 17, 35),
            ongoing = false, coverage = CloudCoverageState.CONTINUOUS,
            observedUntil = null, coverageLapseFrom = null, coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false,
        )
        val result = db.withTransaction {
            committer().commit(
                obs(605), friday, friday,
                placement = CloudPresencePlaytimePlacement.Input(
                    listOf(interval), CloudReaderIdentity(account, 1L),
                ),
            )
        }

        assertEquals(105, result.playedDeltaByAppId[appId])
        val sessions = db.sessionDao().getAll()
        assertEquals(1, sessions.size) // only the evidence-bounded 35 stays timestamped
        assertEquals(35, sessions.single().minutes)
        assertEquals(105, allocatedMinutes(appId) + sessions.single().minutes.toLong())
    }

    @Test
    fun gapAboveToleranceRejectsTheWholeIntervalYetEverySteamMinuteIsCredited() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        // A retained 35-minute interior gap far above the 2-minute tolerance removes the interval
        // from placement entirely — a fresh tail does not excuse it.
        val interval = CloudPresenceInterval(
            appId = appId, gameName = "Owned",
            startAt = atUtc(2026, 7, 22, 16, 0), endAt = atUtc(2026, 7, 22, 17, 0),
            ongoing = false, coverage = CloudCoverageState.CONTINUOUS,
            observedUntil = null,
            coverageLapseFrom = atUtc(2026, 7, 22, 16, 20),
            coverageLapseRecoveredAt = atUtc(2026, 7, 22, 16, 55),
            mayHaveStartedBefore = false,
        )
        val result = db.withTransaction {
            committer().commit(
                obs(605), friday, friday,
                placement = CloudPresencePlaytimePlacement.Input(
                    listOf(interval), CloudReaderIdentity(account, 1L),
                ),
            )
        }

        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(105, result.playedDeltaByAppId[appId])
        assertEquals(105, allocatedMinutes(appId))
    }

    @Test
    fun staleCloudGenerationEvidenceIsRejectedAndFallbackStillCreditsEveryMinute() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = monday)
        val settings = FakeSettingsRepository() // reader generation stays at 0
        val pruner = CloudPendingEvidencePruner(
            db.pendingCloudEvidenceDao(), FakeCredentials(account), settings,
        )
        val committer = committer(pruner = pruner)
        val interval = CloudPresenceInterval(
            appId = appId, gameName = "Owned",
            startAt = atUtc(2026, 7, 22, 17, 0), endAt = atUtc(2026, 7, 22, 17, 35),
            ongoing = false, coverage = CloudCoverageState.CONTINUOUS,
            observedUntil = null, coverageLapseFrom = null, coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false,
        )

        // Evidence minted under generation 1 cannot be accepted against a generation-0 fence.
        val placement = CloudPresencePlaytimePlacement.Input(
            listOf(interval), CloudReaderIdentity(account, 1L),
        )
        val result = committer.withValidatedPlacement(placement) { validated, pruningIdentity ->
            db.withTransaction {
                committer.commit(
                    obs(605), friday, friday,
                    placement = validated, pruningIdentity = pruningIdentity,
                )
            }
        }

        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(105, result.playedDeltaByAppId[appId])
        assertEquals(105, allocatedMinutes(appId))
    }

    @Test
    fun noCloudCommitStillPerformsLocalRecoveryThroughOrdinaryReads() = runTest {
        seedGame(appId, playtime = 500, lastSyncAt = tuesday)

        val result = db.withTransaction { committer().commit(obs(570), wednesday, wednesday) }

        assertEquals(70, result.playedDeltaByAppId[appId])
        assertTrue(db.sessionDao().getAll().isEmpty())
        assertEquals(mapOf("2026-07-22" to 70L), allocationsByDate(appId))
    }

    @Test
    fun familySharedGameNeverEntersSnapshotRecovery() = runTest {
        db.gameDao().upsert(
            Game(appId = appId, name = "Borrowed", iconUrl = "", playtimeForever = 0,
                playtime2Weeks = 0, lastPlaytime = 0, source = GameSource.FAMILY_SHARED),
        )
        db.playerProfileDao().upsert(PlayerProfile(steamId = account, lastSyncAt = monday))

        db.withTransaction { committer().commit(obs(0), wednesday, wednesday) }

        assertTrue(db.playtimeRecoveryDao().statesForAccount(account).isEmpty())
        assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, appId).isEmpty())
        assertEquals(0, allocatedMinutes(appId))
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun seedGame(appId: Long, playtime: Int, lastSyncAt: Long, isGoal: Boolean = false) {
        db.gameDao().upsert(
            Game(
                appId = appId, name = "Owned", iconUrl = "",
                playtimeForever = playtime, playtime2Weeks = 0, isGoal = isGoal,
                lastPlaytime = playtime, source = GameSource.STEAM_OWNED,
                lastSyncedAt = lastSyncAt,
            ),
        )
        db.playerProfileDao().upsert(PlayerProfile(steamId = account, lastSyncAt = lastSyncAt))
    }

    private fun obs(playtime: Int) = listOf(observed(appId, playtime))

    private fun obs(appId: Long, playtime: Int) = listOf(observed(appId, playtime))

    private fun observed(appId: Long, playtime: Int) =
        PlaytimeObservationCommitter.ObservedGame(appId, "Owned", "", playtime, 0)

    private fun committer(
        adapter: PlaytimeRecoveryCommitAdapter = PlaytimeRecoveryCommitAdapter(
            db.playtimeRecoveryDao(), db.sessionDao(),
        ),
        pruner: CloudPendingEvidencePruner? = null,
        time: TimeProvider = MutableTimeProvider(),
        gameDao: GameDao = db.gameDao(),
        sessionDao: SessionDao = db.sessionDao(),
        dailyProgressDao: DailyProgressDao = db.dailyProgressDao(),
    ): PlaytimeObservationCommitter {
        return PlaytimeObservationCommitter(
            gameDao = gameDao,
            sessionDao = sessionDao,
            dailyProgressDao = dailyProgressDao,
            profileDao = db.playerProfileDao(),
            hiddenGameDao = db.hiddenGameDao(),
            differ = SessionDiffer(),
            time = time,
            sessionActionWriter = SessionActionWriter(
                sessionDao, dailyProgressDao, db.hiddenGameDao(), time,
            ),
            recoveryAdapter = adapter,
            pendingEvidencePruner = pruner,
        )
    }

    private fun placement(targetApp: Long) = CloudPresencePlaytimePlacement.Input(
        intervals = listOf(
            CloudPresenceInterval(
                appId = targetApp,
                gameName = "Owned",
                startAt = atUtc(2026, 7, 22, 17, 0),
                endAt = atUtc(2026, 7, 22, 17, 35),
                ongoing = false,
                coverage = CloudCoverageState.CONTINUOUS,
                observedUntil = null,
                coverageLapseFrom = null,
                coverageLapseRecoveredAt = null,
                mayHaveStartedBefore = false,
            ),
        ),
        readerIdentity = CloudReaderIdentity(account, 1L),
    )

    private inner class MutableTimeProvider(var currentZone: ZoneId = ZoneOffset.UTC) : TimeProvider {
        override fun nowMillis() = friday
        override fun zone() = currentZone
        override fun today() = LocalDate.of(2026, 7, 27)
    }

    private suspend fun allocatedMinutes(appId: Long): Long =
        db.playtimeRecoveryDao().allocatedMinutesByGame(account)
            .firstOrNull { it.appId == appId }?.minutes ?: 0L

    private suspend fun allocationsByDate(appId: Long): Map<String, Long> =
        db.playtimeRecoveryDao().contributionsForGame(account, appId)
            .flatMap { db.playtimeRecoveryDao().activeAllocations(it.contributionId) }
            .filter { !it.pendingDate }
            .groupBy { it.date.orEmpty() }
            .mapValues { (_, rows) -> rows.sumOf { it.minutes } }

    private val monday = atUtc(2026, 7, 20, 20, 0)
    private val tuesday = atUtc(2026, 7, 21, 20, 0)
    private val wednesday = atUtc(2026, 7, 22, 20, 0)
    private val thursday = atUtc(2026, 7, 23, 12, 0)
    private val friday = atUtc(2026, 7, 24, 20, 0)
    private val saturday = atUtc(2026, 7, 25, 12, 0)

    private fun atUtc(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Instant.parse("%04d-%02d-%02dT%02d:%02d:00Z".format(year, month, day, hour, minute)).toEpochMilli()
}

private enum class RawObservationFailpoint {
    BASELINE_SEED,
    SESSION_OPEN,
    CONTRIBUTION_GRAPH,
    JOURNAL,
    BASELINE_ADVANCE,
    DAILY_DATE,
    DAILY_CREDIT,
    GAME_STEAM_FIELDS,
}

/** Delegates real Room writes, then throws at the selected point so Room must roll them back. */
private class FailingRawObservationRecoveryDao(
    private val delegate: PlaytimeRecoveryDao,
    private val failpoint: RawObservationFailpoint,
) : PlaytimeRecoveryDao by delegate {
    override suspend fun upsertStateIfAccountActive(state: GameRecoveryState): Boolean {
        val written = delegate.upsertStateIfAccountActive(state)
        if (failpoint == RawObservationFailpoint.BASELINE_SEED) error("raw baseline seed failpoint")
        return written
    }

    override suspend fun commitAllocatedGraph(
        contribution: PlaytimeContribution,
        timed: List<TimedContributionPlacement>,
        allocations: List<UnknownTimeAllocation>,
        recipe: UnknownTimeAllocationRecipe?,
    ): Boolean {
        val written = delegate.commitAllocatedGraph(contribution, timed, allocations, recipe)
        if (failpoint == RawObservationFailpoint.CONTRIBUTION_GRAPH) error("raw contribution graph failpoint")
        return written
    }

    override suspend fun upsertJournal(journal: PlaytimeRecoveryJournal) {
        delegate.upsertJournal(journal)
        if (failpoint == RawObservationFailpoint.JOURNAL) error("raw journal failpoint")
    }

    override suspend fun advanceBaseline(
        account: String,
        appId: Long,
        absoluteMinutes: Long,
        observedAt: Long,
        date: String?,
        zone: String?,
        epoch: Long,
    ): BaselineAdvance {
        val advanced = delegate.advanceBaseline(account, appId, absoluteMinutes, observedAt, date, zone, epoch)
        if (failpoint == RawObservationFailpoint.BASELINE_ADVANCE) error("raw baseline advance failpoint")
        return advanced
    }
}

private class FailingRawObservationSessionDao(
    private val delegate: SessionDao,
    private val failpoint: RawObservationFailpoint,
) : SessionDao by delegate {
    override suspend fun tryOpenSession(
        appId: Long,
        startAt: Long,
        endAt: Long?,
        minutes: Int,
        recoveredSharedPlay: RecoveredSharedPlayState,
        timingInformedSteamPlay: TimingInformedSteamPlayState,
    ): Long {
        val id = delegate.tryOpenSession(
            appId = appId,
            startAt = startAt,
            endAt = endAt,
            minutes = minutes,
            recoveredSharedPlay = recoveredSharedPlay,
            timingInformedSteamPlay = timingInformedSteamPlay,
        )
        if (failpoint == RawObservationFailpoint.SESSION_OPEN) error("raw session write failpoint")
        return id
    }
}

private class FailingRawObservationDailyDao(
    private val delegate: DailyProgressDao,
    private val failpoint: RawObservationFailpoint,
) : DailyProgressDao by delegate {
    override suspend fun ensureDate(date: String) {
        delegate.ensureDate(date)
        if (failpoint == RawObservationFailpoint.DAILY_DATE) error("raw daily date failpoint")
    }

    override suspend fun addMinutes(date: String, minutesPlayed: Int, goalMinutesPlayed: Int) {
        delegate.addMinutes(date, minutesPlayed, goalMinutesPlayed)
        if (failpoint == RawObservationFailpoint.DAILY_CREDIT) error("raw daily credit failpoint")
    }
}

private class FailingRawObservationGameDao(
    private val delegate: GameDao,
    private val failpoint: RawObservationFailpoint,
) : GameDao by delegate {
    override suspend fun updateSteamFields(
        appId: Long,
        name: String,
        iconUrl: String,
        playtimeForever: Int,
        playtime2Weeks: Int,
        lastPlaytime: Int,
        lastSyncedAt: Long,
        lastPlayedAt: Long?,
        returnedToPlayAt: Long?,
    ) {
        delegate.updateSteamFields(
            appId = appId,
            name = name,
            iconUrl = iconUrl,
            playtimeForever = playtimeForever,
            playtime2Weeks = playtime2Weeks,
            lastPlaytime = lastPlaytime,
            lastSyncedAt = lastSyncedAt,
            lastPlayedAt = lastPlayedAt,
            returnedToPlayAt = returnedToPlayAt,
        )
        if (failpoint == RawObservationFailpoint.GAME_STEAM_FIELDS) error("raw Steam detector pair failpoint")
    }
}

/** A storage-level failpoint: every write works until the journal lands, then the commit breaks. */
private class FailingRecoveryDao(
    private val delegate: PlaytimeRecoveryDao,
) : PlaytimeRecoveryDao by delegate {
    override suspend fun upsertJournal(journal: PlaytimeRecoveryJournal) {
        error("simulated journal failure after the raw graph was written")
    }
}
