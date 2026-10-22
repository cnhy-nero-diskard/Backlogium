package com.example.backlogium.data.repo

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.RecoveryReadStatusStore
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryEvidenceQuality
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.domain.RecoveryDailyCreditCoordinator
import com.example.backlogium.domain.UnknownTimeMaterializer
import com.example.backlogium.domain.PlaytimeActivity
import com.example.backlogium.domain.PlaytimeAllocationMethod
import com.example.backlogium.domain.DatedPlayTotal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.time.Instant
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
        sessions = SessionRepository(
            sessionDao = db.sessionDao(),
            hiddenGamesRepository = hidden,
            time = HiddenGamesTestTime,
            database = db,
        )
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
    fun everyCanonicalProjectionEmissionIsCoherentWhenFundedSessionGrows() = runBlocking {
        val app = 10L
        val sessionStart = at(day)
        val session = Session(
            appId = app,
            startAt = sessionStart,
            endAt = sessionStart + 40 * 60_000L,
            minutes = 40,
            open = false,
        )
        val sessionId = db.sessionDao().insert(session)
        db.playtimeRecoveryDao().upsertState(
            GameRecoveryState(
                account = account,
                appId = app,
                baselineEpoch = 1L,
                highWaterMinutes = 35L,
                lastObservationAt = at(day),
                lastObservationDate = day.toString(),
                lastObservationZone = "UTC",
                clockState = "VALID",
                updatedAt = at(day),
            ),
        )
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution("c-old", app, 35L).copy(
                fromMinutes = 0L,
                toMinutes = 35L,
                windowStartAt = at(day),
                windowEndAt = at(day),
            ),
            timed = listOf(timed("t-old", "c-old", app, sessionId, 35L, day)),
            allocations = emptyList(),
        )

        val coherentRepository = SessionRepository(
            sessionDao = db.sessionDao(),
            hiddenGamesRepository = hidden,
            time = HiddenGamesTestTime,
            database = db,
        )
        val tracked = probe(coherentRepository.trackedMinutesByGame)
        val dated = probe(coherentRepository.datedCreditedTotalsBetween(zone, day, day.plusDays(1)))
        val activity = probe(coherentRepository.activityBetween(zone, day, day.plusDays(1)))
        val training = probe(coherentRepository.closedTrainingTotalsBetween(zone, day, day))
        val pending = probe(coherentRepository.pendingDateUnknownQuantity)
        val recoveryActivity = probe(coherentRepository.datedRecoveryActivity())
        val unresolved = probe(coherentRepository.unresolvedRecoveryWindows())

        try {
            assertEquals(40, tracked.next()[app])
            assertEquals(mapOf(day to 40L), dated.next().associate { it.date to it.minutes })
            assertEquals(40, activity.next().filterIsInstance<PlaytimeActivity.TimestampedSession>().single().minutes)
            assertEquals(listOf(DatedPlayTotal(day, 40)), training.next())
            assertEquals(0, pending.next())
            assertTrue(recoveryActivity.next().isEmpty())
            assertTrue(unresolved.next().isEmpty())

            db.withTransaction {
                db.sessionDao().update(
                    session.copy(id = sessionId, endAt = sessionStart + 65 * 60_000L, minutes = 65),
                )
                db.playtimeRecoveryDao().commitAllocatedGraph(
                    contribution = PlaytimeContribution(
                        contributionId = "c-new",
                        account = account,
                        appId = app,
                        baselineEpoch = 1L,
                        fromMinutes = 35L,
                        toMinutes = 60L,
                        windowStartAt = at(day),
                        windowEndAt = at(day),
                        authorizedMinutes = 25L,
                        revision = 1L,
                        provenance = RecoveryProvenance.SYNC,
                        zone = "UTC",
                        createdAt = at(day),
                    ),
                    timed = listOf(timed("t-new", "c-new", app, sessionId, 25L, day)),
                    allocations = emptyList(),
                )
                db.playtimeRecoveryDao().advanceBaseline(
                    account = account,
                    appId = app,
                    absoluteMinutes = 60L,
                    observedAt = at(day) + 1L,
                    date = day.toString(),
                    zone = "UTC",
                    epoch = 1L,
                )
            }

            // The prior independently-combined flow reproduced [65, 90] for this write schedule:
            // the new contribution was combined with the stale 35-minute funded-share snapshot.
            // Every affected flow now issues its full projection read under one Room snapshot.
            assertEquals(mapOf(app to 65), tracked.next())
            assertEquals(mapOf(day to 65L), dated.next().associate { it.date to it.minutes })
            val afterActivity = activity.next().filterIsInstance<PlaytimeActivity.TimestampedSession>().single()
            assertEquals(65, afterActivity.minutes)
            assertEquals(60L, afterActivity.fundedMinutes)
            assertEquals(5L, afterActivity.unlinkedMinutes)
            assertEquals(listOf(DatedPlayTotal(day, 65)), training.next())
            assertEquals(0, pending.next())
            assertTrue(recoveryActivity.next().isEmpty())
            assertTrue(unresolved.next().isEmpty())
            assertEquals(mapOf(app to 65), coherentRepository.creditedMinutesByGameOnce())
        } finally {
            listOf(
                tracked.job, dated.job, activity.job, training.job,
                pending.job, recoveryActivity.job, unresolved.job,
            ).forEach { it.cancelAndJoin() }
        }
    }

    @Test
    fun estimateReplacementAndRecipeMaterializationPreserveDatedAndTrainingSnapshots() = runBlocking {
        val app = 20L
        val contributionId = "c-r1-revision"
        val contribution = contribution(contributionId, app, 105L).copy(
            windowStartAt = at(day),
            windowEndAt = at(day.plusDays(3)),
        )
        val firstEstimate = UnknownTimeAllocation(
            placementId = "estimate-r1-1",
            contributionId = contributionId,
            account = account,
            appId = app,
            revision = 1L,
            date = day.toString(),
            minutes = 27L,
            method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            zone = "UTC",
            windowStartAt = at(day),
            windowEndAt = at(day.plusDays(3)),
            pendingDate = false,
            createdAt = at(day),
        )
        val firstRecipe = UnknownTimeAllocationRecipe(
            contributionId = contributionId,
            account = account,
            appId = app,
            revision = 1L,
            method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26L,
            remainderCount = 1L,
            dateStart = day.toString(),
            dateEnd = day.plusDays(3).toString(),
            eligibleCount = 4,
            zone = "UTC",
            materializedMinutes = 27L,
            materializedThroughDate = day.toString(),
            updatedAt = at(day),
        )
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = emptyList(),
            allocations = listOf(firstEstimate),
            recipe = firstRecipe,
        )

        val allTime = probe(sessions.trackedMinutesByGame)
        val dated = probe(sessions.datedCreditedTotalsBetween(zone, day, day.plusDays(4)))
        val activity = probe(sessions.activityBetween(zone, day, day.plusDays(4)))
        val training = probe(sessions.closedTrainingTotalsBetween(zone, day, day.plusDays(3)))
        val unresolved = probe(sessions.unresolvedRecoveryWindows())
        try {
            assertEquals(105, allTime.next()[app])
            val initialDateTotals = dated.next().associate { it.date to it.minutes }
            assertEquals(
                mapOf(day to 27L, day.plusDays(1) to 26L, day.plusDays(2) to 26L, day.plusDays(3) to 26L),
                initialDateTotals,
            )
            assertEquals(
                105L,
                activity.next().filterIsInstance<PlaytimeActivity.DatedAllocation>()
                    .filter { it.appId == app }.sumOf { it.minutes },
            )
            assertEquals(105, training.next().sumOf { it.minutes })
            assertEquals(listOf(day to day.plusDays(3)), unresolved.next()
                .filter { it.appId == app }.map { it.dateStart to it.dateEnd })

            // A single Room transaction replaces three days of estimate tail with a validated
            // timed share and a smaller residual recipe, retaining the same 105 authorized minutes.
            val sessionId = db.withTransaction {
                val dao = db.playtimeRecoveryDao()
                assertEquals(1, dao.advanceRevision(contributionId, 2L))
                dao.supersedeTimedPlacementsUpTo(contributionId, 2L)
                dao.supersedeAllocationsUpTo(contributionId, 2L)
                val startAt = at(day.plusDays(1)) + 10 * 60 * 60_000L
                val hostId = db.sessionDao().insert(
                    Session(
                        appId = app,
                        startAt = startAt,
                        endAt = startAt + 35 * 60_000L,
                        minutes = 35,
                        open = false,
                    ),
                )
                dao.insertTimedPlacements(
                    listOf(
                        timed("timed-r2", contributionId, app, hostId, 35L, day.plusDays(1))
                            .copy(revision = 2L),
                    ),
                )
                dao.upsertRecipe(
                    firstRecipe.copy(
                        revision = 2L,
                        quotient = 23L,
                        remainderCount = 1L,
                        dateStart = day.plusDays(1).toString(),
                        dateEnd = day.plusDays(3).toString(),
                        eligibleCount = 3,
                        materializedMinutes = 0L,
                        materializedThroughDate = null,
                        updatedAt = at(day.plusDays(1)),
                    ),
                )
                assertTrue(dao.conservationHolds(contributionId))
                hostId
            }
            assertTrue(sessionId > 0L)
            assertEquals(105, allTime.next()[app])
            val replacedTotals = dated.next().associate { it.date to it.minutes }
            assertEquals(
                mapOf(day.plusDays(1) to 59L, day.plusDays(2) to 23L, day.plusDays(3) to 23L),
                replacedTotals,
            )
            val replacedActivity = activity.next()
            val hosted = replacedActivity.filterIsInstance<PlaytimeActivity.TimestampedSession>().single()
            assertEquals(35L, hosted.fundedMinutes)
            assertEquals(0L, hosted.unlinkedMinutes)
            assertEquals(
                70L,
                replacedActivity.filterIsInstance<PlaytimeActivity.DatedAllocation>()
                    .filter { it.appId == app }.sumOf { it.minutes },
            )
            assertEquals(105, training.next().sumOf { it.minutes })
            assertEquals(listOf(day.plusDays(1) to day.plusDays(3)), unresolved.next()
                .filter { it.appId == app }.map { it.dateStart to it.dateEnd })

            val materializer = UnknownTimeMaterializer(
                database = db,
                recoveryDao = db.playtimeRecoveryDao(),
                dailyCredit = RecoveryDailyCreditCoordinator(db.dailyProgressDao()),
            )
            repeat(3) { page ->
                val result = materializer.materialize(contributionId, now = at(day.plusDays(4)), pageSize = 1)
                assertTrue("expected residual recipe page $page", result != null)
                assertEquals(105, allTime.next()[app])
                assertEquals(replacedTotals, dated.next().associate { it.date to it.minutes })
                assertEquals(105L, activity.next().let { rows ->
                    rows.filterIsInstance<PlaytimeActivity.TimestampedSession>().sumOf { it.minutes.toLong() } +
                        rows.filterIsInstance<PlaytimeActivity.DatedAllocation>()
                            .filter { it.appId == app }.sumOf { it.minutes }
                })
                assertEquals(105, training.next().sumOf { it.minutes })
                assertTrue(unresolved.next().any { it.appId == app })
            }
            assertTrue(db.playtimeRecoveryDao().conservationHolds(contributionId))
            assertEquals(70L, db.playtimeRecoveryDao().sumActiveDatedForContribution(contributionId))
        } finally {
            listOf(allTime.job, dated.job, activity.job, training.job, unresolved.job)
                .forEach { it.cancelAndJoin() }
        }
    }

    @Test
    fun hiddenChangesAndAccountResetRetargetWholeProjectionSnapshots() = runBlocking {
        val appA = 30L
        val appB = 31L
        val contributionA = contribution("c-hidden-account-a", appA, 70L)
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contributionA,
            timed = emptyList(),
            allocations = listOf(allocation("a-hidden-account-a", contributionA.contributionId, appA, day, 70L,
                RecoveryAllocationMethods.SINGLE_DAY)),
        )

        val allTime = probe(sessions.trackedMinutesByGame)
        val dated = probe(sessions.datedCreditedTotalsBetween(zone, day, day.plusDays(2)))
        val training = probe(sessions.closedTrainingTotalsBetween(zone, day, day.plusDays(1)))
        try {
            assertEquals(mapOf(appA to 70), allTime.next())
            assertEquals(mapOf(day to 70L), dated.next().associate { it.date to it.minutes })
            assertEquals(listOf(DatedPlayTotal(day, 70)), training.next())

            hidden.hide(listOf(appA))
            assertTrue(allTime.next().isEmpty())
            assertTrue(dated.next().isEmpty())
            assertTrue(training.next().isEmpty())

            hidden.unhide(listOf(appA))
            assertEquals(mapOf(appA to 70), allTime.next())
            assertEquals(mapOf(day to 70L), dated.next().associate { it.date to it.minutes })
            assertEquals(listOf(DatedPlayTotal(day, 70)), training.next())

            val nextAccount = "76561198000000002"
            db.withTransaction {
                db.playtimeRecoveryDao().clearForAccount(account)
                db.sessionDao().deleteAll()
                db.playerProfileDao().upsert(PlayerProfile(steamId = nextAccount))
                val contributionB = contribution("c-hidden-account-b", appB, 45L).copy(account = nextAccount)
                db.playtimeRecoveryDao().commitAllocatedGraph(
                    contribution = contributionB,
                    timed = emptyList(),
                    allocations = listOf(
                        allocation(
                            "a-hidden-account-b",
                            contributionB.contributionId,
                            appB,
                            day.plusDays(1),
                            45L,
                            RecoveryAllocationMethods.SINGLE_DAY,
                        ).copy(account = nextAccount),
                    ),
                )
            }

            assertEquals("no previous-account rows or credits leak through reset", mapOf(appB to 45), allTime.next())
            assertEquals(mapOf(day.plusDays(1) to 45L), dated.next().associate { it.date to it.minutes })
            assertEquals(listOf(DatedPlayTotal(day.plusDays(1), 45)), training.next())
            assertEquals(nextAccount, db.playerProfileDao().get()!!.steamId)
            assertTrue(db.playtimeRecoveryDao().contributionsForGame(account, appA).isEmpty())
        } finally {
            listOf(allTime.job, dated.job, training.job).forEach { it.cancelAndJoin() }
        }
    }

    @Test
    fun concurrentOneShotReadsSeeOnlyCommittedCreditedTotals() = runBlocking {
        val app = 32L
        val startAt = at(day)
        val session = Session(
            appId = app,
            startAt = startAt,
            endAt = startAt + 40 * 60_000L,
            minutes = 40,
            open = false,
        )
        val sessionId = db.sessionDao().insert(session)
        val oldContribution = contribution("c-one-shot-old", app, 35L).copy(
            toMinutes = 35L,
            authorizedMinutes = 35L,
            windowStartAt = startAt,
            windowEndAt = startAt,
        )
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = oldContribution,
            timed = listOf(timed("t-one-shot-old", oldContribution.contributionId, app, sessionId, 35L, day)),
            allocations = emptyList(),
        )
        val oldSnapshot = mapOf(app to 40)
        val newSnapshot = mapOf(app to 65)
        val writerPausedInsideTransaction = CompletableDeferred<Unit>()
        val finishWriter = CompletableDeferred<Unit>()

        coroutineScope {
            val writer = async(Dispatchers.IO) {
                db.withTransaction {
                    db.sessionDao().update(session.copy(id = sessionId, minutes = 65,
                        endAt = startAt + 65 * 60_000L))
                    writerPausedInsideTransaction.complete(Unit)
                    finishWriter.await()
                    db.playtimeRecoveryDao().commitAllocatedGraph(
                        contribution = PlaytimeContribution(
                            contributionId = "c-one-shot-new",
                            account = account,
                            appId = app,
                            baselineEpoch = 1L,
                            fromMinutes = 35L,
                            toMinutes = 60L,
                            windowStartAt = startAt,
                            windowEndAt = startAt + 1L,
                            authorizedMinutes = 25L,
                            revision = 1L,
                            provenance = RecoveryProvenance.SYNC,
                            zone = "UTC",
                            createdAt = startAt + 1L,
                        ),
                        timed = listOf(timed("t-one-shot-new", "c-one-shot-new", app, sessionId, 25L, day)),
                        allocations = emptyList(),
                    )
                }
            }
            writerPausedInsideTransaction.await()
            val readStarted = List(8) { CompletableDeferred<Unit>() }
            val readers = readStarted.map { started ->
                async(Dispatchers.IO) {
                    started.complete(Unit)
                    sessions.creditedMinutesByGameOnce()
                }
            }
            val daoReadStarted = List(8) { CompletableDeferred<Unit>() }
            val daoReaders = daoReadStarted.map { started ->
                async(Dispatchers.IO) {
                    started.complete(Unit)
                    db.sessionDao().creditedMinutesByGame().associate { it.appId to it.minutes }
                }
            }
            readStarted.forEach { it.await() }
            daoReadStarted.forEach { it.await() }
            finishWriter.complete(Unit)
            writer.await()
            val results = readers.awaitAll() + daoReaders.awaitAll()

            assertTrue(
                "every one-shot transaction must see one committed revision, got $results",
                results.all { it == oldSnapshot || it == newSnapshot },
            )
            assertEquals(newSnapshot, sessions.creditedMinutesByGameOnce())
        }
    }

    @Test
    fun windowedCanonicalSnapshotChunksMoreThanNineHundredHostSessionIds() = runBlocking {
        val hostCount = 1_001
        db.withTransaction {
            db.gameDao().upsertAll((0 until hostCount).map { index ->
                val appId = 1_000L + index
                Game(appId, "Window host $appId", "", 0, 0, 0)
            })
            repeat(hostCount) { index ->
                val appId = 1_000L + index
                val sessionId = db.sessionDao().insert(session(appId, day, minutes = 1))
                val contribution = contribution("c-window-host-$index", appId, 1L)
                db.playtimeRecoveryDao().insertContributionChecked(contribution)
                db.playtimeRecoveryDao().insertTimedPlacements(
                    listOf(timed("t-window-host-$index", contribution.contributionId, appId, sessionId, 1L, day)),
                )
            }
        }

        val totals = sessions.datedCreditedTotalsBetween(zone, day, day.plusDays(1)).first()

        assertEquals(1, totals.size)
        assertEquals(hostCount.toLong(), totals.sumOf { it.minutes })
        assertEquals(hostCount, totals.single().minutesByGame.size)
        assertTrue(totals.single().minutesByGame.values.all { it == 1L })
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
    fun activitySinceUsesTheSupportedDateCeilingInsteadOfOverflowingLocalDateMax() = runTest {
        val app = 21L
        val contribution = contribution("c-activity-since", app, 12L)
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = contribution,
            timed = emptyList(),
            allocations = listOf(allocation(
                "a-activity-since", contribution.contributionId, app, day, 12L,
                RecoveryAllocationMethods.SINGLE_DAY,
            )),
        )

        val rows = sessions.activitySince(zone, day).first()

        assertEquals(12L, rows.filterIsInstance<PlaytimeActivity.DatedAllocation>().single().minutes)
        assertTrue(sessions.activitySince(zone, LocalDate.of(2100, 1, 2)).first().isEmpty())
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

    @Test
    fun adjacentWindowsConserveMixedSessionAcrossFrozenShareDatesAfterZoneChange() = runTest {
        val hostStart = Instant.parse("2026-10-16T06:30:00Z").toEpochMilli()
        val hostId = db.sessionDao().insert(
            Session(
                appId = 81L,
                startAt = hostStart,
                endAt = hostStart + 70 * 60_000L,
                minutes = 70,
                open = false,
            ),
        )
        val firstFrozenDate = LocalDate.of(2026, 10, 16)
        val secondFrozenDate = firstFrozenDate.plusDays(1)
        db.playtimeRecoveryDao().insertContribution(contribution("c-adjacent", 81L, 30))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(
                timed("t-adjacent-1", "c-adjacent", 81L, hostId, 20, firstFrozenDate, "Asia/Tokyo"),
                timed("t-adjacent-2", "c-adjacent", 81L, hostId, 10, secondFrozenDate, "Asia/Tokyo"),
            ),
        )

        // The session starts on Oct 15 in Los Angeles, while its funded shares keep their
        // Oct 16/17 Tokyo dates. Each one-day read must subtract both shares before exposing the
        // Oct 15 legacy remainder, even when neither/barely one of those shares is in its slice.
        val losAngeles = ZoneId.of("America/Los_Angeles")
        val hostDate = LocalDate.of(2026, 10, 15)
        val dayTotals = (0L..2L).map { offset ->
            val date = hostDate.plusDays(offset)
            date to sessions.datedCreditedTotalsBetween(losAngeles, date, date.plusDays(1))
                .first().sumOf { it.minutes }
        }.toMap()
        assertEquals(mapOf(hostDate to 40L, firstFrozenDate to 20L, secondFrozenDate to 10L), dayTotals)
        assertEquals(70L, dayTotals.values.sum())

        val hostDayActivity = sessions.activityBetween(losAngeles, hostDate, hostDate.plusDays(1))
            .first().filterIsInstance<PlaytimeActivity.TimestampedSession>().single()
        assertEquals(30L, hostDayActivity.fundedMinutes)
        assertEquals(40L, hostDayActivity.unlinkedMinutes)
        assertEquals(listOf(firstFrozenDate, secondFrozenDate),
            hostDayActivity.fundedShares.map { it.creditedDate })

        val shareDayActivity = sessions.activityBetween(
            losAngeles, firstFrozenDate, firstFrozenDate.plusDays(1),
        ).first().filterIsInstance<PlaytimeActivity.TimestampedSession>().single()
        assertEquals(40L, shareDayActivity.unlinkedMinutes)
        assertEquals(30L, shareDayActivity.fundedMinutes)

        assertEquals(
            listOf(DatedPlayTotal(hostDate, 40)),
            sessions.closedTrainingTotalsBetween(losAngeles, hostDate, hostDate).first(),
        )
        assertEquals(
            listOf(DatedPlayTotal(hostDate, 40), DatedPlayTotal(firstFrozenDate, 20)),
            sessions.closedTrainingTotalsBetween(losAngeles, hostDate, firstFrozenDate).first(),
        )
    }

    @Test
    fun earliestCanonicalDateIncludesHostedShareDateBeforeCurrentZoneHostDate() = runTest {
        val hostStart = Instant.parse("2026-10-16T06:30:00Z").toEpochMilli()
        val hostId = db.sessionDao().insert(
            Session(
                appId = 82L,
                startAt = hostStart,
                endAt = hostStart + 40 * 60_000L,
                minutes = 40,
                open = false,
            ),
        )
        val frozenDate = LocalDate.of(2026, 10, 15)
        db.playtimeRecoveryDao().insertContribution(contribution("c-old-share", 82L, 10))
        db.playtimeRecoveryDao().insertTimedPlacements(
            listOf(timed("t-old-share", "c-old-share", 82L, hostId, 10, frozenDate)),
        )

        assertEquals(frozenDate, sessions.earliestCanonicalActivityDate.first())
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
            database = db,
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
        minutes: Long, date: LocalDate, zone: String = "UTC",
    ) = TimedContributionPlacement(
        placementId = placementId, contributionId = contributionId, account = account, appId = appId,
        revision = 1L, sessionId = sessionId, minutes = minutes, creditedDate = date.toString(),
        zone = zone, evidenceQuality = RecoveryEvidenceQuality.ESTIMATE, createdAt = 0L,
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

private data class ProjectionProbe<T>(
    val emissions: Channel<Result<T>>,
    val job: Job,
)

private fun <T> CoroutineScope.probe(flow: Flow<T>): ProjectionProbe<T> {
    val emissions = Channel<Result<T>>(Channel.UNLIMITED)
    val job = launch {
        try {
            flow.collect { emissions.send(Result.success(it)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            emissions.send(Result.failure(failure))
        }
    }
    return ProjectionProbe(emissions, job)
}

private suspend fun <T> ProjectionProbe<T>.next(): T =
    withTimeout(10_000L) { emissions.receive() }.getOrThrow()
