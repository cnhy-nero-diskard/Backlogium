package com.example.backlogium.data.repo

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.domain.CurrentDateProvider
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
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
 * Personal Pace eligibility feed (recover-missed-playtime 9.1-9.3): eligible single-day recovered
 * activity trains completed-date totals alongside closed sessions, unresolved multi-day estimate
 * dates are masked (never synthesized zeros, never counted toward confidence floors), and a fully
 * resolved date deterministically restores eligibility offline.
 */
@RunWith(RobolectricTestRunner::class)
class PersonalPaceRepositoryRecoveryTest {

    private lateinit var db: BacklogiumDatabase

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 6, 15)
    private val account = "76561198000000001"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        db.playerProfileDao().upsert(PlayerProfile(steamId = account))
        db.gameDao().upsert(Game(100, "Recovery fixture", "", 0, 0, 0))
        db.gameDao().upsert(Game(300, "Pending fixture", "", 0, 0, 0))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun singleDayRecoveryTrainsAlongsideClosedSessions() = runTest {
        insertSession(today.minusDays(10), minutes = 60)
        insertSession(today.minusDays(1), minutes = 60)
        db.playtimeRecoveryDao().insertContribution(contribution("single", 200L, authorized = 70))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a-single", "single", 200L, today.minusDays(2), 70, RecoveryAllocationMethods.SINGLE_DAY)),
        )

        val profile = pace().first()

        assertEquals(10, profile.coveredDates)
        assertEquals(3, profile.activeDates)
        assertEquals(70, profile.dailyTotals.first { it.date == today.minusDays(2) }.minutes)
    }

    @Test
    fun unresolvedMultiDayDatesAreMaskedAndNeverSynthesized() = runTest {
        insertSession(today.minusDays(10), minutes = 60)
        insertSession(today.minusDays(1), minutes = 60)
        // A 105-minute estimate across [today-8 .. today-5], one positive share materialized.
        val windowStart = today.minusDays(8)
        val windowEnd = today.minusDays(5)
        db.playtimeRecoveryDao().insertContribution(contribution("md", 100L, authorized = 105))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a-md", "md", 100L, today.minusDays(7), 26, RecoveryAllocationMethods.MULTI_DAY_ESTIMATE)),
        )
        upsertRecipe(
            contributionId = "md", appId = 100L, method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26, remainderCount = 1, eligibleCount = 4,
            dateStart = windowStart, dateEnd = windowEnd,
            materializedMinutes = 26, materializedThroughDate = today.minusDays(7),
        )

        val profile = pace().first()

        // The four window dates are unknown: not active, not synthesized zero-play days.
        (5L..8L).forEach { offset ->
            assertTrue(profile.dailyTotals.none { it.date == today.minusDays(offset) })
        }
        assertEquals(6, profile.coveredDates)
        assertEquals(2, profile.activeDates)
        assertFalse(profile.isReliable)
    }

    @Test
    fun fullyResolvedDateRestoresEligibilityOnTheNextOfflineDerivation() = runTest {
        insertSession(today.minusDays(10), minutes = 60)
        insertSession(today.minusDays(1), minutes = 60)
        val windowStart = today.minusDays(8)
        val windowEnd = today.minusDays(5)
        db.playtimeRecoveryDao().insertContribution(contribution("md", 100L, authorized = 105))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(allocation("a-md", "md", 100L, today.minusDays(7), 26, RecoveryAllocationMethods.MULTI_DAY_ESTIMATE)),
        )
        upsertRecipe(
            contributionId = "md", appId = 100L, method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26, remainderCount = 1, eligibleCount = 4,
            dateStart = windowStart, dateEnd = windowEnd,
            materializedMinutes = 26, materializedThroughDate = today.minusDays(7),
        )
        val before = pace().first()
        assertEquals(6, before.coveredDates)

        // Late validated evidence fully resolves 06-08: the estimate window drops it and the
        // stale multi-day allocation on that date is superseded (the replacement timed placement
        // owns the date's credit from then on).
        db.playtimeRecoveryDao().supersedeAllocationsUpTo("md", keepRevision = 2L)
        upsertRecipe(
            contributionId = "md", appId = 100L, method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26, remainderCount = 1, eligibleCount = 4,
            dateStart = windowStart, dateEnd = windowEnd,
            materializedMinutes = 26, materializedThroughDate = today.minusDays(7),
            excludedDatesCsv = today.minusDays(7).toString(),
        )

        val after = pace().first()
        // Deterministic offline change: the resolved date becomes an eligible zero-play date
        // without any network read and without altering the credited quantity.
        assertEquals(7, after.coveredDates)
        assertEquals(2, after.activeDates)
        assertTrue(after.dailyTotals.any { it.date == today.minusDays(7) && it.minutes == 0 })
    }

    @Test
    fun pendingOpenAndCurrentDatesSupplyNoObservation() = runTest {
        // A closed past session trains; today's closed session is still current and excluded;
        // an open session and pending date-unknown quantity supply nothing.
        insertSession(today.minusDays(3), minutes = 60)
        insertSession(today, minutes = 45)
        insertSession(today.minusDays(1), minutes = 60, open = true)
        db.playtimeRecoveryDao().insertContribution(contribution("pending", 300L, authorized = 45))
        db.playtimeRecoveryDao().insertAllocations(
            listOf(
                UnknownTimeAllocation(
                    placementId = "p", contributionId = "pending", account = account, appId = 300L,
                    revision = 1L, date = null, minutes = 45,
                    method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN, pendingDate = true,
                    createdAt = 0L,
                ),
            ),
        )

        val profile = pace().first()

        assertTrue(profile.dailyTotals.none { it.date >= today })
        assertFalse(
            "open session leaked into Personal Pace: rows=${db.sessionDao().getAll()}, profile=${profile.dailyTotals}",
            profile.dailyTotals.any { it.date == today.minusDays(1) },
        )
        // Only the one closed past session trains: today is current, the open row is excluded and
        // pending quantity supplies no observation.
        assertEquals(1, profile.activeDates)
        // The open-session date is unknown, so it is not counted toward coverage; the intervening
        // completed local date remains a zero observation inside the tracked span.
        assertEquals(2, profile.coveredDates)
        assertEquals(60, profile.dailyTotals.first { it.date == today.minusDays(3) }.minutes)
    }

    @Test
    fun recoveryWindowsExposeOnlyUnresolvedDates() = runTest {
        val windowStart = today.minusDays(8)
        db.playtimeRecoveryDao().insertContribution(contribution("md", 100L, authorized = 105))
        upsertRecipe(
            contributionId = "md", appId = 100L, method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26, remainderCount = 1, eligibleCount = 4,
            dateStart = windowStart, dateEnd = windowStart.plusDays(3),
            materializedMinutes = 0, materializedThroughDate = null,
            excludedDatesCsv = windowStart.plusDays(1).toString(),
        )

        val repository = sessions()
        val windows = repository.unresolvedRecoveryWindows().first()
        val window = windows.single()
        assertEquals(
            setOf(windowStart, windowStart.plusDays(2), windowStart.plusDays(3)),
            window.unknownDatesBetween(windowStart, windowStart.plusDays(3)),
        )
        assertTrue(windowStart.plusDays(1) !in window.unknownDatesBetween(windowStart, windowStart.plusDays(3)))
    }

    private suspend fun pace() = PersonalPaceRepository(
        sessionRepository = sessions(),
        currentDate = CurrentDateProvider(FixedClock),
    ).profile

    private fun sessions() = SessionRepository(
        sessionDao = db.sessionDao(),
        hiddenGamesRepository = fakeHiddenGamesRepository(),
        time = FixedClock,
    )

    private suspend fun insertSession(date: LocalDate, minutes: Int, open: Boolean = false) {
        db.sessionDao().insert(
            Session(
                appId = 100L,
                startAt = at(date),
                endAt = if (open) null else at(date) + minutes * 60_000L,
                minutes = minutes,
                open = open,
            ),
        )
    }

    private fun at(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun contribution(id: String, appId: Long, authorized: Long) = PlaytimeContribution(
        contributionId = id, account = account, appId = appId, baselineEpoch = 1L,
        fromMinutes = 0, toMinutes = authorized, windowEndAt = 0L,
        authorizedMinutes = authorized, revision = 1L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY, createdAt = 0L,
    )

    private fun allocation(
        placementId: String, contributionId: String, appId: Long, date: LocalDate, minutes: Long,
        method: String,
    ) = UnknownTimeAllocation(
        placementId = placementId, contributionId = contributionId, account = account, appId = appId,
        revision = 1L, date = date.toString(), minutes = minutes, method = method, zone = "UTC",
        pendingDate = false, createdAt = 0L,
    )

    private suspend fun upsertRecipe(
        contributionId: String,
        appId: Long,
        method: String,
        quotient: Long,
        remainderCount: Long,
        eligibleCount: Int,
        dateStart: LocalDate,
        dateEnd: LocalDate,
        materializedMinutes: Long,
        materializedThroughDate: LocalDate?,
        excludedDatesCsv: String? = null,
    ) {
        db.playtimeRecoveryDao().upsertRecipe(
            UnknownTimeAllocationRecipe(
                contributionId = contributionId, account = account, appId = appId, revision = 1L,
                method = method, quotient = quotient, remainderCount = remainderCount,
                eligibleCount = eligibleCount, dateStart = dateStart.toString(),
                dateEnd = dateEnd.toString(), zone = "UTC", excludedDatesCsv = excludedDatesCsv,
                materializedMinutes = materializedMinutes,
                materializedThroughDate = materializedThroughDate?.toString(),
                updatedAt = 1L,
            ),
        )
    }

    private object FixedClock : TimeProvider {
        private val today = LocalDate.of(2026, 6, 15)
        override fun nowMillis(): Long = 0L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = today
    }
}
