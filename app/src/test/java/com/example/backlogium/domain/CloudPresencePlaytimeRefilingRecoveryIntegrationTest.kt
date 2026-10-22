package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.data.local.entity.CloudHistoricalStates
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.domain.GameSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Task 6.4: the historical re-filing/apply/reversal is contribution-revision aware. Re-filing never
 * moves a session that carries an active funded recovery share; reversal never deletes a row the
 * recovery graph later funded and never resurrects minutes a correction already removed. Imported
 * balance transfers keep their own confirmation/journals (unchanged surface, covered by
 * [CloudPresencePlaytimeRefilingUseCaseTest]).
 */
@RunWith(RobolectricTestRunner::class)
class CloudPresencePlaytimeRefilingRecoveryIntegrationTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var settings: DataStoreSettingsRepository
    private lateinit var useCase: CloudPresencePlaytimeRefilingUseCase
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
        useCase = CloudPresencePlaytimeRefilingUseCase(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            playerProfileDao = database.playerProfileDao(),
            cloudHistoricalDao = database.cloudHistoricalDao(),
            settings = settings,
            recoveryDao = database.playtimeRecoveryDao(),
            gamificationUpdater = updater,
            time = FixedTime,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            transaction = RoomDatabaseTransactionScope(database),
        )
        kotlinx.coroutines.runBlocking {
            settings.clearCloudPresenceRefiling()
            database.playerProfileDao().upsert(PlayerProfile(steamId = ACCOUNT))
            database.gameDao().upsert(game())
        }
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.runBlocking { settings.clearCloudPresenceRefiling() }
        database.close()
    }

    @Test
    fun applyNeverMovesASessionThatCarriesAnActiveFundedShare() = runTest {
        val id = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 35,
                open = false,
            ),
        )
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 500L, 535L)
        database.playtimeRecoveryDao().insertContributionChecked(
            PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = GAME,
                baselineEpoch = 1L,
                fromMinutes = 500L,
                toMinutes = 535L,
                windowStartAt = utc("2026-07-25T23:50:00Z"),
                windowEndAt = utc("2026-07-26T00:10:00Z"),
                authorizedMinutes = 35L,
                revision = 1L,
                provenance = RecoveryProvenance.RETROACTIVE_PLAY,
                zone = "UTC",
                createdAt = 1L,
            ),
        )
        database.playtimeRecoveryDao().insertTimedPlacements(
            listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contributionId, 1L, "ev-1", "2026-07-26", id,
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    sessionId = id,
                    minutes = 35L,
                    creditedDate = "2026-07-26",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.CLOUD,
                    createdAt = 1L,
                ),
            ),
        )
        val before = database.sessionDao().getAll().single()
        val canonicalBefore = database.sessionDao().creditedMinutesByGame().single().minutes
        assertEquals(35, canonicalBefore)

        val applied = useCase.apply(
            listOf(interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z"))),
        )

        assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
        assertEquals("the funded session is not refilable", 0, applied.sessionsRefiled)
        // The funded placement is authoritative: its hosting session is never re-placed.
        assertEquals(before, database.sessionDao().getAll().single())
        assertEquals(canonicalBefore, database.sessionDao().creditedMinutesByGame().single().minutes)
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contributionId))
    }

    @Test
    fun reversalPreservesAFundedShareAcquiredOnACreatedRowAfterApply() = runTest {
        database.gameDao().upsert(game().copy(isGoal = true))
        database.playerProfileDao().upsert(
            database.playerProfileDao().get()!!.copy(longestStreak = 22),
        )
        val firstOriginalId = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 30,
                open = false,
            ),
        )
        val unrelatedOriginal = Session(
            appId = GAME,
            startAt = utc("2026-07-26T00:30:00Z"),
            endAt = utc("2026-07-26T00:50:00Z"),
            minutes = 20,
            open = false,
        )
        val unrelatedOriginalId = database.sessionDao().insert(unrelatedOriginal)
        val operation = CloudHistoricalOperation(
            operationId = "reversal-funded-child",
            account = ACCOUNT,
            readerGeneration = 5L,
            endpointIdentity = "https://presence.example.test/readPresence",
            startChoice = CloudPresenceHistoricalStartChoice.CUSTOM_LOCAL_DATE.name,
            zoneId = "UTC",
            selectedStartAt = utc("2026-07-25T00:00:00Z"),
            fromAt = utc("2026-07-25T00:00:00Z"),
            throughAt = utc("2026-07-26T12:00:00Z"),
            pagesFetched = 1,
            transitionsFetched = 4,
            coveredStartAt = utc("2026-07-25T00:00:00Z"),
            coveredEndAt = utc("2026-07-26T12:00:00Z"),
            acquisitionComplete = true,
            state = CloudHistoricalStates.COMPLETE,
            createdAt = 1L,
            updatedAt = 1L,
        )
        check(database.cloudHistoricalDao().insertOperation(operation) != -1L)
        listOf(
            interval(utc("2026-07-25T23:55:00Z"), utc("2026-07-26T00:00:00Z")),
            interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z")),
            interval(utc("2026-07-26T00:30:00Z"), utc("2026-07-26T00:35:00Z")),
            interval(utc("2026-07-26T00:45:00Z"), utc("2026-07-26T00:50:00Z")),
        ).forEach { row ->
            database.cloudHistoricalDao().insertInterval(
                CloudHistoricalInterval(
                    operationId = operation.operationId,
                    account = ACCOUNT,
                    readerGeneration = operation.readerGeneration,
                    endpointIdentity = operation.endpointIdentity,
                    appId = GAME,
                    startAt = row.startAt,
                    endAt = row.endAt,
                    ongoing = false,
                    coverage = row.coverage.name,
                    observedUntil = row.observedUntil,
                    coverageLapseFrom = row.coverageLapseFrom,
                    coverageLapseRecoveredAt = row.coverageLapseRecoveredAt,
                    mayHaveStartedBefore = row.mayHaveStartedBefore,
                    gameName = row.gameName,
                    windowStart = operation.fromAt,
                ),
            )
        }
        val applied = useCase.applyHistorical(operation)
        assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
        assertEquals(2, applied.sessionsRefiled)

        // An independent recovery upgrade later funds one of the re-file's created split rows.
        val sessionsAfterApply = database.sessionDao().getAll()
        val fundedFamilyBefore = sessionsAfterApply.filter {
            it.id == firstOriginalId || it.startAt < utc("2026-07-26T00:10:00Z")
        }
        val created = sessionsAfterApply.first { it.startAt == utc("2026-07-26T00:00:00Z") }
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 535L, 550L)
        database.playtimeRecoveryDao().insertContributionChecked(
            PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = GAME,
                baselineEpoch = 1L,
                fromMinutes = 535L,
                toMinutes = 550L,
                windowStartAt = utc("2026-07-26T00:00:00Z"),
                windowEndAt = utc("2026-07-26T00:10:00Z"),
                authorizedMinutes = 15L,
                revision = 2L,
                provenance = RecoveryProvenance.RETROACTIVE_PLAY,
                zone = "UTC",
                createdAt = 1L,
            ),
        )
        database.playtimeRecoveryDao().insertTimedPlacements(
            listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contributionId, 2L, "upgrade-ev", "2026-07-26", created.id,
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 2L,
                    sessionId = created.id,
                    minutes = 15L,
                    creditedDate = "2026-07-26",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.LOCAL,
                    goalAtCredit = true,
                    createdAt = 1L,
                ),
            ),
        )
        check(database.playtimeRecoveryDao().conservationHolds(contributionId))
        assertTrue(database.playtimeRecoveryDao().validateGraph(contributionId))
        database.dailyProgressDao().ensureDate("2026-07-26")
        updater.recompute(FixedTime.today(), RecomputeSource.SYNC)
        val canonicalBeforeReverse = database.sessionDao().creditedMinutesByGame().single().minutes
        val progressBeforeReverse = database.dailyProgressDao().getAllOrdered()
        val totalXpBeforeReverse = database.playerProfileDao().get()!!.totalXp
        val longestBeforeReverse = database.playerProfileDao().get()!!.longestStreak

        val reversed = useCase.reverse()

        assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
        // The funded split group remains as a unit, while the unrelated unfunded split from the
        // same app restores fully. Recovery revision state on an app must not shrink that group.
        val sessions = database.sessionDao().getAll()
        assertTrue("funded created row must survive", sessions.any { it.id == created.id && it.minutes == 20 })
        val fundedFamilyIds = fundedFamilyBefore.map { it.id }.toSet()
        assertEquals(fundedFamilyBefore, sessions.filter { it.id in fundedFamilyIds })
        assertEquals(unrelatedOriginal.copy(id = unrelatedOriginalId), sessions.single { it.id == unrelatedOriginalId })
        assertEquals(3, sessions.size)
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contributionId))
        assertEquals(canonicalBeforeReverse, database.sessionDao().creditedMinutesByGame().single().minutes)
        val progressAfterReverse = database.dailyProgressDao().getAllOrdered()
        assertEquals(progressBeforeReverse.sumOf { it.minutesPlayed }, progressAfterReverse.sumOf { it.minutesPlayed })
        assertEquals(progressBeforeReverse.sumOf { it.goalMinutesPlayed }, progressAfterReverse.sumOf { it.goalMinutesPlayed })
        assertEquals(totalXpBeforeReverse, database.playerProfileDao().get()!!.totalXp)
        assertTrue(database.playerProfileDao().get()!!.longestStreak >= longestBeforeReverse)
    }

    @Test
    fun legacyReversalWithFundedChildRetainsItsWholeSplitFamilyWithoutDoubleCounting() = runTest {
        val firstOriginalId = database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 30,
                open = false,
            ),
        )
        val unrelatedOriginal = Session(
            appId = GAME,
            startAt = utc("2026-07-26T00:30:00Z"),
            endAt = utc("2026-07-26T00:50:00Z"),
            minutes = 20,
            open = false,
        )
        val unrelatedOriginalId = database.sessionDao().insert(unrelatedOriginal)
        val applied = useCase.apply(
            listOf(
                interval(utc("2026-07-25T23:55:00Z"), utc("2026-07-26T00:00:00Z")),
                interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z")),
                interval(utc("2026-07-26T00:30:00Z"), utc("2026-07-26T00:35:00Z")),
                interval(utc("2026-07-26T00:45:00Z"), utc("2026-07-26T00:50:00Z")),
            ),
        )
        assertEquals(CloudPresenceRefilingOperation.APPLIED, applied.operation)
        assertEquals(2, applied.sessionsRefiled)
        val splitRowsBeforeFunding = database.sessionDao().getAll()
        val fundedFamilyBefore = splitRowsBeforeFunding.filter {
            it.id == firstOriginalId || it.startAt < utc("2026-07-26T00:10:00Z")
        }
        val fundedChild = splitRowsBeforeFunding.first { it.startAt == utc("2026-07-26T00:00:00Z") }
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 535L, 550L)
        database.playtimeRecoveryDao().insertContributionChecked(
            PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = GAME,
                baselineEpoch = 1L,
                fromMinutes = 535L,
                toMinutes = 550L,
                windowStartAt = utc("2026-07-26T00:00:00Z"),
                windowEndAt = utc("2026-07-26T00:10:00Z"),
                authorizedMinutes = 15L,
                revision = 2L,
                provenance = RecoveryProvenance.RETROACTIVE_PLAY,
                zone = "UTC",
                createdAt = 1L,
            ),
        )
        database.playtimeRecoveryDao().insertTimedPlacements(
            listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contributionId, 2L, "legacy-upgrade", "2026-07-26", fundedChild.id,
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 2L,
                    sessionId = fundedChild.id,
                    minutes = 15L,
                    creditedDate = "2026-07-26",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.LOCAL,
                    createdAt = 1L,
                ),
            ),
        )
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contributionId))
        assertTrue(database.playtimeRecoveryDao().validateGraph(contributionId))
        val canonicalBeforeReverse = database.sessionDao().creditedMinutesByGame().single().minutes

        val reversed = useCase.reverse()

        assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
        val reversedSessions = database.sessionDao().getAll()
        val fundedFamilyIds = fundedFamilyBefore.map { it.id }.toSet()
        assertEquals(fundedFamilyBefore, reversedSessions.filter { it.id in fundedFamilyIds })
        assertEquals(unrelatedOriginal.copy(id = unrelatedOriginalId), reversedSessions.single { it.id == unrelatedOriginalId })
        assertEquals(3, reversedSessions.size)
        assertEquals(canonicalBeforeReverse, database.sessionDao().creditedMinutesByGame().single().minutes)
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contributionId))
    }

    @Test
    fun reversalDoesNotResurrectMinutesARecoveryCorrectionAlreadyRemoved() = runTest {
        database.sessionDao().insert(
            Session(
                appId = GAME,
                startAt = utc("2026-07-25T23:50:00Z"),
                endAt = utc("2026-07-26T00:10:00Z"),
                minutes = 30,
                open = false,
            ),
        )
        val applied = useCase.apply(
            listOf(interval(utc("2026-07-26T00:00:00Z"), utc("2026-07-26T00:10:00Z"))),
        )
        assertEquals(1, applied.sessionsRefiled)

        // After apply, an independent recovery revision moves five minutes to a dated residual and
        // shrinks the row to its remaining 25-minute funded share.
        val contributionId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, GAME, 1L, 500L, 530L)
        val contribution = PlaytimeContribution(
            contributionId = contributionId,
            account = ACCOUNT,
            appId = GAME,
            baselineEpoch = 1L,
            fromMinutes = 500L,
            toMinutes = 530L,
            windowStartAt = utc("2026-07-26T00:00:00Z"),
            windowEndAt = utc("2026-07-26T00:10:00Z"),
            authorizedMinutes = 30L,
            revision = 1L,
            provenance = RecoveryProvenance.RETROACTIVE_PLAY,
            zone = "UTC",
            createdAt = 1L,
        )
        val refiled = database.sessionDao().getAll().single()
        database.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = listOf(
                TimedContributionPlacement(
                    placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                        contributionId, 1L, "preserved-evidence", "2026-07-26", refiled.id,
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    sessionId = refiled.id,
                    minutes = 25L,
                    creditedDate = "2026-07-26",
                    zone = "UTC",
                    evidenceQuality = RecoveryEvidenceQuality.LOCAL,
                    createdAt = 1L,
                ),
            ),
            allocations = listOf(
                UnknownTimeAllocation(
                    placementId = PlaytimeRecoveryIdentity.allocationPlacementId(
                        contributionId, 1L, "2026-07-26",
                    ),
                    contributionId = contributionId,
                    account = ACCOUNT,
                    appId = GAME,
                    revision = 1L,
                    date = "2026-07-26",
                    minutes = 5L,
                    method = RecoveryAllocationMethods.SINGLE_DAY,
                    zone = "UTC",
                    windowStartAt = contribution.windowStartAt,
                    windowEndAt = contribution.windowEndAt,
                    createdAt = 1L,
                ),
            ),
        )
        database.sessionDao().update(refiled.copy(minutes = 25))
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contributionId))
        assertTrue(database.playtimeRecoveryDao().validateGraph(contributionId))
        val canonicalBeforeReverse = database.sessionDao().creditedMinutesByGame().single().minutes
        assertEquals(30, canonicalBeforeReverse)

        val reversed = useCase.reverse()

        assertEquals(CloudPresenceRefilingOperation.REVERSED, reversed.operation)
        val restored = database.sessionDao().getAll().single()
        assertEquals(
            "the correction's reduced shape is preserved, not overwritten by the pre-apply total",
            25,
            restored.minutes,
        )
        assertTrue(database.playtimeRecoveryDao().conservationHolds(contributionId))
        assertTrue(database.playtimeRecoveryDao().validateGraph(contributionId))
        assertEquals(canonicalBeforeReverse, database.sessionDao().creditedMinutesByGame().single().minutes)
    }

    private suspend fun interval(startAt: Long, endAt: Long): CloudPresenceInterval =
        CloudPresenceInterval(
            appId = GAME,
            gameName = "Portal",
            startAt = startAt,
            endAt = endAt,
            ongoing = false,
            coverage = CloudCoverageState.CONTINUOUS,
            observedUntil = null,
            coverageLapseFrom = null,
            coverageLapseRecoveredAt = null,
            mayHaveStartedBefore = false,
        )

    private fun game() = Game(
        appId = GAME,
        name = "Portal",
        iconUrl = "",
        playtimeForever = 535,
        playtime2Weeks = 0,
        lastPlaytime = 535,
        source = GameSource.STEAM_OWNED,
    )

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = utc("2026-07-26T12:00:00Z")
        override fun zone(): ZoneOffset = ZoneOffset.UTC
        override fun today(): LocalDate = LocalDate.of(2026, 7, 26)
    }

    private companion object {
        const val ACCOUNT = "76561198000000001"
        const val GAME = 440L

        fun utc(value: String): Long = Instant.parse(value).toEpochMilli()
    }
}
