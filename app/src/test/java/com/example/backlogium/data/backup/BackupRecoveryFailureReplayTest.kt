package com.example.backlogium.data.backup

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PlaytimeObservationCommitter
import com.example.backlogium.domain.RecoveryDailyCreditCoordinator
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.UnknownTimeMaterializer
import com.example.backlogium.gamification.RuleConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
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

/** Real file-backed Room restore/materialization retries (task 4.3). */
@RunWith(RobolectricTestRunner::class)
class BackupRecoveryFailureReplayTest {

    private lateinit var source: BacklogiumDatabase
    private var receiver: BacklogiumDatabase? = null
    private val receiverName = "br${System.nanoTime()}.db"

    @Before
    fun setUp() {
        source = newDatabase()
    }

    @After
    fun tearDown() {
        source.close()
        receiver?.close()
        RuntimeEnvironment.getApplication().deleteDatabase(receiverName)
    }

    @Test
    fun failedRestoreAndMaterializationRollbackThenReplayAfterReopenAndAllowOrdinarySync() = runBlocking {
        val db = openReceiver()
        val originalGame = game().copy(playtimeForever = 500, lastPlaytime = 500)
        db.gameDao().upsert(originalGame)
        db.playerProfileDao().upsert(
            PlayerProfile(steamId = ACCOUNT, lastSyncAt = FixedTime.nowMillis()),
        )
        seedBaseline(db, highWater = 500, date = "2026-07-16")
        val localContribution = contribution("c-local", from = 400L, to = 500L)
        db.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = localContribution,
            timed = emptyList(),
            allocations = listOf(allocation("c-local", 100L, "2026-07-16")),
        )
        val legacySession = db.sessionDao().insert(
            Session(
                appId = APP,
                startAt = iso("2026-07-16T10:00:00Z"),
                endAt = iso("2026-07-16T11:00:00Z"),
                minutes = 40,
                open = false,
            ),
        )
        db.dailyProgressDao().ensureDate("2026-07-14")
        db.dailyProgressDao().setMinutes("2026-07-14", minutesPlayed = 7, goalMinutesPlayed = 4)
        assertTrue(db.playtimeRecoveryDao().validateGraph(localContribution.contributionId))

        source.gameDao().upsert(game().copy(playtimeForever = 605, lastPlaytime = 605))
        source.playerProfileDao().upsert(
            PlayerProfile(steamId = ACCOUNT, lastSyncAt = FixedTime.nowMillis()),
        )
        seedBaseline(source, highWater = 605, date = "2026-07-17")
        val importedContribution = contribution("c-imported-recipe", from = 500L, to = 605L)
        val importedRecipe = UnknownTimeAllocationRecipe(
            contributionId = importedContribution.contributionId,
            account = ACCOUNT,
            appId = APP,
            revision = 1L,
            method = RecoveryAllocationMethods.MULTI_DAY_ESTIMATE,
            quotient = 26L,
            remainderCount = 1L,
            dateStart = "2026-07-14",
            dateEnd = "2026-07-17",
            eligibleCount = 4,
            zone = "UTC",
            windowStartAt = iso("2026-07-13T12:00:00Z"),
            windowEndAt = iso("2026-07-17T12:00:00Z"),
            goalAtCredit = true,
            updatedAt = 1L,
        )
        source.playtimeRecoveryDao().commitAllocatedGraph(
            contribution = importedContribution,
            timed = emptyList(),
            allocations = emptyList(),
            recipe = importedRecipe,
        )
        val file = exportMapper(source).buildExport()
        assertTrue(BackupValidator.validate(file) is BackupValidationResult.Valid)

        val recoveryBeforeFailure = recoveryStore(db).read(ACCOUNT)
        val sessionsBeforeFailure = db.sessionDao().getAll()
        val dailyBeforeFailure = db.dailyProgressDao().getAllOrdered()
        val gameBeforeFailure = db.gameDao().getById(APP)
        val profileBeforeFailure = db.playerProfileDao().get()
        val failingStore = DaoRecoveryBackupStore(
            dao = FailAfterFirstGraphCommit(db.playtimeRecoveryDao()),
            sessionDao = db.sessionDao(),
        )
        val failedImport = runCatching { mergeEngine(db, failingStore).merge(file, RuleConfig()) }
        assertTrue("injected failure after the first recovery graph commit must escape", failedImport.isFailure)
        assertTrue(
            "failure must come from the first graph-write failpoint",
            exceptionMessages(failedImport.exceptionOrNull()).contains("injected failure after first recovery graph write"),
        )

        assertEquals(recoveryBeforeFailure, recoveryStore(db).read(ACCOUNT))
        assertEquals(sessionsBeforeFailure, db.sessionDao().getAll())
        assertEquals(dailyBeforeFailure, db.dailyProgressDao().getAllOrdered())
        assertEquals(gameBeforeFailure, db.gameDao().getById(APP))
        assertEquals(profileBeforeFailure, db.playerProfileDao().get())
        assertEquals(40, db.sessionDao().getAll().single { it.id == legacySession }.minutes)

        // Re-open the on-disk Room DB, confirm the failed transaction left no partial graph, then
        // replay the exact same backup successfully.
        reopenReceiver()
        var restored = requireNotNull(receiver)
        assertEquals(recoveryBeforeFailure, recoveryStore(restored).read(ACCOUNT))
        assertEquals(sessionsBeforeFailure, restored.sessionDao().getAll())
        assertEquals(dailyBeforeFailure, restored.dailyProgressDao().getAllOrdered())
        mergeEngine(restored).merge(file, RuleConfig())
        var dao = restored.playtimeRecoveryDao()
        assertEquals(2, dao.contributionsForGame(ACCOUNT, APP).size)
        assertEquals(205L, dao.contributionsForGame(ACCOUNT, APP).sumOf { it.authorizedMinutes })
        assertTrue(dao.validateGraph(importedContribution.contributionId))
        assertEquals(605L, dao.state(ACCOUNT, APP)!!.highWaterMinutes)
        assertEquals(605, restored.gameDao().getById(APP)!!.lastPlaytime)

        val allocationsBeforeMaterializationFailure = dao.activeAllocations(importedContribution.contributionId)
        val recipeBeforeMaterializationFailure = dao.recipe(importedContribution.contributionId)!!
        val dailyBeforeMaterializationFailure = restored.dailyProgressDao().getAllOrdered()
        val failedDailyCredit = FailAfterFirstDailyDelta(restored.dailyProgressDao())
        val failingMaterializer = UnknownTimeMaterializer(
            database = restored,
            recoveryDao = dao,
            dailyCredit = RecoveryDailyCreditCoordinator(failedDailyCredit),
        )
        val failedSlice = runCatching {
            failingMaterializer.materialize(importedContribution.contributionId, now = FixedTime.nowMillis(), pageSize = 1)
        }
        assertTrue("injected raw-daily-credit failure must escape materialization", failedSlice.isFailure)
        assertTrue(
            "failure must come from the daily-credit failpoint",
            exceptionMessages(failedSlice.exceptionOrNull()).contains("injected failure after raw daily-credit write"),
        )
        assertEquals(allocationsBeforeMaterializationFailure,
            dao.activeAllocations(importedContribution.contributionId))
        assertEquals(recipeBeforeMaterializationFailure, dao.recipe(importedContribution.contributionId))
        assertEquals(dailyBeforeMaterializationFailure, restored.dailyProgressDao().getAllOrdered())
        assertTrue(dao.validateGraph(importedContribution.contributionId))
        assertEquals(105L, dao.conservedAllocatedMinutes(importedContribution.contributionId))

        // Restart with the first page entirely rolled back, then commit it once and restart again
        // before continuing the remaining prefix pages.
        reopenReceiver()
        restored = requireNotNull(receiver)
        dao = restored.playtimeRecoveryDao()
        assertEquals(0L, dao.recipe(importedContribution.contributionId)!!.materializedMinutes)
        assertNull(dao.recipe(importedContribution.contributionId)!!.materializedThroughDate)
        val firstPage = materializer(restored).materialize(
            importedContribution.contributionId,
            now = FixedTime.nowMillis(),
            pageSize = 1,
        )
        assertNotNull(firstPage)
        assertEquals(27, firstPage!!.minutes)
        assertEquals(27L, dao.recipe(importedContribution.contributionId)!!.materializedMinutes)
        assertEquals("2026-07-14", dao.recipe(importedContribution.contributionId)!!.materializedThroughDate)
        assertEquals(34, restored.dailyProgressDao().getByDate("2026-07-14")!!.minutesPlayed)
        assertEquals(31, restored.dailyProgressDao().getByDate("2026-07-14")!!.goalMinutesPlayed)
        reopenReceiver()
        restored = requireNotNull(receiver)
        dao = restored.playtimeRecoveryDao()
        assertEquals(3, materializer(restored).materializeAll(ACCOUNT, FixedTime.nowMillis(), pageSize = 1))
        assertTrue(dao.validateGraph(importedContribution.contributionId))
        assertEquals(105L, dao.conservedAllocatedMinutes(importedContribution.contributionId))
        assertEquals(105L, dao.recipe(importedContribution.contributionId)!!.materializedMinutes)
        assertEquals("2026-07-17", dao.recipe(importedContribution.contributionId)!!.materializedThroughDate)
        val dailyByDate = restored.dailyProgressDao().getAllOrdered().associateBy { it.date }
        assertEquals(mapOf("2026-07-14" to 34, "2026-07-15" to 26, "2026-07-16" to 26, "2026-07-17" to 26),
            dailyByDate.mapValues { it.value.minutesPlayed })
        assertEquals(mapOf("2026-07-14" to 31, "2026-07-15" to 26, "2026-07-16" to 26, "2026-07-17" to 26),
            dailyByDate.mapValues { it.value.goalMinutesPlayed })
        val materializedRecovery = recoveryStore(restored).read(ACCOUNT)
        val materializedDaily = restored.dailyProgressDao().getAllOrdered()
        materializer(restored).materializeAll(ACCOUNT, FixedTime.nowMillis(), pageSize = 1)
        assertEquals(materializedRecovery, recoveryStore(restored).read(ACCOUNT))
        assertEquals(materializedDaily, restored.dailyProgressDao().getAllOrdered())

        // The next ordinary Steam read is measured from the imported 605 high-water, not zero.
        val committer = observationCommitter(restored)
        val unchangedAt = iso("2026-07-17T13:00:00Z")
        val unchanged = restored.withTransaction {
            committer.commit(
                observed = listOf(observed(605)),
                observedPlayAt = unchangedAt,
                syncedAt = unchangedAt,
                expectedAccount = ACCOUNT,
            )
        }
        assertFalse(unchanged.recordedPlay)
        assertEquals(0, unchanged.playedDeltaByAppId[APP] ?: 0)
        restored.playerProfileDao().updateSyncStatus(unchangedAt, null)
        val increaseAt = iso("2026-07-17T13:30:00Z")
        val increase = restored.withTransaction {
            committer.commit(
                observed = listOf(observed(620)),
                observedPlayAt = increaseAt,
                syncedAt = increaseAt,
                expectedAccount = ACCOUNT,
            )
        }
        assertTrue(increase.recordedPlay)
        assertEquals(15, increase.playedDeltaByAppId[APP])
        val finalContributions = dao.contributionsForGame(ACCOUNT, APP)
        assertEquals(3, finalContributions.size)
        assertEquals(220L, finalContributions.sumOf { it.authorizedMinutes })
        finalContributions.forEach { assertTrue(dao.validateGraph(it.contributionId)) }
        assertEquals(620L, dao.state(ACCOUNT, APP)!!.highWaterMinutes)
        assertEquals(620, restored.gameDao().getById(APP)!!.lastPlaytime)
    }

    private fun materializer(db: BacklogiumDatabase) = UnknownTimeMaterializer(
        database = db,
        recoveryDao = db.playtimeRecoveryDao(),
        dailyCredit = RecoveryDailyCreditCoordinator(db.dailyProgressDao()),
    )

    private fun openReceiver() = Room.databaseBuilder(
        RuntimeEnvironment.getApplication(),
        BacklogiumDatabase::class.java,
        receiverName,
    ).allowMainThreadQueries().build().also { receiver = it }

    private fun reopenReceiver() {
        receiver?.close()
        receiver = openReceiver()
    }

    private suspend fun seedBaseline(db: BacklogiumDatabase, highWater: Long, date: String) {
        db.playtimeRecoveryDao().upsertState(
            GameRecoveryState(
                account = ACCOUNT,
                appId = APP,
                baselineEpoch = 1L,
                highWaterMinutes = highWater,
                lastObservationAt = iso("$date" + "T12:00:00Z"),
                lastObservationDate = date,
                lastObservationZone = "UTC",
                clockState = "VALID",
                updatedAt = 0L,
            ),
        )
    }

    private fun contribution(id: String, from: Long, to: Long) = PlaytimeContribution(
        contributionId = id,
        account = ACCOUNT,
        appId = APP,
        baselineEpoch = 1L,
        fromMinutes = from,
        toMinutes = to,
        windowStartAt = iso("2026-07-10T12:00:00Z"),
        windowEndAt = iso("2026-07-16T12:00:00Z"),
        authorizedMinutes = to - from,
        revision = 1L,
        provenance = "RETROACTIVE_PLAY",
        zone = "UTC",
        observedZone = "UTC",
        createdAt = 0L,
    )

    private fun allocation(contributionId: String, minutes: Long, date: String) = UnknownTimeAllocation(
        placementId = "$contributionId:1:u:$date",
        contributionId = contributionId,
        account = ACCOUNT,
        appId = APP,
        revision = 1L,
        date = date,
        minutes = minutes,
        method = RecoveryAllocationMethods.SINGLE_DAY,
        zone = "UTC",
        windowStartAt = iso("2026-07-10T12:00:00Z"),
        windowEndAt = iso("2026-07-16T12:00:00Z"),
        createdAt = 0L,
    )

    private fun game() = Game(
        appId = APP,
        name = "Restore game",
        iconUrl = "",
        playtimeForever = 0,
        playtime2Weeks = 0,
        lastPlaytime = 0,
    )

    private fun newDatabase() = Room.inMemoryDatabaseBuilder(
        RuntimeEnvironment.getApplication(), BacklogiumDatabase::class.java,
    ).allowMainThreadQueries().build()

    private fun exportMapper(db: BacklogiumDatabase) = BackupExportMapper(
        gameDao = db.gameDao(),
        achievementDao = db.achievementDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        hiddenGameDao = db.hiddenGameDao(),
        excludedSharedGameDao = db.excludedSharedGameDao(),
        recoveryStore = DaoRecoveryBackupStore(db.playtimeRecoveryDao(), db.sessionDao()),
        settings = SettingsDataStore(RuntimeEnvironment.getApplication()),
        credentials = TestCredentials,
        time = FixedTime,
    )

    private fun mergeEngine(
        db: BacklogiumDatabase,
        recoveryBackupStore: RecoveryBackupStore = DaoRecoveryBackupStore(db.playtimeRecoveryDao(), db.sessionDao()),
    ) = BackupMergeEngine(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        hltbDataDao = db.hltbDataDao(),
        achievementDao = db.achievementDao(),
        playerProfileDao = db.playerProfileDao(),
        collectionDao = db.collectionDao(),
        excludedSharedGameDao = db.excludedSharedGameDao(),
        hiddenGameDao = db.hiddenGameDao(),
        gamificationUpdater = GamificationUpdater(
            db.sessionDao(),
            db.dailyProgressDao(),
            db.playerProfileDao(),
            db.hltbDataDao(),
            db.achievementDao(),
            db.gameDao(),
            db.hiddenGameDao(),
        ),
        time = FixedTime,
        derivedStateWrites = DerivedStateWriteCoordinator(),
        transaction = RoomDatabaseTransactionScope(db),
        recoveryStore = recoveryBackupStore,
        credentials = TestCredentials,
    )

    private fun recoveryStore(db: BacklogiumDatabase) =
        DaoRecoveryBackupStore(db.playtimeRecoveryDao(), db.sessionDao())

    private fun observationCommitter(db: BacklogiumDatabase) = PlaytimeObservationCommitter(
        gameDao = db.gameDao(),
        sessionDao = db.sessionDao(),
        dailyProgressDao = db.dailyProgressDao(),
        profileDao = db.playerProfileDao(),
        hiddenGameDao = db.hiddenGameDao(),
        differ = SessionDiffer(),
        time = FixedTime,
        sessionActionWriter = SessionActionWriter(
            db.sessionDao(), db.dailyProgressDao(), db.hiddenGameDao(), FixedTime,
        ),
        recoveryAdapter = PlaytimeRecoveryCommitAdapter(db.playtimeRecoveryDao(), db.sessionDao()),
    )

    private fun observed(playtime: Int) =
        PlaytimeObservationCommitter.ObservedGame(APP, "Restore game", "", playtime, 0)

    private fun iso(value: String): Long = Instant.parse(value).toEpochMilli()

    private fun exceptionMessages(error: Throwable?): String =
        generateSequence(error) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    private class FailAfterFirstGraphCommit(
        private val delegate: PlaytimeRecoveryDao,
    ) : PlaytimeRecoveryDao by delegate {
        private var failed = false

        override suspend fun commitAllocatedGraph(
            contribution: PlaytimeContribution,
            timed: List<com.example.backlogium.data.local.entity.TimedContributionPlacement>,
            allocations: List<UnknownTimeAllocation>,
            recipe: UnknownTimeAllocationRecipe?,
        ): Boolean {
            val committed = delegate.commitAllocatedGraph(contribution, timed, allocations, recipe)
            if (!failed) {
                failed = true
                error("injected failure after first recovery graph write")
            }
            return committed
        }
    }

    private class FailAfterFirstDailyDelta(
        private val delegate: DailyProgressDao,
    ) : DailyProgressDao by delegate {
        private var failed = false

        override suspend fun applyMinutesDelta(date: String, minutesDelta: Int, goalDelta: Int) {
            delegate.applyMinutesDelta(date, minutesDelta, goalDelta)
            if (!failed) {
                failed = true
                error("injected failure after raw daily-credit write")
            }
        }
    }

    private object TestCredentials : CredentialsProvider {
        override suspend fun currentCredentials() = CredentialsState.Configured("unit-test-key", ACCOUNT)
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = Instant.parse("2026-07-17T12:00:00Z").toEpochMilli()
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-17")
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val APP = 440L
    }
}
