package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.RecoveryReadStatusStore
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.domain.PlaytimeAllocationMethod
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * The canonical read-model boundary (recover-missed-playtime 5.1/5.7): real Room rows prove the
 * repository projections reconcile mixed sessions, allocation-only games, pending quantity, recipes
 * and hidden games — while recency, session counts and session-shape reads stay session-only.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryRecoveryProjectionTest {

    private lateinit var db: BacklogiumDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var hidden: HiddenGamesRepository

    private val account = "76561198000000001"
    private val zone: ZoneId = ZoneId.of("UTC")
    private val day = LocalDate.of(2026, 6, 8)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        hidden = HiddenGamesRepository(
            hiddenGameDao = db.hiddenGameDao(),
            gameDao = db.gameDao(),
            storeCacheDao = db.gameGenreCacheDao(),
            time = HiddenGamesTestTime,
        )
        sessions = SessionRepository(sessionDao = db.sessionDao(), hiddenGamesRepository = hidden, time = HiddenGamesTestTime)
        db.playerProfileDao().upsert(PlayerProfile(steamId = account))
        db.gameDao().upsertAll((10L..93L).map { appId ->
            Game(appId, "Recovery fixture $appId", "", 0, 0, 0)
        })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun mixedSessionCountsContributionsOncePlusItsUnlinkedRemainder() = runTest {
        val sessionId = db.sessionDao().insert(session(10L, day, minutes = 40))
        db.playtimeRecoveryDao().insertContribution(contribution("c1", 10L, 10))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(timed("t1", "c1", 10L, sessionId, minutes = 10, day)),
        )

        assertEquals(mapOf(10L to 40), sessions.trackedMinutesByGame.first())
        // The session count and last-play instant come from the session row alone.
        assertEquals(mapOf(10L to 1), sessions.sessionCountByGame.first())
        assertEquals(day, LocalDate.ofInstant(java.time.Instant.ofEpochMilli(
            sessions.latestSessionAtByGame.first()[10L]!!), zone))
    }

    @Test
    fun allocationOnlyGameCountsWithoutInventingAnySessionShape() = runTest {
        db.playtimeRecoveryDao().insertContribution(contribution("c2", 20L, 70))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a1", "c2", 20L, day, 70, RecoveryAllocationMethods.SINGLE_DAY)),
        )

        assertEquals(mapOf(20L to 70), sessions.trackedMinutesByGame.first())
        assertNull(sessions.earliestSessionStart.first())
        assertEquals(day, sessions.earliestCanonicalActivityDate.first())
        assertTrue(sessions.sessionCountByGame.first().isEmpty())
        assertTrue(sessions.latestSessionAtByGame.first().isEmpty())
        val dated = PlaytimeReadProjectorVia.datedTotals(sessions, zone, day)
        assertEquals(mapOf(20L to 70L), dated.single().minutesByGame)
    }

    @Test
    fun pendingQuantityCountsInXpButNeverInDatedCharts() = runTest {
        db.playtimeRecoveryDao().insertContribution(contribution("c3", 30L, 45))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(
                UnknownTimeAllocation(
                    placementId = "p1", contributionId = "c3", account = account, appId = 30L,
                    revision = 1L, date = null, minutes = 45,
                    method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN, pendingDate = true,
                    createdAt = 0L,
                ),
            ),
        )

        assertEquals(mapOf(30L to 45), sessions.trackedMinutesByGame.first())
        assertTrue(PlaytimeReadProjectorVia.datedTotals(sessions, zone, day).isEmpty())
        // Pending quantity is not a session: no session count, no last-play instant.
        assertTrue(sessions.sessionCountByGame.first().isEmpty())
    }

    @Test
    fun activityBetweenShowsDatedAllocationWithoutClockTimes() = runTest {
        val sessionId = db.sessionDao().insert(
            session(10L, day, minutes = 60, open = false),
        )
        db.playtimeRecoveryDao().insertContribution(contribution("c4", 10L, 20))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(timed("t4", "c4", 10L, sessionId, minutes = 20, day)),
        )
        db.playtimeRecoveryDao().insertContribution(contribution("c5", 20L, 70))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a5", "c5", 20L, day, 70, RecoveryAllocationMethods.SINGLE_DAY)),
        )

        val activities = sessions.activityBetween(zone, day, day.plusDays(1)).first()
        val sessionActivity = activities.filterIsInstance<PlaytimeActivity.TimestampedSession>().single()
        assertEquals(20L, sessionActivity.fundedMinutes)
        assertEquals(40L, sessionActivity.unlinkedMinutes)
        val allocationActivity = activities.filterIsInstance<PlaytimeActivity.DatedAllocation>().single()
        assertEquals(day, allocationActivity.date)
        assertEquals(PlaytimeAllocationMethod.SINGLE_DAY_ESTIMATE, allocationActivity.method)
    }

    @Test
    fun hiddenGameExcludedFromCanonicalFigureWithoutLosingRow() = runTest {
        db.playtimeRecoveryDao().insertContribution(contribution("c6", 40L, 70))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a6", "c6", 40L, day, 70, RecoveryAllocationMethods.SINGLE_DAY)),
        )
        hidden.hide(listOf(40L))

        assertTrue(sessions.trackedMinutesByGame.first().isEmpty())
        assertTrue(sessions.datedRecoveryActivity().first().isEmpty())
    }

    @Test
    fun overfundedMappingIsRejectedByTheCanonicalFlow() = runTest {
        val sessionId = db.sessionDao().insert(session(50L, day, minutes = 10))
        db.playtimeRecoveryDao().insertContribution(contribution("c7", 50L, 100))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(timed("t7", "c7", 50L, sessionId, minutes = 40, day)),
        )
        try {
            sessions.trackedMinutesByGame.first()
            fail("an overfunded session must reject the canonical mapping")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.startsWith("overfunded session"))
        }
    }

    @Test
    fun recipeTailIsCombinedExactlyOnceWithMaterializedRows() = runTest {
        db.playtimeRecoveryDao().insertContribution(contribution("c8", 60L, 105))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a8", "c8", 60L, day, 27, RecoveryAllocationMethods.MULTI_DAY_ESTIMATE)),
        )
        db.playtimeRecoveryDao().upsertRecipe(
            UnknownTimeAllocationRecipe(
                contributionId = "c8", account = account, appId = 60L, revision = 1L,
                method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
                quotient = 26, remainderCount = 1, eligibleCount = 4,
                dateStart = day.toString(), dateEnd = day.plusDays(3).toString(), zone = "UTC",
                materializedMinutes = 27, materializedThroughDate = day.toString(),
                updatedAt = 0L,
            ),
        )

        // The game's canonical total is the full contribution even while only the first date is
        // materialized; the activity slice derives the exact 26/26/26 tail.
        assertEquals(mapOf(60L to 105), sessions.trackedMinutesByGame.first())
        val activities = sessions.activityBetween(zone, day, day.plusDays(4)).first()
        val derivedDates = activities.filterIsInstance<PlaytimeActivity.DatedAllocation>()
            .filter { it.appId == 60L }
            .sortedBy { it.date }
        assertEquals(
            listOf(day to 27L, day.plusDays(1) to 26L, day.plusDays(2) to 26L, day.plusDays(3) to 26L),
            derivedDates.map { it.date to it.minutes },
        )
    }

    @Test
    fun daoSuspendCanonicalReadMatchesTheRepositoryFlow() = runTest {
        val sessionId = db.sessionDao().insert(session(70L, day, minutes = 35))
        db.playtimeRecoveryDao().insertContribution(contribution("c9", 70L, 105))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(timed("t9", "c9", 70L, sessionId, minutes = 35, day)),
        )
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a9", "c9", 70L, day, 70, RecoveryAllocationMethods.SINGLE_DAY)),
        )

        val viaDao = db.sessionDao().creditedMinutesByGame().associate { it.appId to it.minutes }
        val viaRepo = sessions.trackedMinutesByGame.first()
        assertEquals(mapOf(70L to 105), viaDao)
        assertEquals(viaDao, viaRepo)
    }

    @Test
    fun datedTotalsKeepFundedSharesOnTheirFrozenDateWhileRemainderStaysOnTheSessionDate() = runTest {
        val frozenDate = day.plusDays(1)
        val sessionId = db.sessionDao().insert(session(80L, day, minutes = 60))
        db.playtimeRecoveryDao().insertContribution(contribution("c-freeze", 80L, 20))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(timed("t-freeze", "c-freeze", 80L, sessionId, minutes = 20, frozenDate)),
        )

        val totals = sessions.datedCreditedTotalsBetween(zone, day, frozenDate.plusDays(1)).first()

        // The unlinked remainder (40) is credited to the session's own date; the validated funded
        // share (20) stays frozen regardless of the session row's current-zone date — no travel
        // re-bucketing of funded timed credit.
        assertEquals(
            mapOf(day to 40L, frozenDate to 20L),
            totals.associate { it.date to it.minutes },
        )
    }

    /** Keeps the dated-total projection reachable without bloating the test body. */
    private object PlaytimeReadProjectorVia {
        suspend fun datedTotals(
            sessions: SessionRepository,
            zone: ZoneId,
            start: LocalDate,
        ) = sessions.datedCreditedTotalsBetween(zone, start, start.plusDays(1)).first()
    }

    @Test
    fun datedAllocationsBetweenExposesTheUiLeafModelWithWindowContext() = runTest {
        db.playtimeRecoveryDao().insertContribution(contribution("c-alloc", 90L, 70))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(
                UnknownTimeAllocation(
                    placementId = "alloc-1", contributionId = "c-alloc", account = account, appId = 90L,
                    revision = 1L, date = day.toString(), minutes = 70,
                    method = RecoveryAllocationMethods.SINGLE_DAY, zone = "Europe/Berlin",
                    windowStartAt = at(day), windowEndAt = at(day), pendingDate = false,
                    goalAtCredit = true, createdAt = 0L,
                ),
            ),
        )
        db.playtimeRecoveryDao().insertContribution(contribution("c-pending", 91L, 45))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(
                UnknownTimeAllocation(
                    placementId = "pend-1", contributionId = "c-pending", account = account, appId = 91L,
                    revision = 1L, date = null, minutes = 45,
                    method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN, pendingDate = true, createdAt = 0L,
                ),
            ),
        )

        val allocations = sessions.datedAllocationsBetween(day, day).first()
        assertEquals(1, allocations.size)
        val leaf = allocations.single()
        assertEquals("alloc-1", leaf.placementId)
        assertEquals(70, leaf.minutes)
        assertEquals(70, leaf.windowTotalMinutes)
        assertEquals("Europe/Berlin", leaf.zone)
        assertEquals(day, leaf.windowStartDate)
        assertEquals(day, leaf.windowEndDate)
        assertEquals(true, leaf.goalAtCredit)

        // Pending quantity is disclosed separately, never as a dated row.
        assertEquals(45, sessions.pendingDateUnknownQuantity.first())
        assertEquals(0, sessions.datedAllocationsBetween(day.minusDays(1), day.plusDays(1)).first()
            .count { it.appId == 91L })
    }

    @Test
    fun recoveryStatusReadsAccountScopedPersistedObservationOutcomes() = runTest {
        val statusStore = RecoveryReadStatusStore(RuntimeEnvironment.getApplication())
        statusStore.unavailable(account, 100L)
        val statusAwareRepository = SessionRepository(
            sessionDao = db.sessionDao(),
            hiddenGamesRepository = hidden,
            time = HiddenGamesTestTime,
            playerProfileDao = db.playerProfileDao(),
            recoveryReadStatusStore = statusStore,
        )
        assertEquals(com.example.backlogium.domain.PlaytimeRecoveryStatus.REMOTE_UNAVAILABLE,
            statusAwareRepository.recoveryStatus.first())
        assertEquals(com.example.backlogium.domain.PlaytimeRecoveryStatus.KNOWN_GOOD,
            RecoveryReadStatusStore(RuntimeEnvironment.getApplication()).observe("different-account").first())
    }

    @Test
    fun hiddenPendingQuantityIsExcludedFromPendingDateUnknownLikeOtherReads() = runTest {
        db.playtimeRecoveryDao().insertContribution(contribution("c-hide-pend", 92L, 45))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(
                UnknownTimeAllocation(
                    placementId = "hp1", contributionId = "c-hide-pend", account = account, appId = 92L,
                    revision = 1L, date = null, minutes = 45,
                    method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN, pendingDate = true, createdAt = 0L,
                ),
            ),
        )
        assertEquals(45, sessions.pendingDateUnknownQuantity.first())
        hidden.hide(listOf(92L))
        assertEquals(0, sessions.pendingDateUnknownQuantity.first())
    }

    @Test
    fun allocationOnlyWindowMasksItsFullSpanWithoutARetainedRecipe() = runTest {
        // A multi-day allocation without a retained recipe: the observed window instants plus the
        // frozen zone recover the whole window, so gap dates stay unknown for habit training.
        db.playtimeRecoveryDao().insertContribution(contribution("c-window", 93L, 105))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(
                UnknownTimeAllocation(
                    placementId = "w1", contributionId = "c-window", account = account, appId = 93L,
                    revision = 1L, date = day.toString(), minutes = 26,
                    method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE, zone = "UTC",
                    windowStartAt = at(day.minusDays(1)), windowEndAt = at(day.plusDays(3)),
                    pendingDate = false, createdAt = 0L,
                ),
            ),
        )

        val window = sessions.unresolvedRecoveryWindows().first().single()
        assertEquals(day, window.dateStart)
        assertEquals(day.plusDays(3), window.dateEnd)
        assertEquals(
            setOf(day, day.plusDays(1), day.plusDays(2), day.plusDays(3)),
            window.unknownDatesBetween(day.minusDays(1), day.plusDays(4)),
        )
    }

    private fun session(appId: Long, date: LocalDate, minutes: Int, open: Boolean = false) = Session(
        appId = appId,
        startAt = date.atStartOfDay(zone).toInstant().toEpochMilli(),
        endAt = date.atStartOfDay(zone).toInstant().toEpochMilli() + minutes * 60_000L,
        minutes = minutes,
        open = open,
    )

    private fun contribution(id: String, appId: Long, authorized: Long) = PlaytimeContribution(
        contributionId = id, account = account, appId = appId, baselineEpoch = 1L,
        fromMinutes = 0, toMinutes = authorized, windowEndAt = 0L,
        authorizedMinutes = authorized, revision = 1L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY, createdAt = 0L,
    )

    private fun timed(
        placementId: String, contributionId: String, appId: Long, sessionId: Long?,
        minutes: Long, date: LocalDate,
    ) = TimedContributionPlacement(
        placementId = placementId, contributionId = contributionId, account = account, appId = appId,
        revision = 1L, sessionId = sessionId, minutes = minutes, creditedDate = date.toString(),
        zone = "UTC", evidenceQuality = RecoveryEvidenceQuality.ESTIMATE, createdAt = 0L,
    )

    private fun allocation(
        placementId: String, contributionId: String, appId: Long, date: LocalDate, minutes: Long,
        method: String,
    ) = UnknownTimeAllocation(
        placementId = placementId, contributionId = contributionId, account = account, appId = appId,
        revision = 1L, date = date.toString(), minutes = minutes, method = method, zone = "UTC",
        pendingDate = false, createdAt = 0L,
    )

    private fun at(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}
