package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** The one-time legacy correction must not erase canonical recovery shares on their frozen dates. */
@RunWith(RobolectricTestRunner::class)
class DailyProgressCanonicalBackfillTest {
    private lateinit var db: BacklogiumDatabase
    private val account = "daily-backfill-fixture"
    private val zone = ZoneId.of("UTC")
    private val time = object : TimeProvider {
        override fun nowMillis() = Instant.parse("2026-08-14T12:00:00Z").toEpochMilli()
        override fun zone() = this@DailyProgressCanonicalBackfillTest.zone
        override fun today() = LocalDate.parse("2026-08-14")
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun `backfill preserves dated allocations and frozen hosted timed shares`() = runBlocking {
        db.playerProfileDao().upsert(PlayerProfile(steamId = account))
        db.gameDao().upsert(Game(440, "Fixture", "", 150, 0, 150, isGoal = true))
        val sessionId = db.sessionDao().insert(
            Session(
                appId = 440,
                startAt = Instant.parse("2026-08-13T23:30:00Z").toEpochMilli(),
                endAt = Instant.parse("2026-08-14T00:10:00Z").toEpochMilli(),
                minutes = 40,
                open = false,
            ),
        )
        val contributionId = PlaytimeRecoveryIdentity.contributionId(account, 440, 1, 100, 150)
        val contribution = PlaytimeContribution(
            contributionId = contributionId, account = account, appId = 440,
            baselineEpoch = 1, fromMinutes = 100, toMinutes = 150,
            windowStartAt = Instant.parse("2026-08-12T00:00:00Z").toEpochMilli(),
            windowEndAt = Instant.parse("2026-08-14T00:20:00Z").toEpochMilli(),
            authorizedMinutes = 50, revision = 1,
            provenance = RecoveryProvenance.RETROACTIVE_PLAY, zone = "UTC", createdAt = 1,
        )
        val timed = TimedContributionPlacement(
            placementId = PlaytimeRecoveryIdentity.timedPlacementId(
                contributionId, 1, "evidence", "2026-08-12", sessionId,
            ),
            contributionId = contributionId, account = account, appId = 440,
            revision = 1, sessionId = sessionId, minutes = 20,
            creditedDate = "2026-08-12", zone = "UTC",
            evidenceIdentity = "evidence|1|2", evidenceQuality = "LOCAL",
            goalAtCredit = true, createdAt = 1,
        )
        val bucket = UnknownTimeAllocation(
            placementId = PlaytimeRecoveryIdentity.allocationPlacementId(contributionId, 1, "2026-08-12"),
            contributionId = contributionId, account = account, appId = 440,
            revision = 1, date = "2026-08-12", minutes = 30,
            method = RecoveryAllocationMethods.SINGLE_DAY, zone = "UTC",
            hiddenAtCredit = false, goalAtCredit = false, createdAt = 1,
        )
        db.playtimeRecoveryDao().commitAllocatedGraph(contribution, listOf(timed), listOf(bucket))
        db.dailyProgressDao().upsert(DailyProgress("2026-08-12", minutesPlayed = 0))
        db.dailyProgressDao().upsert(DailyProgress("2026-08-13", minutesPlayed = 40, goalMinutesPlayed = 40))

        val useCase = DailyProgressBackfillUseCase(
            sessionDao = db.sessionDao(),
            recoveryDao = db.playtimeRecoveryDao(),
            gameDao = db.gameDao(),
            dailyProgressDao = db.dailyProgressDao(),
            settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
            gamificationUpdater = GamificationUpdater(
                db.sessionDao(), db.dailyProgressDao(), db.playerProfileDao(), db.hltbDataDao(),
                db.achievementDao(), db.gameDao(), db.hiddenGameDao(),
            ),
            time = time,
            syncCoordinator = com.example.backlogium.work.SteamSyncCoordinator(),
            derivedStateWrites = DerivedStateWriteCoordinator(),
            dailyCredit = RecoveryDailyCreditCoordinator(db.dailyProgressDao()),
        )
        val corrections = useCase.computeCorrections().associateBy { it.date }

        assertEquals(50, corrections.getValue("2026-08-12").correctedMinutes)
        assertEquals(20, corrections.getValue("2026-08-12").correctedGoalMinutes)
        assertEquals(20, corrections.getValue("2026-08-13").correctedMinutes)
        assertEquals(20, corrections.getValue("2026-08-13").correctedGoalMinutes)
    }
}
