package com.example.backlogium.data.local.dao

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.DailyPlaytimeSnapshot
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryClockStates
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.RecoverySnapshotClassifications
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
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

/**
 * Storage guarantees behind recover-missed-playtime tasks 2.2/2.3: baseline state with epoch
 * semantics, idempotent contribution identity, exact conservation, canonical reads, the account
 * barrier and the atomic graph invariants — all against the real Room schema.
 */
@RunWith(RobolectricTestRunner::class)
class PlaytimeRecoveryDaoTest {

    private lateinit var database: BacklogiumDatabase
    private lateinit var dao: PlaytimeRecoveryDao
    private var nextFixtureSessionId = 7L

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.playtimeRecoveryDao()
        runBlocking { database.playerProfileDao().upsert(profile(steamId = ACCOUNT)) }
    }

    @After
    fun tearDown() = database.close()

    // ---------------------------------------------------------------- 2.2 state & snapshots

    @Test
    fun baselineIsReadableInBulkAndByGame() = runBlocking {
        dao.upsertState(state(appId = 440L, highWater = 500L))
        dao.upsertState(state(appId = 441L, highWater = 12L))
        dao.upsertState(state(appId = 440L, highWater = 501L))

        assertEquals(2, dao.statesForAccount(ACCOUNT).size)
        assertEquals(501L, dao.statesForAccount(ACCOUNT).first { it.appId == 440L }.highWaterMinutes)
        assertEquals(501L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
        assertNull(dao.state(ACCOUNT, 999L))
    }

    @Test
    fun firstReadSeedsAndSameDayReadingReplacesTheDaysSnapshot() = runBlocking {
        dao.upsertSnapshot(snapshot(date = "2026-09-01", absolute = 500L))
        // Second successful reading the same day replaces the day's snapshot, never appends.
        dao.upsertSnapshot(snapshot(date = "2026-09-01", absolute = 570L))
        dao.upsertSnapshot(snapshot(date = "2026-09-02", absolute = 570L))

        assertEquals(570L, dao.snapshot(ACCOUNT, 440L, "2026-09-01")?.absoluteMinutes)
        assertEquals(2, dao.recentSnapshots(ACCOUNT, 440L, 10).size)
    }

    @Test
    fun regressionSnapshotIsStoredButCannotAdvanceTheHighWater() = runBlocking {
        dao.upsertState(state(appId = 440L, highWater = 500L))
        dao.upsertSnapshot(snapshot(date = "2026-09-03", absolute = 450L, classification = RecoverySnapshotClassifications.REGRESSION))

        assertEquals(500L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
        assertEquals(RecoverySnapshotClassifications.REGRESSION, dao.snapshot(ACCOUNT, 440L, "2026-09-03")?.classification)
    }

    @Test
    fun staleSameDayResponseKeepsTheDaysNewestSnapshotMetadata() = runBlocking {
        dao.advanceBaseline(ACCOUNT, 440L, 500L, observedAt = 2_000L, zone = "UTC", date = "2026-09-01")
        // An older same-day response exposes genuinely higher quantity: state recovers it, but the
        // day's raw snapshot keeps the newest observation metadata and is never rewound.
        val staleHigher = dao.advanceBaseline(ACCOUNT, 440L, 600L, observedAt = 1_000L, zone = "UTC", date = "2026-09-01")
        assertEquals(100L, staleHigher.deltaMinutes)
        assertEquals(600L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
        val stored = dao.snapshot(ACCOUNT, 440L, "2026-09-01")!!
        assertEquals(2_000L, stored.observedAt)
        assertEquals(500L, stored.absoluteMinutes)
    }

    // ---------------------------------------------------------------- 2.3 contributions

    @Test
    fun contributionInsertIsIdempotentByIdentity() = runBlocking {
        assertTrue(dao.insertContributionChecked(contribution(from = 500L, to = 605L)))
        assertFalse("Replaying the same cumulative range adds no second contribution", dao.insertContributionChecked(contribution(from = 500L, to = 605L)))
        assertEquals(1, dao.contributionsForGame(ACCOUNT, 440L).size)
        assertEquals(105L, dao.contribution(contributionId(500L, 605L))?.authorizedMinutes)
    }

    @Test
    fun conflictingReplayOfTheSameIdentityIsRejectedNotIgnored() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val conflict = contribution(from = 500L, to = 605L).copy(authorizedMinutes = 110L)
        assertTrue(runCatching { dao.insertContributionChecked(conflict) }.isFailure)
        assertEquals(105L, dao.contribution(contributionId(500L, 605L))?.authorizedMinutes)
    }

    @Test
    fun overlappingRangesWithinOneEpochAreRejectedButAdjacentAreAllowed() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        assertTrue(runCatching { dao.insertContributionChecked(contribution(from = 550L, to = 650L)) }.isFailure)
        // (605, 700] is adjacent, not overlapping, so it is legal.
        assertTrue(dao.insertContributionChecked(contribution(from = 605L, to = 700L)))
        assertEquals(2, dao.contributionsForGame(ACCOUNT, 440L).size)
    }

    @Test
    fun malformedContributionRangesAreRejected() = runBlocking {
        val base = contribution(from = 500L, to = 605L)
        assertTrue(runCatching { dao.insertContributionChecked(base.copy(account = "")) }.isFailure)
        assertTrue(runCatching { dao.insertContributionChecked(base.copy(appId = 0L)) }.isFailure)
        assertTrue(runCatching { dao.insertContributionChecked(base.copy(baselineEpoch = 0L)) }.isFailure)
        assertTrue(runCatching { dao.insertContributionChecked(base.copy(fromMinutes = -1L)) }.isFailure)
        assertTrue(runCatching { dao.insertContributionChecked(base.copy(toMinutes = 500L)) }.isFailure)
        assertTrue(runCatching { dao.insertContributionChecked(base.copy(authorizedMinutes = 200L)) }.isFailure)
    }

    @Test
    fun fullPlanConservesExactlyAndRepeatedIdentitiesAddNoCredit() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)

        dao.insertTimedPlacements(listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")))
        dao.insertAllocations(listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 70L)))
        assertTrue(dao.conservationHolds(id))

        // Replaying the identical plan (same deterministic placement ids) changes nothing.
        dao.insertTimedPlacements(listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")))
        dao.insertAllocations(listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 70L)))
        assertEquals(35L, dao.sumActiveTimedMinutes(id))
        assertEquals(70L, dao.sumActiveAllocatedMinutes(id))
        assertTrue(dao.conservationHolds(id))
    }

    @Test
    fun conflictingGraphRejectsConservationAndCannotAcceptAnotherShare() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.insertAllocations(listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 100L)))

        assertFalse(dao.canAcceptShare(id, 10L))
        assertFalse(dao.conservationHolds(id))
    }

    @Test
    fun canAcceptShareRejectsNegativeAdditionalAndOverflow() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        assertFalse("negative addition is never acceptable", dao.canAcceptShare(id, -5L))
        assertTrue("a 105-minute headroom accepts up to its full width", dao.canAcceptShare(id, 105L))
        assertFalse("overflowing additions are rejected without arithmetic overflow", dao.canAcceptShare(id, Long.MAX_VALUE))
        dao.insertAllocations(listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 80L)))
        assertTrue(dao.canAcceptShare(id, 25L))
        assertFalse(dao.canAcceptShare(id, 26L))
    }

    @Test
    fun pendingQuantityStaysUndatedAndDatedCreditGroupsByDate() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.insertAllocations(
            listOf(
                allocation(id, revision = 1L, date = "2026-09-01", minutes = 27L),
                allocation(id, revision = 1L, date = "2026-09-02", minutes = 26L),
                allocation(id, revision = 1L, date = "2026-09-03", minutes = 26L),
                allocation(id, revision = 1L, date = "2026-09-04", minutes = 26L),
            ),
        )
        val pendingId = PlaytimeRecoveryIdentity.contributionId(ACCOUNT, 441L, 1L, 0L, 20L)
        dao.insertContributionChecked(contribution(appId = 441L, from = 0L, to = 20L, authorized = 20L))
        dao.insertAllocations(
            listOf(
                allocation(
                    id = pendingId,
                    revision = 1L,
                    date = null,
                    minutes = 20L,
                    method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
                    pending = true,
                    appId = 441L,
                ),
            ),
        )

        assertEquals(105L, dao.sumActiveDatedMinutes(ACCOUNT, 440L))
        assertEquals(20L, dao.sumActivePendingMinutes(ACCOUNT, 441L))
        assertEquals(105L, dao.creditedMinutesByDate(ACCOUNT).sumOf { it.minutes })
        assertEquals(listOf("2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04"), dao.creditedMinutesByDate(ACCOUNT).map { it.date })
        assertEquals(105L, dao.allocatedMinutesByGame(ACCOUNT).first { it.appId == 440L }.minutes)
    }

    @Test
    fun revisionUpgradeSupersedesOldPlacementsAndRecomputesConservation() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)

        dao.insertAllocations(listOf(allocation(id, revision = 1L, date = "2026-09-04", minutes = 105L)))
        assertTrue(dao.conservationHolds(id))

        dao.supersedeAllocationsUpTo(id, keepRevision = 2L)
        dao.insertTimedPlacements(listOf(timedPlacement(id, revision = 2L, sessionId = 9L, minutes = 35L, creditedDate = "2026-09-04")))
        dao.insertAllocations(listOf(allocation(id, revision = 2L, date = "2026-09-04", minutes = 70L)))
        dao.advanceRevision(id, 2L)

        assertEquals(1, dao.activeAllocations(id).size)
        assertEquals(70L, dao.activeAllocations(id).single().minutes)
        assertEquals(35L, dao.sumActiveTimedMinutes(id))
        assertEquals(105L, dao.conservedAllocatedMinutes(id))
        assertTrue(dao.conservationHolds(id))
        assertEquals(2L, dao.contribution(id)?.revision)
    }

    @Test
    fun timedSharesBySessionExposeTheFundedPortionForLegacyRemainder() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.insertTimedPlacements(listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")))
        assertEquals(35L, dao.activeTimedSharesForSession(7L).single().minutes)
        assertTrue(dao.activeTimedSharesForSession(8L).isEmpty())
    }

    @Test
    fun recipesRoundTripWithExclusions() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.upsertRecipe(recipe(id = id, revision = 1L, quotient = 26L, remainder = 1L, start = "2026-09-01", end = "2026-09-04", eligible = 4, excluded = "2026-09-02"))
        val stored = dao.recipe(id)!!
        assertEquals(26L, stored.quotient)
        assertEquals(1L, stored.remainderCount)
        assertEquals("2026-09-02", stored.excludedDatesCsv)
    }

    // ---------------------------------------------------------------- account barrier

    @Test
    fun upsertStateIsRejectedWhenProfileMatchesNoAccountOrADifferentOne() = runBlocking {
        database.playerProfileDao().upsert(profile(steamId = "different-account"))
        assertFalse(dao.upsertStateIfAccountActive(state(appId = 440L, highWater = 500L)))
        assertNull(dao.state(ACCOUNT, 440L))

        database.playerProfileDao().upsert(profile(steamId = ""))
        assertFalse(dao.upsertStateIfAccountActive(state(appId = 440L, highWater = 500L)))

        database.playerProfileDao().upsert(profile(steamId = ACCOUNT))
        assertTrue(dao.upsertStateIfAccountActive(state(appId = 440L, highWater = 500L)))
        assertEquals(500L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
    }

    @Test
    fun advanceBaselineForAnInactiveAccountWritesNothing() = runBlocking {
        database.playerProfileDao().upsert(profile(steamId = "different-account"))
        val ignored = dao.advanceBaseline(ACCOUNT, 440L, 500L, observedAt = 1_000L, zone = "UTC", date = "2026-09-01")
        assertTrue("an inactive account's observation must be ignored", ignored.ignored)
        assertNull(dao.state(ACCOUNT, 440L))
        assertTrue(dao.recentSnapshots(ACCOUNT, 440L, 10).isEmpty())
    }

    // ---------------------------------------------------------------- journal

    @Test
    fun journalRecordsRevisionAndFinalizationResumeStates() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.upsertJournal(
            PlaytimeRecoveryJournal(
                operationId = "op-1",
                contributionId = id,
                account = ACCOUNT,
                appId = 440L,
                priorRevision = 1L,
                currentRevision = 2L,
                affectedDatesCsv = "2026-09-03,2026-09-04",
                replacedPlacementIdsCsv = "c:1:u:2026-09-04",
                replacedRecipeJson = "{\"quotient\":105}",
                derivedStatus = RecoveryJournalStatus.RAW_COMMITTED,
                payloadJson = "{\"earned\":[],\"corrective\":[\"$id\"]}",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        assertEquals(RecoveryJournalStatus.RAW_COMMITTED, dao.journal("op-1")?.derivedStatus)
        assertEquals("c:1:u:2026-09-04", dao.journal("op-1")?.replacedPlacementIdsCsv)
        assertEquals(listOf("op-1"), dao.pendingFinalization(ACCOUNT).map { it.operationId })

        assertEquals(1, dao.markJournalStatus("op-1", RecoveryJournalStatus.FINALIZED, 2L))
        assertEquals(RecoveryJournalStatus.FINALIZED, dao.journal("op-1")?.derivedStatus)
        assertTrue(dao.pendingFinalization(ACCOUNT).isEmpty())
    }

    // ---------------------------------------------------------------- 2.2 semantics helper

    @Test
    fun firstReadSeedsQuantityOnlyAndLaterReadsAdvanceTheBoundary() = runBlocking {
        val first = dao.advanceBaseline(ACCOUNT, 440L, 500L, observedAt = 1_000L, zone = "UTC", date = "2026-09-01")
        assertTrue(first.firstBaseline)
        assertEquals(0L, first.deltaMinutes)
        assertFalse(first.baselineAdvanced)

        val second = dao.advanceBaseline(ACCOUNT, 440L, 570L, observedAt = 2_000L, zone = "UTC", date = "2026-09-02")
        assertEquals(70L, second.deltaMinutes)
        assertTrue(second.baselineAdvanced)
        assertEquals(570L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)

        val unchanged = dao.advanceBaseline(ACCOUNT, 440L, 570L, observedAt = 3_000L, zone = "UTC", date = "2026-09-02")
        assertEquals(0L, unchanged.deltaMinutes)
        assertEquals(3_000L, dao.state(ACCOUNT, 440L)?.lastObservationAt)
    }

    @Test
    fun regressionTouchesNothingAndStaleHigherResponseRecoversConservatively() = runBlocking {
        dao.advanceBaseline(ACCOUNT, 440L, 500L, observedAt = 1_000L, zone = "UTC", date = "2026-09-01")

        val regression = dao.advanceBaseline(ACCOUNT, 440L, 450L, observedAt = 2_000L, zone = "UTC", date = "2026-09-02")
        assertTrue(regression.regression)
        assertEquals(500L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
        assertEquals(1_000L, dao.state(ACCOUNT, 440L)?.lastObservationAt)
        assertEquals(RecoverySnapshotClassifications.REGRESSION, dao.snapshot(ACCOUNT, 440L, "2026-09-02")?.classification)

        // An older response exposing genuinely new quantity advances the high-water without
        // rewinding the newer observation boundary.
        val staleHigher = dao.advanceBaseline(ACCOUNT, 440L, 605L, observedAt = 900L, zone = "UTC", date = "2026-08-31")
        assertEquals(105L, staleHigher.deltaMinutes)
        assertEquals(605L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
        assertEquals(1_000L, dao.state(ACCOUNT, 440L)?.lastObservationAt)
    }

    @Test
    fun missingGameStateSurvivesRestartAndIsComparedAgainstThePersistedBaseline() = runBlocking {
        dao.advanceBaseline(ACCOUNT, 440L, 500L, observedAt = 1_000L, zone = null, date = null)
        assertEquals(500L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
        val afterRestart = dao.advanceBaseline(ACCOUNT, 440L, 605L, observedAt = 2_000L, zone = "UTC", date = "2026-09-02")
        assertEquals(105L, afterRestart.deltaMinutes)
    }

    @Test
    fun reestablishBaselineCreatesAFreshEpochWithoutALifetimeDelta() = runBlocking {
        dao.advanceBaseline(ACCOUNT, 440L, 500L, observedAt = 1_000L, zone = "UTC", date = "2026-09-01", epoch = 1L)
        val converted = dao.reestablishBaseline(ACCOUNT, 440L, 900L, observedAt = 2_000L, zone = "UTC", date = "2026-09-02", epoch = 2L)
        assertTrue(converted.firstBaseline)
        assertEquals(0L, converted.deltaMinutes)
        assertEquals(2L, dao.state(ACCOUNT, 440L)?.baselineEpoch)
        assertEquals(900L, dao.state(ACCOUNT, 440L)?.highWaterMinutes)
    }

    // ---------------------------------------------------------------- 2.3 atomic graph & recipes

    @Test
    fun partialTimedPlusLogicalRecipeConservesExactlyOnce() = runBlocking {
        insertSession(appId = 440L, minutes = 50)
        val contribution = contribution(from = 500L, to = 605L)
        val id = contributionId(500L, 605L)
        // 35 minutes of validated timing into a session + a 70-minute logical recipe = 105.
        val recipe = recipe(id = id, revision = 1L, quotient = 17L, remainder = 2L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        assertTrue(
            dao.commitAllocatedGraph(
                contribution = contribution,
                timed = listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")),
                allocations = emptyList(),
                recipe = recipe,
            ),
        )
        assertEquals(35L, dao.sumActiveTimedMinutes(id))
        assertEquals(70L, dao.recipeResidual(id))
        assertTrue(dao.conservationHolds(id))
        assertTrue(dao.validateGraph(id))
    }

    @Test
    fun atomicGraphRejectsShareAccountOrAppMismatchAndRollsBack() = runBlocking {
        val id = contributionId(500L, 605L)
        val wrongAccountTimed = timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")
            .copy(account = "other-account")
        assertTrue(runCatching { dao.commitAllocatedGraph(contribution(from = 500L, to = 605L), listOf(wrongAccountTimed), emptyList()) }.isFailure)

        val wrongAppAllocation = allocation(id, revision = 1L, date = "2026-08-14", minutes = 70L).copy(appId = 441L)
        assertTrue(runCatching { dao.commitAllocatedGraph(contribution(from = 500L, to = 605L), emptyList(), listOf(wrongAppAllocation)) }.isFailure)

        assertNull("a rejected graph must roll back the contribution too", dao.contribution(id))
    }

    @Test
    fun atomicGraphRejectsInvalidPendingDateShapes() = runBlocking {
        val id = contributionId(500L, 605L)
        // date set but pendingDate true — an impossible shape.
        val badPending = allocation(id, revision = 1L, date = "2026-08-14", minutes = 70L).copy(pendingDate = true)
        assertTrue(runCatching { dao.commitAllocatedGraph(contribution(from = 500L, to = 605L), emptyList(), listOf(badPending)) }.isFailure)
        // date null but pendingDate false — an undefined shape.
        val badDated = allocation(id, revision = 1L, date = null, minutes = 70L).copy(pendingDate = false)
        assertTrue(runCatching { dao.commitAllocatedGraph(contribution(from = 500L, to = 605L), emptyList(), listOf(badDated)) }.isFailure)
        assertNull(dao.contribution(id))
    }

    @Test
    fun atomicGraphInsertsTimedSharesInTheRecipeBranchToo() = runBlocking {
        insertSession(appId = 440L, minutes = 40)
        val id = contributionId(500L, 605L)
        val recipe = recipe(id = id, revision = 1L, quotient = 17L, remainder = 2L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        assertTrue(
            dao.commitAllocatedGraph(
                contribution = contribution(from = 500L, to = 605L),
                timed = listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")),
                allocations = emptyList(),
                recipe = recipe,
            ),
        )
        assertEquals("timed shares must land even when a recipe is present", 35L, dao.sumActiveTimedMinutes(id))
    }

    @Test
    fun fundedSharesNeverExceedSessionMinutesAndSessionsMustMatchApp() = runBlocking {
        // Session exists but is too small for the requested share.
        insertSession(appId = 440L, minutes = 30)
        assertTrue(
            runCatching {
                dao.commitAllocatedGraph(
                    contribution(from = 500L, to = 605L),
                    listOf(timedPlacement(contributionId(500L, 605L), revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")),
                    listOf(allocation(contributionId(500L, 605L), revision = 1L, date = "2026-08-14", minutes = 70L)),
                )
            }.isFailure,
        )
        assertNull(dao.contribution(contributionId(500L, 605L)))

        // Session exists but reports the wrong app.
        insertSession(appId = 999L, minutes = 60)
        assertTrue(
            runCatching {
                dao.commitAllocatedGraph(
                    contribution(from = 605L, to = 700L),
                    listOf(timedPlacement(contributionId(605L, 700L), revision = 1L, sessionId = 8L, minutes = 20L, creditedDate = "2026-09-03")),
                    listOf(allocation(contributionId(605L, 700L), revision = 1L, date = "2026-08-15", minutes = 75L)),
                )
            }.isFailure,
        )

        // Funded total across ALL contributions for one session cannot exceed its minutes.
        insertSession(appId = 440L, minutes = 50)
        dao.commitAllocatedGraph(
            contribution(from = 500L, to = 605L),
            listOf(timedPlacement(contributionId(500L, 605L), revision = 1L, sessionId = 9L, minutes = 35L, creditedDate = "2026-09-03")),
            listOf(allocation(contributionId(500L, 605L), revision = 1L, date = "2026-08-14", minutes = 70L)),
        )
        assertTrue(
            runCatching {
                // Second contribution pushes the session's funded total to 55 > 50.
                dao.commitAllocatedGraph(
                    contribution(from = 605L, to = 625L),
                    listOf(timedPlacement(contributionId(605L, 625L), revision = 1L, sessionId = 9L, minutes = 20L, creditedDate = "2026-09-03")),
                    emptyList(),
                )
            }.isFailure,
        )
        assertNull(dao.contribution(contributionId(605L, 625L)))
    }

    @Test
    fun recipeBackedResidualCountsInConservationExactlyOnceAcrossSlices() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        val recipe = recipe(id = id, revision = 1L, quotient = 26L, remainder = 1L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        assertTrue(dao.commitAllocatedGraph(contribution(from = 500L, to = 605L), emptyList(), emptyList(), recipe))
        assertTrue(dao.conservationHolds(id))
        assertEquals(105L, dao.recipeResidual(id))

        dao.materializeRecipeSlice(
            listOf(
                allocation(id, revision = 1L, date = "2026-09-01", minutes = 27L),
                allocation(id, revision = 1L, date = "2026-09-02", minutes = 26L),
            ),
        )
        assertEquals(52L, dao.recipeResidual(id))
        assertEquals(53L, dao.sumActiveDatedForContribution(id))
        assertTrue(dao.conservationHolds(id))
        assertTrue(dao.validateGraph(id))
        assertEquals("2026-09-02", dao.recipe(id)?.materializedThroughDate)

        // Replaying the identical slice is a no-op and cannot move the residual.
        dao.materializeRecipeSlice(
            listOf(
                allocation(id, revision = 1L, date = "2026-09-01", minutes = 27L),
                allocation(id, revision = 1L, date = "2026-09-02", minutes = 26L),
            ),
        )
        assertEquals(52L, dao.recipeResidual(id))

        // A date with a non-frozen share count is rejected even though its sum fits.
        assertTrue(runCatching { dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-03", minutes = 60L))) }.isFailure)
        assertEquals(52L, dao.recipeResidual(id))

        // A correct remainder slice completes the recipe with the exact 26/26 shares.
        dao.materializeRecipeSlice(
            listOf(
                allocation(id, revision = 1L, date = "2026-09-03", minutes = 26L),
                allocation(id, revision = 1L, date = "2026-09-04", minutes = 26L),
            ),
        )
        assertEquals(0L, dao.recipeResidual(id))
        assertEquals(105L, dao.sumActiveDatedForContribution(id))
        assertTrue(dao.conservationHolds(id))
        assertTrue(dao.validateGraph(id))
        // Completing every positive date proves the whole window, so the cursor reaches the end.
        assertEquals("2026-09-04", dao.recipe(id)?.materializedThroughDate)
    }

    @Test
    fun tenYearSingleMinuteRecipeMaterializesOneRowAndCompletes() = runBlocking {
        val start = LocalDate.of(2016, 1, 1)
        val end = start.plusYears(10)
        val eligibleCount = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        val id = contributionId(0L, 1L)
        val recipe = recipe(
            id = id, revision = 1L, quotient = 0L, remainder = 1L,
            start = start.toString(), end = end.toString(), eligible = eligibleCount,
        )
        assertTrue(dao.commitAllocatedGraph(contribution(from = 0L, to = 1L), emptyList(), emptyList(), recipe))

        // A single one-minute row covers the ten-year window: no date iteration, no zero rows.
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = start.toString(), minutes = 1L)))
        assertEquals(0L, dao.recipeResidual(id))
        assertEquals(1L, dao.sumActiveDatedForContribution(id))
        assertEquals(end.toString(), dao.recipe(id)?.materializedThroughDate)
        assertTrue(dao.validateGraph(id))
    }

    @Test
    fun zeroQuotientTailNeedsNoRowsAndCursorReachesTheEnd() = runBlocking {
        // q=0, r=2 over four eligible dates: ranks 1,2 are one-minute each; ranks 3,4 are zero.
        val id = contributionId(100L, 102L)
        val recipe = recipe(id = id, revision = 1L, quotient = 0L, remainder = 2L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        assertTrue(dao.commitAllocatedGraph(contribution(from = 100L, to = 102L), emptyList(), emptyList(), recipe))

        dao.materializeRecipeSlice(
            listOf(
                allocation(id, revision = 1L, date = "2026-09-01", minutes = 1L),
                allocation(id, revision = 1L, date = "2026-09-02", minutes = 1L),
            ),
        )
        assertEquals(0L, dao.recipeResidual(id))
        // No activity rows exist for the zero tail, yet the cursor proves through the last date.
        assertEquals("2026-09-04", dao.recipe(id)?.materializedThroughDate)
        assertEquals(2, dao.activeAllocations(id).size)
        assertTrue(dao.validateGraph(id))

        // A zero-share date must never receive an activity row.
        assertTrue(runCatching { dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-03", minutes = 0L))) }.isFailure)
        assertTrue(runCatching { dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-03", minutes = 1L))) }.isFailure)
    }

    @Test
    fun excludedFirstAndInteriorDatesUseRankArithmetic() = runBlocking {
        // dateStart (09-01) and an interior date (09-03) are excluded; ranks 1 and 2 are 09-02 and 09-04.
        val id = contributionId(100L, 110L)
        val recipe = recipe(
            id = id, revision = 1L, quotient = 5L, remainder = 0L,
            start = "2026-09-01", end = "2026-09-04", eligible = 2, excluded = "2026-09-01,2026-09-03",
        )
        assertTrue(dao.commitAllocatedGraph(contribution(from = 100L, to = 110L), emptyList(), emptyList(), recipe))

        // The first row is the first ELIGIBLE date, not necessarily dateStart.
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-02", minutes = 5L)))
        assertEquals("2026-09-02", dao.recipe(id)?.materializedThroughDate)
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-04", minutes = 5L)))
        assertEquals("2026-09-04", dao.recipe(id)?.materializedThroughDate)
        assertTrue(dao.validateGraph(id))

        for (excludedDate in listOf("2026-09-01", "2026-09-03")) {
            assertTrue(runCatching { dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = excludedDate, minutes = 5L))) }.isFailure)
        }
    }

    @Test
    fun compactExcludedDateRangeUsesBoundedRankArithmetic() = runBlocking {
        val id = contributionId(100L, 103L)
        val recipe = recipe(
            id = id,
            revision = 1L,
            quotient = 1L,
            remainder = 0L,
            start = "2026-09-01",
            end = "2026-09-06",
            eligible = 3,
            excluded = "2026-09-01..2026-09-03",
        )
        assertTrue(dao.commitAllocatedGraph(contribution(from = 100L, to = 103L), emptyList(), emptyList(), recipe))
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-04", minutes = 1L)))
        assertEquals(2L, dao.recipeResidual(id))
        assertTrue(dao.validateGraph(id))
        assertTrue(runCatching {
            dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-02", minutes = 1L)))
        }.isFailure)
    }

    @Test
    fun explicitMaterializedThroughDateBindsTheProvenCursor() = runBlocking {
        val id = contributionId(100L, 102L)
        val recipe = recipe(id = id, revision = 1L, quotient = 0L, remainder = 2L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        dao.commitAllocatedGraph(contribution(from = 100L, to = 102L), emptyList(), emptyList(), recipe)

        // A cursor that would claim an unproven positive date (09-02 holds rank 2, still owed) is rejected.
        assertTrue(
            runCatching {
                dao.materializeRecipeSlice(
                    listOf(allocation(id, revision = 1L, date = "2026-09-01", minutes = 1L)),
                    materializedThroughDate = "2026-09-02",
                )
            }.isFailure,
        )
        // A cursor at/ before the first proven positive row binds exactly.
        dao.materializeRecipeSlice(
            listOf(allocation(id, revision = 1L, date = "2026-09-01", minutes = 1L)),
            materializedThroughDate = "2026-09-01",
        )
        assertEquals("2026-09-01", dao.recipe(id)?.materializedThroughDate)
        // Once the tail is proven, an explicit through-date reaches the window end without rows.
        dao.materializeRecipeSlice(
            listOf(allocation(id, revision = 1L, date = "2026-09-02", minutes = 1L)),
            materializedThroughDate = "2026-09-04",
        )
        assertEquals("2026-09-04", dao.recipe(id)?.materializedThroughDate)
        assertTrue(dao.validateGraph(id))
    }

    @Test
    fun hugeTwoHundredYearWindowIsBoundedAndCompletes() = runBlocking {
        val start = LocalDate.of(1970, 1, 1)
        val end = start.plusYears(200)
        val eligibleCount = (ChronoUnit.DAYS.between(start, end) + 1).toInt()
        val id = contributionId(0L, 1L)
        val recipe = recipe(
            id = id, revision = 1L, quotient = 0L, remainder = 1L,
            start = start.toString(), end = end.toString(), eligible = eligibleCount,
        )
        assertTrue(dao.commitAllocatedGraph(contribution(from = 0L, to = 1L), emptyList(), emptyList(), recipe))
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = start.toString(), minutes = 1L)))
        // ~73,000 calendar dates with no date-window iteration: one row completes the recipe.
        assertEquals(end.toString(), dao.recipe(id)?.materializedThroughDate)
        assertEquals(0L, dao.recipeResidual(id))
        assertTrue(dao.validateGraph(id))
    }

    @Test
    fun recipeRejectsWrongRemainderNegativeTotalAndOverflow() = runBlocking {
        val id = contributionId(500L, 505L)
        val base = recipe(id = id, revision = 1L, quotient = 1L, remainder = 0L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        // remainder >= eligibleCount is not a valid quotient/remainder form.
        assertTrue(runCatching { dao.commitAllocatedGraph(contribution(from = 500L, to = 505L), emptyList(), emptyList(), base.copy(remainderCount = 4L)) }.isFailure)
        // negative quotient is rejected before any checked arithmetic.
        assertTrue(runCatching { dao.commitAllocatedGraph(contribution(from = 500L, to = 505L), emptyList(), emptyList(), base.copy(quotient = -1L)) }.isFailure)
        // Long.MAX_VALUE * 2 overflows the checked multiply and must reject, not wrap.
        assertTrue(
            runCatching {
                dao.commitAllocatedGraph(
                    contribution(from = 500L, to = 507L),
                    emptyList(),
                    emptyList(),
                    base.copy(quotient = Long.MAX_VALUE, remainderCount = 0L, dateEnd = "2026-09-02", eligibleCount = 2),
                )
            }.isFailure,
        )
        assertNull("every rejected graph must roll the contribution back", dao.contribution(id))

        // validateGraph rejects the same overflow shape from a persisted recipe.
        dao.insertContributionChecked(contribution(from = 500L, to = 507L))
        dao.upsertRecipe(
            recipe(
                id = contributionId(500L, 507L), revision = 1L, quotient = Long.MAX_VALUE, remainder = 0L,
                start = "2026-09-01", end = "2026-09-02", eligible = 2,
            ),
        )
        assertFalse(dao.validateGraph(contributionId(500L, 507L)))
    }

    @Test
    fun malformedDuplicateAndOutOfWindowExclusionsAreRejectedWithoutCredit() = runBlocking {
        val id = contributionId(500L, 504L)
        val shape = recipe(id = id, revision = 1L, quotient = 1L, remainder = 0L,
            start = "2026-09-01", end = "2026-09-04", eligible = 4)
        for (csv in listOf("bad-date", "2026-08-31", "2026-09-02,2026-09-02", "2026-09-02,")) {
            assertTrue(runCatching {
                dao.commitAllocatedGraph(contribution(from = 500L, to = 504L), emptyList(), emptyList(),
                    shape.copy(excludedDatesCsv = csv))
            }.isFailure)
            assertNull(dao.contribution(id))
        }
    }

    @Test
    fun materializeRecipeSliceRejectsDiscontiguousRunsAndHoles() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        val recipe = recipe(id = id, revision = 1L, quotient = 26L, remainder = 1L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        dao.upsertRecipe(recipe)

        // First slice is a proven prefix.
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-01", minutes = 27L)))
        assertEquals("2026-09-01", dao.recipe(id)?.materializedThroughDate)

        // A slice with the correct shares but a hole (2026-09-02 skipped) is rejected: the cursor
        // must never claim coverage beyond what is actually proven contiguous.
        assertTrue(
            runCatching {
                dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-03", minutes = 26L)))
            }.isFailure,
        )
        assertEquals("2026-09-01", dao.recipe(id)?.materializedThroughDate)
        assertEquals(78L, dao.recipeResidual(id))

        // Closing the hole then extending is fine.
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-02", minutes = 26L)))
        dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-03", minutes = 26L)))
        assertEquals("2026-09-03", dao.recipe(id)?.materializedThroughDate)
    }

    @Test
    fun atomicGraphInsertRejectsNonConservedPlansAndRollsEverythingBack() = runBlocking {
        insertSession(appId = 440L, minutes = 40)
        val id = contributionId(500L, 605L)
        val thrown = runCatching {
            dao.commitAllocatedGraph(
                contribution = contribution(from = 500L, to = 605L),
                timed = listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")),
                allocations = listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 80L)),
            )
        }
        assertTrue("35 + 80 != 105 must reject atomically", thrown.isFailure)
        assertNull(dao.contribution(id))
        assertTrue(dao.activeTimedPlacements(id).isEmpty())
        assertTrue(dao.activeAllocations(id).isEmpty())
    }

    @Test
    fun validateGraphCatchesRealMismatchedGraphs() = runBlocking {
        val id = contributionId(500L, 605L)
        // A healthy fully-materialized graph validates.
        insertSession(appId = 440L, minutes = 40)
        assertTrue(
            dao.commitAllocatedGraph(
                contribution(from = 500L, to = 605L),
                listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")),
                listOf(
                    allocation(id, revision = 1L, date = "2026-09-01", minutes = 24L),
                    allocation(id, revision = 1L, date = "2026-09-02", minutes = 23L),
                    allocation(id, revision = 1L, date = "2026-09-03", minutes = 23L),
                ),
            ),
        )
        assertTrue(dao.validateGraph(id))

        // A share whose app does not match the contribution makes the graph invalid.
        val wrongApp = allocation(id, revision = 1L, date = "2026-09-04", minutes = 25L).copy(appId = 441L)
        dao.insertAllocations(listOf(wrongApp))
        assertFalse(dao.validateGraph(id))
    }

    @Test
    fun validateGraphDetectsAFabricatedRecipeMaterialization() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.upsertRecipe(recipe(id = id, revision = 1L, quotient = 26L, remainder = 1L, start = "2026-09-01", end = "2026-09-04", eligible = 4).copy(materializedMinutes = 999L))
        assertFalse(dao.validateGraph(id))
    }

    @Test
    fun discontiguouslyImportedMaterializedRowsMakeTheGraphInvalid() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        val recipe = recipe(id = id, revision = 1L, quotient = 26L, remainder = 1L, start = "2026-09-01", end = "2026-09-04", eligible = 4)
        dao.upsertRecipe(recipe)
        // Simulate a legacy/backup import that wrote d1 and d3 but never d2 (a hole).
        dao.insertAllocations(
            listOf(
                allocation(id, revision = 1L, date = "2026-09-01", minutes = 27L),
                allocation(id, revision = 1L, date = "2026-09-03", minutes = 26L),
            ),
        )
        dao.upsertRecipe(recipe.copy(materializedMinutes = 53L))
        // The proven prefix stops at d1; claiming d3 as materialized is dishonest.
        assertFalse(dao.validateGraph(id))
        assertTrue(runCatching { dao.materializeRecipeSlice(listOf(allocation(id, revision = 1L, date = "2026-09-02", minutes = 26L))) }.isFailure)
    }

    @Test
    fun fundedSharesCarryFrozenCreditTimeEligibilityFacts() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.insertTimedPlacements(
            listOf(
                timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")
                    .copy(hiddenAtCredit = false, goalAtCredit = true),
            ),
        )
        dao.insertAllocations(
            listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 70L).copy(hiddenAtCredit = true, goalAtCredit = false)),
        )
        assertTrue(dao.activeTimedPlacements(id).single().goalAtCredit)
        assertTrue(dao.activeAllocations(id).single().hiddenAtCredit)
    }

    // ---------------------------------------------------------------- local evidence

    @Test
    fun localEvidenceTracksContinuityAcrossGenerationsAndClearsWithAccount() = runBlocking {
        dao.upsertLocalEvidence(
            com.example.backlogium.data.local.entity.LocalTimingEvidence(
                account = ACCOUNT,
                appId = 440L,
                generation = 1L,
                firstSampleAt = 100L,
                lastSuccessAt = 200L,
                observedUntil = 200L,
                continuityLossFrom = null,
                continuityLossRecoveredAt = null,
                windowStart = 50L,
                updatedAt = 200L,
            ),
        )
        dao.upsertLocalEvidence(
            com.example.backlogium.data.local.entity.LocalTimingEvidence(
                account = ACCOUNT,
                appId = 440L,
                generation = 2L,
                firstSampleAt = 300L,
                lastSuccessAt = 400L,
                observedUntil = null,
                continuityLossFrom = 300L,
                continuityLossRecoveredAt = null,
                windowStart = 250L,
                updatedAt = 400L,
            ),
        )
        assertEquals(400L, dao.localEvidence(ACCOUNT, 440L, 2L)?.lastSuccessAt)
        assertEquals(2, dao.evidenceForAccount(ACCOUNT).size)

        dao.clearForAccount(ACCOUNT)
        assertTrue(dao.evidenceForAccount(ACCOUNT).isEmpty())
    }

    // ---------------------------------------------------------------- reset & cascade

    @Test
    fun clearingContributionsCascadesPlacementsAllocationsRecipesAndJournal() = runBlocking {
        dao.insertContributionChecked(contribution(from = 500L, to = 605L))
        val id = contributionId(500L, 605L)
        dao.insertTimedPlacements(listOf(timedPlacement(id, revision = 1L, sessionId = 7L, minutes = 35L, creditedDate = "2026-09-03")))
        dao.insertAllocations(listOf(allocation(id, revision = 1L, date = "2026-08-14", minutes = 70L)))
        dao.upsertRecipe(recipe(id = id, revision = 1L, quotient = 0L, remainder = 0L))
        dao.upsertJournal(
            PlaytimeRecoveryJournal(
                operationId = "op-2",
                contributionId = id,
                account = ACCOUNT,
                appId = 440L,
                currentRevision = 1L,
                affectedDatesCsv = "2026-08-14",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        dao.clearContributionsForAccount(ACCOUNT)

        assertTrue(dao.contributionsForGame(ACCOUNT, 440L).isEmpty())
        assertTrue(dao.activeTimedPlacements(id).isEmpty())
        assertTrue(dao.activeAllocations(id).isEmpty())
        assertNull(dao.recipe(id))
        assertNull(dao.journal("op-2"))
    }

    @Test
    fun resetClearAllAndClearForAccountRemoveEveryRecoveryRow() = runBlocking {
        val other = "76561198000000002"
        dao.upsertState(state(appId = 440L, highWater = 500L))
        dao.upsertState(state(account = other, appId = 441L, highWater = 55L))
        dao.upsertSnapshot(snapshot(date = "2026-09-01", absolute = 500L))

        dao.clearForAccount(ACCOUNT)
        assertTrue(dao.statesForAccount(ACCOUNT).isEmpty())
        assertEquals(1, dao.statesForAccount(other).size)

        dao.upsertState(state(appId = 440L, highWater = 501L))
        dao.clearAll()
        assertTrue(dao.statesForAccount(ACCOUNT).isEmpty())
        assertTrue(dao.statesForAccount(other).isEmpty())
        assertTrue(dao.recentSnapshots(ACCOUNT, 440L, 10).isEmpty())
    }

    // ---------------------------------------------------------------- fixtures

    private suspend fun insertSession(appId: Long, minutes: Int): Long {
        database.gameDao().upsert(com.example.backlogium.data.local.entity.Game(
            appId = appId, name = "Funded session fixture", iconUrl = "",
            playtimeForever = 0, playtime2Weeks = 0, lastPlaytime = 0,
        ))
        return database.sessionDao().insert(
            Session(
                id = nextFixtureSessionId++,
                appId = appId,
                startAt = 1L,
                minutes = minutes,
                open = false,
            ),
        )
    }

    private fun profile(steamId: String) =
        com.example.backlogium.data.local.entity.PlayerProfile(
            steamId = steamId,
            gamificationConfigVersion = 5L,
        )

    private fun state(
        account: String = ACCOUNT,
        appId: Long,
        highWater: Long,
        epoch: Long = 1L,
    ) = GameRecoveryState(
        account = account,
        appId = appId,
        baselineEpoch = epoch,
        highWaterMinutes = highWater,
        lastObservationAt = null,
        lastObservationDate = null,
        lastObservationZone = null,
        clockState = RecoveryClockStates.UNKNOWN,
        updatedAt = 1L,
    )

    private fun snapshot(
        account: String = ACCOUNT,
        date: String,
        absolute: Long,
        classification: String = RecoverySnapshotClassifications.TRUSTED,
    ) = DailyPlaytimeSnapshot(
        account = account,
        appId = 440L,
        date = date,
        zone = "UTC",
        absoluteMinutes = absolute,
        observedAt = 1L,
        classification = classification,
    )

    private fun contribution(
        account: String = ACCOUNT,
        appId: Long = 440L,
        from: Long,
        to: Long,
        authorized: Long = to - from,
    ) = PlaytimeContribution(
        contributionId = PlaytimeRecoveryIdentity.contributionId(account, appId, 1L, from, to),
        account = account,
        appId = appId,
        baselineEpoch = 1L,
        fromMinutes = from,
        toMinutes = to,
        windowEndAt = 2L,
        authorizedMinutes = authorized,
        revision = 1L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY,
        zone = "UTC",
        observedZone = "UTC",
        createdAt = 1L,
    )

    private fun contributionId(from: Long, to: Long, account: String = ACCOUNT) =
        PlaytimeRecoveryIdentity.contributionId(account, 440L, 1L, from, to)

    private fun timedPlacement(
        contributionId: String,
        revision: Long,
        sessionId: Long,
        minutes: Long,
        creditedDate: String,
    ) = TimedContributionPlacement(
        placementId = PlaytimeRecoveryIdentity.timedPlacementId(
            contributionId, revision, "evidence-1", creditedDate, sessionId,
        ),
        contributionId = contributionId,
        account = ACCOUNT,
        appId = 440L,
        revision = revision,
        sessionId = sessionId,
        minutes = minutes,
        creditedDate = creditedDate,
        zone = "UTC",
        evidenceQuality = RecoveryEvidenceQuality.LOCAL,
        createdAt = 1L,
    )

    private fun allocation(
        id: String,
        revision: Long,
        date: String?,
        minutes: Long,
        method: String = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
        pending: Boolean = false,
        appId: Long = 440L,
    ) = UnknownTimeAllocation(
        placementId = PlaytimeRecoveryIdentity.allocationPlacementId(id, revision, date),
        contributionId = id,
        account = ACCOUNT,
        appId = appId,
        revision = revision,
        date = date,
        minutes = minutes,
        method = if (pending) RecoveryAllocationMethods.PENDING_DATE_UNKNOWN else method,
        zone = "UTC",
        pendingDate = pending,
        createdAt = 1L,
    )

    private fun recipe(
        id: String,
        revision: Long,
        quotient: Long,
        remainder: Long,
        start: String? = null,
        end: String? = null,
        eligible: Int = 0,
        excluded: String? = null,
    ) = UnknownTimeAllocationRecipe(
        contributionId = id,
        account = ACCOUNT,
        appId = 440L,
        revision = revision,
        method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
        quotient = quotient,
        remainderCount = remainder,
        dateStart = start,
        dateEnd = end,
        eligibleCount = eligible,
        zone = "UTC",
        excludedDatesCsv = excluded,
        updatedAt = 1L,
    )

    companion object {
        private const val ACCOUNT = "76561198000000000"
    }
}
