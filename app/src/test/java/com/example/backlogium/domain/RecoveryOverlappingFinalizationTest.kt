package com.example.backlogium.domain

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.backup.BackupComputed
import com.example.backlogium.data.backup.BackupFile
import com.example.backlogium.data.backup.BackupIdentity
import com.example.backlogium.data.backup.BackupLibrarySortPrefs
import com.example.backlogium.data.backup.BackupMergeEngine
import com.example.backlogium.data.backup.BackupPlayerProfile
import com.example.backlogium.data.backup.BackupRecovery
import com.example.backlogium.data.backup.BackupRuleConfig
import com.example.backlogium.data.backup.DaoRecoveryBackupStore
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.repo.AccountRoomReset
import com.example.backlogium.data.repo.DataStoreProgressMarksStore
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.ProgressEventRepository
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.work.SteamSyncCoordinator
import com.example.backlogium.domain.GameSource
import com.example.backlogium.gamification.RuleConfig
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Real-Room characterization of the raw-commit/finalization gap (task 5.1). */
@RunWith(RobolectricTestRunner::class)
class RecoveryOverlappingFinalizationTest {

    @Test
    fun unfencedCorrectiveOnlyBeforeEarnedRaw_reproducesLostQuestEligibility() = runBlocking {
        val unfenced = runCorrectiveEarnedScenario(
            rawOrder = listOf(CORRECTIVE_APP, EARNED_APP),
            pauseBothBeforeDerived = true,
        )
        val serial = runCorrectiveEarnedScenario(
            rawOrder = listOf(CORRECTIVE_APP, EARNED_APP),
            pauseBothBeforeDerived = false,
        )

        assertTrue("serial earned execution should owe today's quest", TODAY in serial.marks.pendingQuestDates)
        assertTrue(serial.events.contains(ProgressEvent.QuestMet(TODAY)))
        assertFalse(
            "reproduced R2: the unfenced corrective pass reseeded past the later earned quest",
            TODAY in unfenced.marks.pendingQuestDates,
        )
        assertFalse(unfenced.events.any { it == ProgressEvent.QuestMet(TODAY) })
    }

    @Test
    fun unfencedEarnedRawBeforeCorrectiveRaw_reproducesLostQuestEligibility() = runBlocking {
        val unfenced = runCorrectiveEarnedScenario(
            rawOrder = listOf(EARNED_APP, CORRECTIVE_APP),
            pauseBothBeforeDerived = true,
        )
        val serial = runCorrectiveEarnedScenario(
            rawOrder = listOf(EARNED_APP, CORRECTIVE_APP),
            pauseBothBeforeDerived = false,
        )

        assertTrue("serial earned execution should owe today's quest", TODAY in serial.marks.pendingQuestDates)
        assertTrue(serial.events.contains(ProgressEvent.QuestMet(TODAY)))
        assertFalse(
            "reproduced R2 in the reverse raw order: correction consumed the not-yet-finalized quest",
            TODAY in unfenced.marks.pendingQuestDates,
        )
        assertFalse(unfenced.events.any { it == ProgressEvent.QuestMet(TODAY) })
    }

    @Test
    fun unfencedMixedAThenBRawBeforeFinalization_reproducesLostQuestAndLevelTransition() = runBlocking {
        val unfenced = runMixedScenario(
            rawOrder = listOf(MIXED_A_APP, MIXED_B_APP),
            finalizeOrder = listOf(MIXED_A_APP, MIXED_B_APP),
            pauseBothBeforeDerived = true,
        )
        val serial = runMixedScenario(
            rawOrder = listOf(MIXED_A_APP, MIXED_B_APP),
            finalizeOrder = listOf(MIXED_A_APP, MIXED_B_APP),
            pauseBothBeforeDerived = false,
        )

        assertEquals("both commits must be genuine mixed operations", 2, unfenced.mixedOperations)
        assertTrue("serial operations should earn today's quest", TODAY in serial.marks.pendingQuestDates)
        assertEquals("each serialized mixed operation crosses an ordinary level threshold", 2,
            serial.marks.pendingLevelUps.size)
        assertTrue(serial.events.contains(ProgressEvent.QuestMet(TODAY)))
        assertFalse(TODAY in unfenced.marks.pendingQuestDates)
        assertEquals("the race retains only the later level transition", 1, unfenced.marks.pendingLevelUps.size)
        assertFalse(
            "the unacknowledged early level transition is consumed when correction persists after both raw commits",
            serial.marks.pendingLevelUps == unfenced.marks.pendingLevelUps,
        )
    }

    @Test
    fun unfencedMixedBThenARawBeforeFinalization_reproducesLostQuestAndLevelTransition() = runBlocking {
        val unfenced = runMixedScenario(
            rawOrder = listOf(MIXED_B_APP, MIXED_A_APP),
            finalizeOrder = listOf(MIXED_B_APP, MIXED_A_APP),
            pauseBothBeforeDerived = true,
        )
        val serial = runMixedScenario(
            rawOrder = listOf(MIXED_B_APP, MIXED_A_APP),
            finalizeOrder = listOf(MIXED_B_APP, MIXED_A_APP),
            pauseBothBeforeDerived = false,
        )

        assertEquals("both commits must be genuine mixed operations", 2, unfenced.mixedOperations)
        assertTrue("serial operations should earn today's quest", TODAY in serial.marks.pendingQuestDates)
        assertEquals("each serialized mixed operation crosses an ordinary level threshold", 2,
            serial.marks.pendingLevelUps.size)
        assertTrue(serial.events.contains(ProgressEvent.QuestMet(TODAY)))
        assertFalse(TODAY in unfenced.marks.pendingQuestDates)
        assertEquals("the race retains only the later level transition", 1, unfenced.marks.pendingLevelUps.size)
        assertFalse(
            "the unacknowledged early level transition is consumed when correction persists after both raw commits",
            serial.marks.pendingLevelUps == unfenced.marks.pendingLevelUps,
        )
    }

    @Test
    fun syncBarrierKeepsCorrectiveAndEarnedOperationsAtomicInBothOrders() = runBlocking {
        listOf(
            listOf(CORRECTIVE_APP, EARNED_APP),
            listOf(EARNED_APP, CORRECTIVE_APP),
        ).forEach { order ->
            val guarded = runGatedWorkerScenario(order, mixed = false)
            val serial = runCorrectiveEarnedScenario(order, pauseBothBeforeDerived = false)
            assertTrue(TODAY in serial.marks.pendingQuestDates)
            assertEquals(serial.marks.pendingQuestDates, guarded.marks.pendingQuestDates)
            assertEquals(serial.marks.pendingLevelUps, guarded.marks.pendingLevelUps)
            assertEquals(serial.events, guarded.events)
        }
    }

    @Test
    fun syncBarrierKeepsTwoMixedPlayAndAchievementOperationsAtomicInBothOrders() = runBlocking {
        listOf(
            listOf(MIXED_A_APP, MIXED_B_APP),
            listOf(MIXED_B_APP, MIXED_A_APP),
        ).forEach { order ->
            val guarded = runGatedWorkerScenario(order, mixed = true)
            val serial = runMixedScenario(
                rawOrder = order,
                finalizeOrder = order,
                pauseBothBeforeDerived = false,
            )
            assertEquals(2, guarded.mixedOperations)
            assertTrue(TODAY in serial.marks.pendingQuestDates)
            assertEquals(2, serial.marks.pendingLevelUps.size)
            assertEquals(serial.marks.pendingQuestDates, guarded.marks.pendingQuestDates)
            assertEquals(serial.marks.pendingLevelUps, guarded.marks.pendingLevelUps)
            assertEquals(serial.events, guarded.events)
        }
    }

    @Test
    fun finalizationBarrierDoesNotDeadlockWithResetRestoreOrRuleConfigWriters() = runBlocking {
        val gate = WalGate()
        val fixture = Fixture.open(gate)
        try {
            fixture.seedProfileAndToday()
            fixture.seedGame(CORRECTIVE_APP, highWater = 500, pendingMinutes = 10)
            val coordinator = SteamSyncCoordinator()
            var rawInputs: RecoveryDerivedFinalizeInputs? = null
            val worker = async {
                fixture.workerOperation(
                    coordinator = coordinator,
                    appId = CORRECTIVE_APP,
                    highWater = 500,
                    at = FIRST_NOW,
                    afterRawCommit = { commit -> rawInputs = fixture.inputs(commit) },
                )
            }

            // The finalizer now owns sync/account, derived-state, and WAL locks. These are the
            // ordered competing writers used by the real account reset and backup merge paths.
            gate.reached.await()
            val resetStarted = CompletableDeferred<Unit>()
            val restoreStarted = CompletableDeferred<Unit>()
            val configStarted = CompletableDeferred<Unit>()
            val resetDone = CompletableDeferred<Unit>()
            val restoreDone = CompletableDeferred<Unit>()
            val configDone = CompletableDeferred<Unit>()
            val reset = async {
                resetStarted.complete(Unit)
                fixture.resetUnderBarrier(coordinator)
                resetDone.complete(Unit)
            }
            val restore = async {
                restoreStarted.complete(Unit)
                fixture.restoreUnderBarrier(coordinator)
                restoreDone.complete(Unit)
            }
            val config = async {
                configStarted.complete(Unit)
                fixture.applyRuleChange(CONFIG.copy(questThresholdMin = 45))
                configDone.complete(Unit)
            }
            resetStarted.await()
            restoreStarted.await()
            configStarted.await()
            yield()
            assertFalse(resetDone.isCompleted)
            assertFalse(restoreDone.isCompleted)
            assertFalse(configDone.isCompleted)

            gate.release.complete(Unit)
            withTimeout(10_000L) {
                worker.await()
                reset.await()
                restore.await()
                config.await()
            }
            assertEquals(ACCOUNT, fixture.profile().steamId)
            assertEquals(0, fixture.pendingFinalizationCount())
            assertEquals(CONFIG, requireNotNull(rawInputs).config)
            assertEquals(45, fixture.currentRuleConfig().questThresholdMin)
        } finally {
            gate.release.complete(Unit)
            fixture.close()
        }
    }

    private suspend fun runCorrectiveEarnedScenario(
        rawOrder: List<Long>,
        pauseBothBeforeDerived: Boolean,
    ): Snapshot {
        val fixture = Fixture.open()
        try {
            fixture.seedProfileAndToday()
            fixture.seedGame(CORRECTIVE_APP, highWater = 500, pendingMinutes = 10)
            fixture.seedGame(EARNED_APP, highWater = 800, pendingMinutes = 0)

            var correction: PlaytimeObservationCommitter.Commit? = null
            var earned: PlaytimeObservationCommitter.Commit? = null
            for ((index, appId) in rawOrder.withIndex()) {
                val at = if (index == 0) FIRST_NOW else SECOND_NOW
                val commit = if (appId == CORRECTIVE_APP) {
                    fixture.rawCommit(appId, highWater = 500, at = at)
                } else {
                    fixture.rawCommit(appId, highWater = 830, at = at)
                }
                if (appId == CORRECTIVE_APP) {
                    correction = commit
                    val inputs = fixture.inputs(commit)
                    assertEquals(RecoveryFinalizeKind.CORRECTIVE_ONLY, inputs.kind)
                    assertTrue(inputs.earned.isEmpty)
                } else {
                    earned = commit
                    assertNull("fresh-only worker input uses the ordinary earned recompute", commit.finalizationOperationId)
                    assertEquals(30, commit.playedDeltaByAppId[appId])
                }

                if (!pauseBothBeforeDerived) {
                    if (appId == CORRECTIVE_APP) {
                        fixture.finalize(requireNotNull(correction))
                    } else {
                        fixture.persistOrdinaryEarned()
                    }
                }
            }

            if (pauseBothBeforeDerived) {
                fixture.finalize(requireNotNull(correction))
                fixture.persistOrdinaryEarned()
            }
            assertNotNull(earned)
            fixture.assertAllGraphsConserved()
            return fixture.snapshot()
        } finally {
            fixture.close()
        }
    }

    private suspend fun runMixedScenario(
        rawOrder: List<Long>,
        finalizeOrder: List<Long>,
        pauseBothBeforeDerived: Boolean,
    ): Snapshot {
        val fixture = Fixture.open()
        try {
            fixture.seedProfileAndToday()
            fixture.seedGame(MIXED_A_APP, highWater = 500, pendingMinutes = 10)
            fixture.seedGame(MIXED_B_APP, highWater = 800, pendingMinutes = 10)

            val commits = mutableMapOf<Long, PlaytimeObservationCommitter.Commit>()
            for ((index, appId) in rawOrder.withIndex()) {
                val at = if (index == 0) FIRST_NOW else SECOND_NOW
                val highWater = if (appId == MIXED_A_APP) 530 else 830
                val commit = fixture.rawCommit(
                    appId = appId,
                    highWater = highWater,
                    at = at,
                    achievements = fixture.freshAchievements(appId),
                )
                commits[appId] = commit
                val inputs = fixture.inputs(commit)
                assertEquals("the committer must create the mixed payload", RecoveryFinalizeKind.MIXED, inputs.kind)
                assertEquals(1, inputs.earned.contributions.size)
                assertEquals(ACHIEVEMENTS_PER_OPERATION, inputs.earned.achievements.size)
                assertTrue("fresh play must carry dated earned input", inputs.earned.datedCredits.isNotEmpty())

                if (!pauseBothBeforeDerived) fixture.finalize(commit)
            }
            if (pauseBothBeforeDerived) {
                assertEquals(2, fixture.pendingFinalizationCount())
                finalizeOrder.forEach { appId -> fixture.finalize(requireNotNull(commits[appId])) }
            }
            assertEquals(0, fixture.pendingFinalizationCount())
            fixture.assertAllGraphsConserved()
            return fixture.snapshot(mixedOperations = commits.size)
        } finally {
            fixture.close()
        }
    }

    /**
     * Worker-shaped schedule: pause the first real Room operation after raw commit while it still
     * owns the shared account/sync mutex, start the competing observer, prove it cannot reach its
     * own raw boundary, then let the first operation finish its real derived tail before releasing
     * the second. This is the exact isolation the periodic and targeted worker wiring must retain.
     */
    private suspend fun runGatedWorkerScenario(rawOrder: List<Long>, mixed: Boolean): Snapshot {
        val fixture = Fixture.open()
        try {
            fixture.seedProfileAndToday()
            fixture.seedGame(
                appId = if (mixed) MIXED_A_APP else CORRECTIVE_APP,
                highWater = 500,
                pendingMinutes = 10,
            )
            fixture.seedGame(
                appId = if (mixed) MIXED_B_APP else EARNED_APP,
                highWater = 800,
                pendingMinutes = if (mixed) 10 else 0,
            )
            val coordinator = SteamSyncCoordinator()
            val firstRawCommitted = CompletableDeferred<Unit>()
            val allowFirstFinalize = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val secondRawCommitted = CompletableDeferred<Unit>()

            return coroutineScope {
                val firstApp = rawOrder.first()
                val secondApp = rawOrder.last()
                val first = async {
                    fixture.workerOperation(
                        coordinator = coordinator,
                        appId = firstApp,
                        highWater = fixture.nextHighWater(firstApp),
                        at = FIRST_NOW,
                        achievements = if (mixed) fixture.freshAchievements(firstApp) else emptyList(),
                    ) {
                        firstRawCommitted.complete(Unit)
                        allowFirstFinalize.await()
                    }
                }
                firstRawCommitted.await()

                val second = async {
                    secondStarted.complete(Unit)
                    fixture.workerOperation(
                        coordinator = coordinator,
                        appId = secondApp,
                        highWater = fixture.nextHighWater(secondApp),
                        at = SECOND_NOW,
                        achievements = if (mixed) fixture.freshAchievements(secondApp) else emptyList(),
                    ) {
                        secondRawCommitted.complete(Unit)
                    }
                }
                secondStarted.await()
                yield()
                assertFalse(
                    "the competing Steam observation must wait until the first raw operation finalizes",
                    secondRawCommitted.isCompleted,
                )
                allowFirstFinalize.complete(Unit)
                first.await()
                second.await()
                assertTrue(secondRawCommitted.isCompleted)
                fixture.assertAllGraphsConserved()
                fixture.snapshot(mixedOperations = if (mixed) 2 else 1)
            }
        } finally {
            fixture.close()
        }
    }

    private data class Snapshot(
        val marks: ProgressMarks,
        val events: List<ProgressEvent>,
        val level: Int,
        val todayProgress: DailyProgress?,
        val mixedOperations: Int = 0,
    )

    /** Blocks after the durable write-ahead record is stored, while the real finalizer locks hold. */
    private class WalGate {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class GatedProgressMarksStore(
        private val delegate: ProgressMarksStore,
        private val gate: WalGate,
    ) : ProgressMarksStore by delegate {
        override suspend fun update(transform: (ProgressMarks) -> ProgressMarks): ProgressMarks {
            val before = delegate.read()
            val after = delegate.update(transform)
            if (before.pendingTransition == null && after.pendingTransition != null) {
                gate.reached.complete(Unit)
                gate.release.await()
            }
            return after
        }
    }

    private class Fixture private constructor(
        private val database: BacklogiumDatabase,
        private val settings: SettingsDataStore,
        private val marksStore: ProgressMarksStore,
        private val updater: GamificationUpdater,
        private val finalizer: RecoveryDerivedFinalizer,
        private val materializer: UnknownTimeMaterializer,
        private val committer: PlaytimeObservationCommitter,
        private val eventRepository: ProgressEventRepository,
        private val derivedStateWrites: DerivedStateWriteCoordinator,
        private val transitionCoordinator: ProgressTransitionCoordinator,
    ) {
        companion object {
            suspend fun open(gate: WalGate? = null): Fixture {
                val context = RuntimeEnvironment.getApplication()
                val database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
                    .allowMainThreadQueries()
                    .build()
                val settings = SettingsDataStore(context)
                val durableMarksStore = DataStoreProgressMarksStore(settings)
                val marksStore: ProgressMarksStore = if (gate == null) {
                    durableMarksStore
                } else {
                    GatedProgressMarksStore(durableMarksStore, gate)
                }
                settings.writeProgressMarks(
                    ProgressMarks(
                        lastCelebratedLevel = 1,
                        lastQuestCelebratedDate = TODAY.minusDays(1),
                        initialized = true,
                    ),
                )
                val transitionCoordinator = ProgressTransitionCoordinator()
                val derivedStateWrites = DerivedStateWriteCoordinator()
                val updater = GamificationUpdater(
                    sessionDao = database.sessionDao(),
                    dailyProgressDao = database.dailyProgressDao(),
                    playerProfileDao = database.playerProfileDao(),
                    hltbDataDao = database.hltbDataDao(),
                    achievementDao = database.achievementDao(),
                    gameDao = database.gameDao(),
                    hiddenGameDao = database.hiddenGameDao(),
                    progressMarksStore = marksStore,
                    transitionCoordinator = transitionCoordinator,
                )
                val eventRepository = ProgressEventRepository(
                    marksStore = marksStore,
                    profileDao = database.playerProfileDao(),
                    dailyProgressDao = database.dailyProgressDao(),
                    transitionCoordinator = transitionCoordinator,
                )
                val finalizer = RecoveryDerivedFinalizer(
                    recoveryDao = database.playtimeRecoveryDao(),
                    derivedStateWrites = derivedStateWrites,
                    compute = GamificationRecoveryDerivedCompute(updater),
                )
                val materializer = UnknownTimeMaterializer(
                    database = database,
                    recoveryDao = database.playtimeRecoveryDao(),
                    dailyCredit = RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
                )
                val time = FixedTime
                val committer = PlaytimeObservationCommitter(
                    gameDao = database.gameDao(),
                    sessionDao = database.sessionDao(),
                    dailyProgressDao = database.dailyProgressDao(),
                    profileDao = database.playerProfileDao(),
                    hiddenGameDao = database.hiddenGameDao(),
                    differ = SessionDiffer(),
                    time = time,
                    sessionActionWriter = SessionActionWriter(
                        database.sessionDao(), database.dailyProgressDao(), database.hiddenGameDao(), time,
                    ),
                    recoveryAdapter = PlaytimeRecoveryCommitAdapter(
                        database.playtimeRecoveryDao(), database.sessionDao(),
                    ),
                )
                return Fixture(
                    database,
                    settings,
                    marksStore,
                    updater,
                    finalizer,
                    materializer,
                    committer,
                    eventRepository,
                    derivedStateWrites,
                    transitionCoordinator,
                )
            }
        }

        suspend fun seedProfileAndToday() {
            database.playerProfileDao().upsert(
                PlayerProfile(
                    steamId = ACCOUNT,
                    lastSyncAt = BASELINE_AT,
                    totalXp = 0L,
                    level = 1,
                    currentStreak = 0,
                    longestStreak = 0,
                ),
            )
            database.dailyProgressDao().upsert(
                DailyProgress(date = TODAY.toString(), minutesPlayed = 0, goalMinutesPlayed = 0, questMet = false),
            )
        }

        suspend fun seedGame(appId: Long, highWater: Int, pendingMinutes: Int) {
            database.gameDao().upsert(
                Game(
                    appId = appId,
                    name = "Recovery R2 test game $appId",
                    iconUrl = "",
                    playtimeForever = highWater,
                    playtime2Weeks = 0,
                    lastPlaytime = highWater,
                    lastSyncedAt = BASELINE_AT,
                    source = GameSource.STEAM_OWNED,
                ),
            )
            database.playtimeRecoveryDao().upsertState(
                GameRecoveryState(
                    account = ACCOUNT,
                    appId = appId,
                    baselineEpoch = 1L,
                    highWaterMinutes = highWater.toLong(),
                    lastObservationAt = BASELINE_AT,
                    lastObservationDate = TODAY.toString(),
                    lastObservationZone = "UTC",
                    clockState = "VALID",
                    updatedAt = BASELINE_AT,
                ),
            )
            if (pendingMinutes <= 0) return

            val from = highWater - pendingMinutes
            val contributionId = PlaytimeRecoveryIdentity.contributionId(
                ACCOUNT,
                appId,
                1L,
                from.toLong(),
                highWater.toLong(),
            )
            val contribution = PlaytimeContribution(
                contributionId = contributionId,
                account = ACCOUNT,
                appId = appId,
                baselineEpoch = 1L,
                fromMinutes = from.toLong(),
                toMinutes = highWater.toLong(),
                windowStartAt = OLD_WINDOW_START,
                windowEndAt = OLD_WINDOW_END,
                authorizedMinutes = pendingMinutes.toLong(),
                revision = 1L,
                provenance = "RETROACTIVE_PLAY",
                zone = "UTC",
                observedZone = "UTC",
                createdAt = OLD_WINDOW_END,
            )
            database.playtimeRecoveryDao().commitAllocatedGraph(
                contribution = contribution,
                timed = emptyList(),
                allocations = listOf(
                    UnknownTimeAllocation(
                        placementId = "$contributionId:1:pending",
                        contributionId = contributionId,
                        account = ACCOUNT,
                        appId = appId,
                        revision = 1L,
                        date = null,
                        minutes = pendingMinutes.toLong(),
                        method = "PENDING_DATE_UNKNOWN",
                        zone = "UTC",
                        windowStartAt = OLD_WINDOW_START,
                        windowEndAt = OLD_WINDOW_END,
                        pendingDate = true,
                        createdAt = OLD_WINDOW_END,
                    ),
                ),
            )
            assertTrue(database.playtimeRecoveryDao().validateGraph(contributionId))
        }

        suspend fun freshAchievements(appId: Long): List<Achievement> =
            (0 until ACHIEVEMENTS_PER_OPERATION).map { index ->
                val apiName = "r2-$appId-achievement-$index"
                Achievement(
                    appId = appId,
                    apiName = apiName,
                    displayName = apiName,
                    unlocked = true,
                    unlockedAt = FIRST_NOW,
                    globalPercent = 0.0,
                    snapshotPercent = 0.0,
                    fetchedAt = FIRST_NOW,
                )
            }

        suspend fun rawCommit(
            appId: Long,
            highWater: Int,
            at: Long,
            achievements: List<Achievement> = emptyList(),
        ): PlaytimeObservationCommitter.Commit = database.withTransaction {
            if (achievements.isNotEmpty()) database.achievementDao().upsertAll(achievements)
            val freshSnapshots = achievements.mapTo(mutableSetOf()) { achievement ->
                RecoveryAchievementSnapshot(
                    appId = achievement.appId,
                    apiName = achievement.apiName,
                    unlockedBefore = false,
                    snapshotPercentBefore = null,
                )
            }
            val commit = committer.commit(
                observed = listOf(
                    PlaytimeObservationCommitter.ObservedGame(
                        appId = appId,
                        name = "Recovery R2 test game $appId",
                        iconUrl = "",
                        playtimeForever = highWater,
                        playtime2Weeks = 0,
                    ),
                ),
                observedPlayAt = at,
                syncedAt = at,
                expectedAccount = ACCOUNT,
                ruleConfig = CONFIG,
                ruleConfigVersion = CONFIG_VERSION,
                earnedAchievements = freshSnapshots,
            )
            database.playerProfileDao().updateSyncStatus(lastSyncAt = at, lastSyncError = null)
            commit
        }

        fun nextHighWater(appId: Long): Int = when (appId) {
            CORRECTIVE_APP -> 500
            EARNED_APP -> 830
            MIXED_A_APP -> 530
            MIXED_B_APP -> 830
            else -> error("unexpected task 5.1 app id $appId")
        }

        suspend fun workerOperation(
            coordinator: SteamSyncCoordinator,
            appId: Long,
            highWater: Int,
            at: Long,
            achievements: List<Achievement> = emptyList(),
            afterRawCommit: suspend (PlaytimeObservationCommitter.Commit) -> Unit = {},
        ): PlaytimeObservationCommitter.Commit = coordinator.withLock {
            // Both production workers drain prior durable recovery work under this barrier before
            // starting another raw observation; the finalizer remains under it through the tail.
            materializer.materializeAll(ACCOUNT, at)
            finalizer.resumePending(ACCOUNT, at)
            val commit = rawCommit(appId, highWater, at, achievements)
            afterRawCommit(commit)
            if (commit.finalizationOperationId == null) {
                persistOrdinaryEarned()
            } else {
                finalize(commit)
            }
            commit
        }

        suspend fun resetUnderBarrier(coordinator: SteamSyncCoordinator) {
            coordinator.withLock {
                derivedStateWrites.withLock {
                    transitionCoordinator.withTransition {
                        AccountRoomReset(database).resetForAccountChange(ACCOUNT)
                    }
                }
            }
        }

        suspend fun restoreUnderBarrier(coordinator: SteamSyncCoordinator) {
            mergeEngine(coordinator).merge(emptyBackup(), CONFIG, CONFIG_VERSION)
        }

        suspend fun applyRuleChange(config: RuleConfig) {
            UpdateRuleConfigUseCase(
                settings = DataStoreSettingsRepository(settings),
                gamificationUpdater = updater,
                time = FixedTime,
                derivedStateWrites = derivedStateWrites,
            ).apply(config)
        }

        suspend fun currentRuleConfig(): RuleConfig = settings.ruleConfigWithVersionFlow.first().config

        suspend fun profile(): PlayerProfile = requireNotNull(database.playerProfileDao().get())

        private fun mergeEngine(coordinator: SteamSyncCoordinator) = BackupMergeEngine(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            hltbDataDao = database.hltbDataDao(),
            achievementDao = database.achievementDao(),
            playerProfileDao = database.playerProfileDao(),
            collectionDao = database.collectionDao(),
            excludedSharedGameDao = database.excludedSharedGameDao(),
            hiddenGameDao = database.hiddenGameDao(),
            gamificationUpdater = updater,
            time = FixedTime,
            derivedStateWrites = derivedStateWrites,
            transaction = RoomDatabaseTransactionScope(database),
            recoveryStore = DaoRecoveryBackupStore(database.playtimeRecoveryDao(), database.sessionDao()),
            syncCoordinator = coordinator,
        )

        private fun emptyBackup() = BackupFile(
            formatVersion = 3,
            exportedAt = "2026-09-10T12:00:00Z",
            identity = BackupIdentity(ACCOUNT),
            ruleConfig = BackupRuleConfig(1, 100, 30, "ANY_GAME", 0, 5, 10, 20, 40, 80),
            games = emptyList(),
            achievements = emptyList(),
            sessions = emptyList(),
            dailyProgress = emptyList(),
            hltbData = emptyList(),
            librarySortPrefs = BackupLibrarySortPrefs("NAME", "NAME"),
            playerProfile = BackupPlayerProfile(0, 1, 0, 0, false),
            computed = BackupComputed(emptyList(), emptyList()),
            recovery = BackupRecovery(
                observations = emptyList(),
                contributions = emptyList(),
                timedPlacements = emptyList(),
                unknownAllocations = emptyList(),
                recipes = emptyList(),
                replacements = emptyList(),
            ),
        )

        suspend fun inputs(commit: PlaytimeObservationCommitter.Commit): RecoveryDerivedFinalizeInputs {
            val operationId = requireNotNull(commit.finalizationOperationId)
            val journal = requireNotNull(database.playtimeRecoveryDao().journal(operationId))
            assertEquals(RecoveryJournalStatus.RAW_COMMITTED, journal.derivedStatus)
            return RecoveryDerivedFinalizeInputs.fromJson(
                payload = requireNotNull(journal.payloadJson),
                expectedOperationId = operationId,
            )
        }

        suspend fun finalize(commit: PlaytimeObservationCommitter.Commit) {
            val operationId = requireNotNull(commit.finalizationOperationId)
            finalizer.finalize(inputs(commit), SECOND_NOW)
            assertEquals(
                RecoveryJournalStatus.FINALIZED,
                database.playtimeRecoveryDao().journal(operationId)?.derivedStatus,
            )
        }

        suspend fun persistOrdinaryEarned() {
            updater.recompute(
                today = TODAY,
                source = RecomputeSource.SYNC,
                config = CONFIG,
                configVersion = CONFIG_VERSION,
            )
        }

        suspend fun assertAllGraphsConserved() {
            database.playtimeRecoveryDao().contributionsForAccount(ACCOUNT).forEach { contribution ->
                assertTrue(database.playtimeRecoveryDao().validateGraph(contribution.contributionId))
                assertEquals(
                    contribution.authorizedMinutes,
                    database.playtimeRecoveryDao().conservedAllocatedMinutes(contribution.contributionId),
                )
            }
        }

        suspend fun pendingFinalizationCount(): Int =
            database.playtimeRecoveryDao().pendingFinalization(ACCOUNT).size

        suspend fun snapshot(mixedOperations: Int = 0): Snapshot = Snapshot(
            marks = marksStore.read(),
            events = eventRepository.pendingEvents.first(),
            level = database.playerProfileDao().get()!!.level,
            todayProgress = database.dailyProgressDao().getByDate(TODAY.toString()),
            mixedOperations = mixedOperations,
        )

        fun close() = database.close()
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = FIRST_NOW
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun today(): LocalDate = TODAY
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val CORRECTIVE_APP = 440L
        const val EARNED_APP = 441L
        const val MIXED_A_APP = 442L
        const val MIXED_B_APP = 443L
        const val ACHIEVEMENTS_PER_OPERATION = 4
        const val CONFIG_VERSION = 7L
        val TODAY = LocalDate.parse("2026-09-10")
        val BASELINE_AT = Instant.parse("2026-09-10T11:00:00Z").toEpochMilli()
        val FIRST_NOW = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        val SECOND_NOW = Instant.parse("2026-09-10T12:01:00Z").toEpochMilli()
        val OLD_WINDOW_START = Instant.parse("2026-09-08T12:00:00Z").toEpochMilli()
        val OLD_WINDOW_END = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()
        val CONFIG = RuleConfig(questThresholdMin = 30)
    }
}
