package com.example.backlogium.domain

import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.data.repo.DataStoreProgressMarksStore
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.PlaytimeRecoveryCommitAdapter
import com.example.backlogium.data.repo.ProgressEventRepository
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** File-backed Room + Preferences DataStore crash/restart evidence for task 5.3. */
@RunWith(RobolectricTestRunner::class)
class RecoveryFinalizationCrashReplayTest {

    @Test
    fun resumeAfterDateAndZoneChangeUsesCapturedDateAndKeepsHistoricalCorrectionSilent() = runBlocking {
        val fixture = Fixture.open()
        try {
            fixture.seedInitialState()
            fixture.seedHistoricalContext()
            val baselineXp = fixture.updater.compute(TODAY, CONFIG).xpState.totalXp
            assertEquals(100L, baselineXp)

            val raw = fixture.rawMixedCommit()
            val inputs = fixture.inputs(raw)
            assertEquals(TODAY, inputs.evaluationDate)
            assertEquals(CONFIG, inputs.config)
            assertEquals(CONFIG_VERSION, inputs.configVersion)
            val operationId = requireNotNull(raw.finalizationOperationId)
            val beforeStageCrash = runCatching {
                fixture.finalizer(
                    compute = CrashAroundPersist(
                        delegate = GamificationRecoveryDerivedCompute(fixture.updater),
                        source = RecomputeSource.RETROACTIVE_PLAY,
                        afterPersist = true,
                    ),
                ).finalize(inputs, FIRST_NOW)
            }.exceptionOrNull()
            assertTrue(beforeStageCrash?.message.orEmpty().contains("injected process death after persist"))
            assertEquals(RecoveryJournalStatus.RAW_COMMITTED,
                fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)

            // Placement-only correction re-dates old pending quantity but stays silent, leaves the
            // fresh current-day play excluded, and never lowers the stored longest-streak record.
            assertEquals(baselineXp, fixture.database.playerProfileDao().get()!!.totalXp)
            assertEquals(12, fixture.database.playerProfileDao().get()!!.longestStreak)
            assertEquals(35, fixture.database.dailyProgressDao().getByDate("2026-09-09")!!.minutesPlayed)
            assertTrue(fixture.database.dailyProgressDao().getByDate("2026-09-09")!!.questMet)
            val marksAfterCorrection = fixture.marksStore.read()
            assertEquals(setOf(TODAY.minusDays(3)), marksAfterCorrection.pendingQuestDates)
            assertFalse(TODAY.minusDays(2) in marksAfterCorrection.pendingQuestDates)

            // Simulate a device-zone rollover and a later calendar day after the corrective write.
            fixture.clock.evaluationDate = NEXT_DAY
            fixture.clock.currentZone = ZoneId.of("Asia/Tokyo")
            fixture.reopen()
            val decoded = fixture.inputs(raw)
            assertEquals("resume must retain the raw operation's date", TODAY, decoded.evaluationDate)
            assertEquals("resume must retain the raw operation's rules", CONFIG, decoded.config)
            fixture.finalizer().resumePending(ACCOUNT, NEXT_DAY_NOW)

            val dao = fixture.database.playtimeRecoveryDao()
            assertEquals(RecoveryJournalStatus.FINALIZED, dao.journal(operationId)!!.derivedStatus)
            fixture.assertConservedGraphs()
            assertEquals(12, fixture.database.playerProfileDao().get()!!.longestStreak)
            assertEquals(30, fixture.database.dailyProgressDao().getByDate(TODAY.toString())!!.minutesPlayed)
            assertTrue(fixture.database.dailyProgressDao().getByDate(TODAY.toString())!!.questMet)
            assertNull("no credit or quest row is invented on the new device-local date",
                fixture.database.dailyProgressDao().getByDate(NEXT_DAY.toString()))
            val activeAllocations = dao.contributionsForAccount(ACCOUNT).flatMap {
                dao.activeAllocations(it.contributionId)
            }
            assertTrue(activeAllocations.all { it.zone == "UTC" || it.zone == "Z" })
            val correctedAllocations = dao.activeAllocations(
                PlaytimeRecoveryIdentity.contributionId(ACCOUNT, APP, 1L, 490L, 500L),
            )
            assertTrue("the pending contribution must retain its original frozen zone",
                correctedAllocations.all { it.zone == "UTC" })
            assertFalse(activeAllocations.any { it.date == NEXT_DAY.toString() })

            val finalMarks = fixture.marksStore.read()
            assertEquals(setOf(TODAY.minusDays(3), TODAY), finalMarks.pendingQuestDates)
            assertFalse(TODAY.minusDays(2) in finalMarks.pendingQuestDates)
            assertTrue(finalMarks.pendingLevelUps.contains(PendingLevelUp(1, 2)))
            assertTrue(finalMarks.pendingLevelUps.any {
                it.from == 2 && it.to == fixture.database.playerProfileDao().get()!!.level
            })
            val events = fixture.eventRepository.pendingEvents.first()
            assertTrue(events.contains(ProgressEvent.QuestMet(TODAY.minusDays(3))))
            assertTrue(events.any { it is ProgressEvent.LevelUp })
            assertFalse(events.any { it == ProgressEvent.QuestMet(TODAY.minusDays(2)) })
        } finally {
            fixture.closeAndDelete()
        }
    }

    @Test
    fun ruleChangeBetweenCorrectiveAndEarnedStagesCannotConsumeCapturedFreshEligibility() = runBlocking {
        val fixture = Fixture.open()
        try {
            fixture.seedInitialState()
            val capturedRules = fixture.setRuleConfig(CONFIG)
            val raw = fixture.rawMixedCommit(
                ruleConfig = capturedRules.config,
                ruleConfigVersion = capturedRules.version,
            )
            val inputs = fixture.inputs(raw)
            assertEquals(TODAY, inputs.evaluationDate)
            assertEquals(capturedRules, VersionedRuleConfig(inputs.config, inputs.configVersion))
            val operationId = requireNotNull(raw.finalizationOperationId)
            val beforeEarned = runCatching {
                fixture.finalizer(
                    recoveryDao = CrashAfterJournalMark(
                        fixture.database.playtimeRecoveryDao(), RecoveryJournalStatus.CORRECTIVE_DONE,
                    ),
                ).finalize(inputs, FIRST_NOW)
            }.exceptionOrNull()
            assertTrue(beforeEarned?.message.orEmpty().contains("injected process death after journal mark"))
            assertEquals(RecoveryJournalStatus.CORRECTIVE_DONE,
                fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)

            fixture.reopen()
            // Lowering the rule would make the raw daily row appear met in a RULE_CHANGE baseline.
            // A pending earned operation must finish under its captured version before this write.
            fixture.applyRuleChange(CONFIG.copy(questThresholdMin = 20))
            val changedRules = fixture.currentRuleConfig()
            assertEquals(20, changedRules.config.questThresholdMin)
            assertTrue(changedRules.version > capturedRules.version)
            fixture.reopen()
            fixture.finalizer().resumePending(ACCOUNT, SECOND_NOW)

            assertEquals(RecoveryJournalStatus.FINALIZED,
                fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
            fixture.assertConservedGraphs()
            val marks = fixture.marksStore.read()
            assertTrue("captured earned quest must be delivered under the raw version", TODAY in marks.pendingQuestDates)
            assertTrue(marks.pendingLevelUps.isNotEmpty())
            val events = fixture.eventRepository.pendingEvents.first()
            assertTrue(events.contains(ProgressEvent.QuestMet(TODAY)))
            assertTrue(events.any { it is ProgressEvent.LevelUp })
            assertEquals(changedRules.version, fixture.currentRuleConfig().version)
            assertEquals(changedRules.version,
                fixture.database.playerProfileDao().get()!!.gamificationConfigVersion)
            assertEquals(30, fixture.database.dailyProgressDao().getByDate(TODAY.toString())!!.minutesPlayed)
        } finally {
            fixture.closeAndDelete()
        }
    }

    @Test
    fun failedRawGraphWriteRollsBackBeforeRestartThenRetryEarnsExactlyOnce() = runBlocking {
        val fixture = Fixture.open()
        try {
            fixture.seedInitialState()
            val before = fixture.rawSnapshot()
            val failure = runCatching {
                fixture.rawMixedCommit(
                    recoveryDao = FailBeforeGraphCommit(fixture.database.playtimeRecoveryDao()),
                )
            }.exceptionOrNull()

            assertTrue("the raw graph boundary failpoint must escape", failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("injected failure before contribution graph write"))
            assertEquals("the Room transaction rolls back every earlier raw write", before, fixture.rawSnapshot())

            fixture.reopen()
            assertEquals(before, fixture.rawSnapshot())
            val retry = fixture.rawMixedCommit()
            assertEquals(30, retry.playedDeltaByAppId[APP])
            val inputs = fixture.inputs(retry)
            assertEquals(RecoveryFinalizeKind.MIXED, inputs.kind)
            fixture.finalizer().finalize(inputs, SECOND_NOW)
            fixture.assertEarnedState()
            assertEquals(0, fixture.database.playtimeRecoveryDao().pendingFinalization(ACCOUNT).size)
        } finally {
            fixture.closeAndDelete()
        }
    }

    @Test
    fun everyDurableFinalizationBoundaryResumesFromFileBackedRoomAndDataStore() = runBlocking {
        listOf(
            CrashPoint.AFTER_RAW_COMMIT,
            CrashPoint.BEFORE_CORRECTIVE_PERSIST,
            CrashPoint.AFTER_CORRECTIVE_PERSIST,
            CrashPoint.AFTER_CORRECTIVE_DONE_MARK,
            CrashPoint.BEFORE_EARNED_PERSIST,
            CrashPoint.AFTER_EARNED_PERSIST,
            CrashPoint.AFTER_EARNED_DONE_MARK,
            CrashPoint.AFTER_FINALIZED_MARK,
        ).forEach { point -> runFinalizationCrash(point) }
    }

    private suspend fun runFinalizationCrash(point: CrashPoint) {
        val fixture = Fixture.open()
        try {
            fixture.seedInitialState()
            val raw = fixture.rawMixedCommit()
            val inputs = fixture.inputs(raw)
            assertEquals("the raw operation must contain fresh current play and achievements", 1,
                inputs.earned.contributions.size)
            assertEquals(ACHIEVEMENTS_PER_OPERATION, inputs.earned.achievements.size)
            assertTrue(inputs.earned.datedCredits.isNotEmpty())
            val operationId = requireNotNull(raw.finalizationOperationId)
            assertEquals(RecoveryFinalizeKind.MIXED, inputs.kind)
            assertEquals(RecoveryJournalStatus.RAW_COMMITTED,
                fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)

            val fail = when (point) {
                CrashPoint.AFTER_RAW_COMMIT -> null
                CrashPoint.BEFORE_CORRECTIVE_PERSIST -> {
                    fixture.finalizer(
                        compute = CrashAroundPersist(
                            delegate = GamificationRecoveryDerivedCompute(fixture.updater),
                            source = RecomputeSource.RETROACTIVE_PLAY,
                            afterPersist = false,
                        ),
                    )
                }
                CrashPoint.AFTER_CORRECTIVE_PERSIST -> {
                    fixture.finalizer(
                        compute = CrashAroundPersist(
                            delegate = GamificationRecoveryDerivedCompute(fixture.updater),
                            source = RecomputeSource.RETROACTIVE_PLAY,
                            afterPersist = true,
                        ),
                    )
                }
                CrashPoint.BEFORE_EARNED_PERSIST -> {
                    fixture.finalizer(
                        compute = CrashAroundPersist(
                            delegate = GamificationRecoveryDerivedCompute(fixture.updater),
                            source = RecomputeSource.SYNC,
                            afterPersist = false,
                        ),
                    )
                }
                CrashPoint.AFTER_EARNED_PERSIST -> {
                    fixture.finalizer(
                        compute = CrashAroundPersist(
                            delegate = GamificationRecoveryDerivedCompute(fixture.updater),
                            source = RecomputeSource.SYNC,
                            afterPersist = true,
                        ),
                    )
                }
                CrashPoint.AFTER_CORRECTIVE_DONE_MARK -> {
                    fixture.finalizer(
                        recoveryDao = CrashAfterJournalMark(
                            fixture.database.playtimeRecoveryDao(), RecoveryJournalStatus.CORRECTIVE_DONE,
                        ),
                    )
                }
                CrashPoint.AFTER_EARNED_DONE_MARK -> {
                    fixture.finalizer(
                        recoveryDao = CrashAfterJournalMark(
                            fixture.database.playtimeRecoveryDao(), RecoveryJournalStatus.EARNED_DONE,
                        ),
                    )
                }
                CrashPoint.AFTER_FINALIZED_MARK -> {
                    fixture.finalizer(
                        recoveryDao = CrashAfterJournalMark(
                            fixture.database.playtimeRecoveryDao(), RecoveryJournalStatus.FINALIZED,
                        ),
                    )
                }
            }

            val failure = fail?.let { runCatching { it.finalize(inputs, FIRST_NOW) }.exceptionOrNull() }
            if (point == CrashPoint.AFTER_RAW_COMMIT) {
                assertEquals(RecoveryJournalStatus.RAW_COMMITTED,
                    fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
            } else {
                assertTrue("$point must be injected after its named durable boundary", failure is IllegalStateException)
                assertTrue(failure?.message.orEmpty().contains("injected process death"))
                assertEquals(point.statusAfterFailure,
                    fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
            }

            // Closing/reopening Room is the process-death boundary; the separately persisted
            // Preferences WAL/earned marks are intentionally retained by the same app DataStore.
            fixture.reopen()
            if (point == CrashPoint.AFTER_EARNED_PERSIST) {
                val earnedEvents = fixture.eventRepository.pendingEvents.first()
                assertTrue(earnedEvents.contains(ProgressEvent.QuestMet(TODAY)))
                assertTrue(earnedEvents.any { it is ProgressEvent.LevelUp })
                earnedEvents.forEach { fixture.eventRepository.acknowledge(it) }
                assertTrue(fixture.eventRepository.pendingEvents.first().isEmpty())
                fixture.reopen()
                assertTrue("acknowledgements must survive the second restart",
                    fixture.eventRepository.pendingEvents.first().isEmpty())
            }

            val resumedCompute = CountingRecoveryCompute(GamificationRecoveryDerivedCompute(fixture.updater))
            fixture.finalizer(compute = resumedCompute).resumePending(ACCOUNT, SECOND_NOW)
            assertEquals("restart must complete the same operation", RecoveryJournalStatus.FINALIZED,
                fixture.database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
            assertEquals(0, fixture.database.playtimeRecoveryDao().pendingFinalization(ACCOUNT).size)
            fixture.assertConservedGraphs()

            when (point) {
                CrashPoint.AFTER_RAW_COMMIT,
                CrashPoint.BEFORE_CORRECTIVE_PERSIST,
                CrashPoint.AFTER_CORRECTIVE_PERSIST -> {
                    assertEquals(1, resumedCompute.correctiveCalls)
                    assertEquals(1, resumedCompute.earnedCalls)
                }
                CrashPoint.AFTER_CORRECTIVE_DONE_MARK,
                CrashPoint.BEFORE_EARNED_PERSIST,
                CrashPoint.AFTER_EARNED_PERSIST -> {
                    assertEquals("durable corrective mark must prevent replaying correction", 0,
                        resumedCompute.correctiveCalls)
                    assertEquals(1, resumedCompute.earnedCalls)
                }
                CrashPoint.AFTER_EARNED_DONE_MARK,
                CrashPoint.AFTER_FINALIZED_MARK -> {
                    assertEquals("durable earned/finalized marks must not recompute either stage", 0,
                        resumedCompute.correctiveCalls)
                    assertEquals(0, resumedCompute.earnedCalls)
                }
            }

            if (point == CrashPoint.AFTER_EARNED_PERSIST) {
                // The independently persisted acknowledgement is not resurrected by replay. A new
                // post-restart mixed operation still earns its own later level transition.
                fixture.seedGame(SECOND_APP, highWater = 800, pendingMinutes = 10, observedAt = FIRST_NOW)
                val fresh = fixture.rawMixedCommit(SECOND_APP, highWater = 830, at = SECOND_NOW)
                val freshInputs = fixture.inputs(fresh)
                assertEquals(RecoveryFinalizeKind.MIXED, freshInputs.kind)
                fixture.finalizer().finalize(freshInputs, SECOND_NOW)
                val currentLevel = fixture.database.playerProfileDao().get()!!.level
                val marks = fixture.marksStore.read()
                assertTrue("new play/achievement remains eligible after the acknowledged retry",
                    marks.pendingLevelUps.any { it.from < currentLevel && it.to == currentLevel })
                assertTrue(marks.pendingQuestDates.isEmpty())
                val events = fixture.eventRepository.pendingEvents.first()
                assertTrue(events.any { it is ProgressEvent.LevelUp })
                assertFalse(events.any { it == ProgressEvent.QuestMet(TODAY) })
            } else {
                fixture.assertEarnedState()
            }
        } finally {
            fixture.closeAndDelete()
        }
    }

    @Test
    fun rejectedRawTransactionBeforeGraphWriteLeavesNoPartialStateAndCanRetryAfterRestart() = runBlocking {
        val fixture = Fixture.open()
        try {
            fixture.seedInitialState()
            val before = fixture.rawSnapshot()
            val failure = runCatching {
                fixture.rawMixedCommit(
                    recoveryDao = FailBeforeGraphCommit(fixture.database.playtimeRecoveryDao()),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure?.message.orEmpty().contains("injected failure before contribution graph write"))
            assertEquals(before, fixture.rawSnapshot())

            fixture.reopen()
            assertEquals(before, fixture.rawSnapshot())
            val retry = fixture.rawMixedCommit()
            val inputs = fixture.inputs(retry)
            fixture.finalizer().finalize(inputs, SECOND_NOW)
            fixture.assertEarnedState()
            fixture.assertConservedGraphs()
        } finally {
            fixture.closeAndDelete()
        }
    }

    private enum class CrashPoint(val statusAfterFailure: String) {
        AFTER_RAW_COMMIT(RecoveryJournalStatus.RAW_COMMITTED),
        BEFORE_CORRECTIVE_PERSIST(RecoveryJournalStatus.RAW_COMMITTED),
        AFTER_CORRECTIVE_PERSIST(RecoveryJournalStatus.RAW_COMMITTED),
        AFTER_CORRECTIVE_DONE_MARK(RecoveryJournalStatus.CORRECTIVE_DONE),
        BEFORE_EARNED_PERSIST(RecoveryJournalStatus.CORRECTIVE_DONE),
        AFTER_EARNED_PERSIST(RecoveryJournalStatus.CORRECTIVE_DONE),
        AFTER_EARNED_DONE_MARK(RecoveryJournalStatus.EARNED_DONE),
        AFTER_FINALIZED_MARK(RecoveryJournalStatus.FINALIZED),
    }

    private class CrashAroundPersist(
        private val delegate: RecoveryDerivedCompute,
        private val source: RecomputeSource,
        private val afterPersist: Boolean,
    ) : RecoveryDerivedCompute by delegate {
        private var crashed = false

        override suspend fun persist(
            result: GamificationResult,
            source: RecomputeSource,
            configVersion: Long,
            operationId: String?,
        ) {
            if (!crashed && source == this.source && !afterPersist) {
                crashed = true
                error("injected process death before persist for $source")
            }
            delegate.persist(result, source, configVersion, operationId)
            if (!crashed && source == this.source && afterPersist) {
                crashed = true
                error("injected process death after persist for $source")
            }
        }
    }

    private class CountingRecoveryCompute(
        private val delegate: RecoveryDerivedCompute,
    ) : RecoveryDerivedCompute by delegate {
        var correctiveCalls: Int = 0
            private set
        var earnedCalls: Int = 0
            private set

        override suspend fun computeCorrective(
            today: LocalDate,
            config: RuleConfig,
            excludedEarned: RecoveryEarnedInputs,
        ): GamificationResult {
            correctiveCalls++
            return delegate.computeCorrective(today, config, excludedEarned)
        }

        override suspend fun computeEarned(today: LocalDate, config: RuleConfig): GamificationResult {
            earnedCalls++
            return delegate.computeEarned(today, config)
        }
    }

    private class CrashAfterJournalMark(
        private val delegate: PlaytimeRecoveryDao,
        private val targetStatus: String,
    ) : PlaytimeRecoveryDao by delegate {
        private var crashed = false

        override suspend fun markJournalStatus(operationId: String, status: String, updatedAt: Long): Int {
            val updated = delegate.markJournalStatus(operationId, status, updatedAt)
            if (!crashed && status == targetStatus) {
                crashed = true
                error("injected process death after journal mark $status")
            }
            return updated
        }
    }

    private class FailBeforeGraphCommit(
        private val delegate: PlaytimeRecoveryDao,
    ) : PlaytimeRecoveryDao by delegate {
        override suspend fun commitAllocatedGraph(
            contribution: PlaytimeContribution,
            timed: List<TimedContributionPlacement>,
            allocations: List<UnknownTimeAllocation>,
            recipe: UnknownTimeAllocationRecipe?,
        ): Boolean = error("injected failure before contribution graph write")
    }

    private class Fixture private constructor(
        private val app: android.content.Context,
        private val databaseName: String,
        var database: BacklogiumDatabase,
        private val settings: SettingsDataStore,
        val clock: MutableTestTime,
    ) {
        lateinit var marksStore: DataStoreProgressMarksStore
            private set
        lateinit var updater: GamificationUpdater
            private set
        lateinit var eventRepository: ProgressEventRepository
            private set
        private lateinit var transitionCoordinator: ProgressTransitionCoordinator
        private lateinit var derivedStateWrites: DerivedStateWriteCoordinator
        private lateinit var syncCoordinator: SteamSyncCoordinator
        private lateinit var committer: PlaytimeObservationCommitter

        companion object {
            suspend fun open(): Fixture {
                val app = RuntimeEnvironment.getApplication()
                val databaseName = "fr${System.nanoTime()}.db"
                val settings = SettingsDataStore(app)
                val clock = MutableTestTime()
                settings.writeProgressMarks(initialMarks())
                val fixture = Fixture(app, databaseName, openDatabase(app, databaseName), settings, clock)
                fixture.wire()
                return fixture
            }

            private fun openDatabase(app: android.content.Context, name: String) =
                Room.databaseBuilder(app, BacklogiumDatabase::class.java, name)
                    .allowMainThreadQueries()
                    .build()

            private fun initialMarks() = ProgressMarks(
                lastCelebratedLevel = 1,
                lastQuestCelebratedDate = TODAY.minusDays(1),
                initialized = true,
            )
        }

        private fun wire() {
            marksStore = DataStoreProgressMarksStore(settings)
            transitionCoordinator = ProgressTransitionCoordinator()
            derivedStateWrites = DerivedStateWriteCoordinator()
            syncCoordinator = SteamSyncCoordinator()
            updater = GamificationUpdater(
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
            eventRepository = ProgressEventRepository(
                marksStore = marksStore,
                profileDao = database.playerProfileDao(),
                dailyProgressDao = database.dailyProgressDao(),
                transitionCoordinator = transitionCoordinator,
            )
            committer = newCommitter(database.playtimeRecoveryDao())
        }

        suspend fun seedHistoricalContext() {
            val profile = requireNotNull(database.playerProfileDao().get())
            database.playerProfileDao().upsert(
                profile.copy(totalXp = 100L, level = 2, currentStreak = 0, longestStreak = 12),
            )
            val game = requireNotNull(database.gameDao().getById(APP))
            database.gameDao().upsert(game.copy(backfillMinutes = 90))
            listOf(
                DailyProgress(TODAY.minusDays(3).toString(), minutesPlayed = 30, questMet = true),
                DailyProgress(TODAY.minusDays(2).toString(), minutesPlayed = 0, questMet = false),
                DailyProgress(TODAY.minusDays(1).toString(), minutesPlayed = 25, questMet = false),
            ).forEach { database.dailyProgressDao().upsert(it) }
            settings.writeProgressMarks(
                ProgressMarks(
                    lastCelebratedLevel = 1,
                    lastQuestCelebratedDate = TODAY.minusDays(4),
                    initialized = true,
                    pendingQuestDates = setOf(TODAY.minusDays(3)),
                    pendingLevelUps = setOf(PendingLevelUp(1, 2)),
                ),
            )
        }

        suspend fun setRuleConfig(config: RuleConfig): VersionedRuleConfig =
            settings.setRuleConfigAndGetVersion(config)

        suspend fun currentRuleConfig(): VersionedRuleConfig = settings.ruleConfigWithVersionFlow.first()

        suspend fun applyRuleChange(config: RuleConfig) {
            UpdateRuleConfigUseCase(
                settings = DataStoreSettingsRepository(settings),
                gamificationUpdater = updater,
                time = clock,
                derivedStateWrites = derivedStateWrites,
                syncCoordinator = syncCoordinator,
                playerProfileDao = database.playerProfileDao(),
                materializer = UnknownTimeMaterializer(
                    database,
                    database.playtimeRecoveryDao(),
                    RecoveryDailyCreditCoordinator(database.dailyProgressDao()),
                ),
                recoveryFinalizer = finalizer(),
            ).apply(config)
        }

        fun changeClock(date: LocalDate, zone: ZoneId) {
            clock.evaluationDate = date
            clock.currentZone = zone
        }

        private fun newCommitter(recoveryDao: PlaytimeRecoveryDao) = PlaytimeObservationCommitter(
            gameDao = database.gameDao(),
            sessionDao = database.sessionDao(),
            dailyProgressDao = database.dailyProgressDao(),
            profileDao = database.playerProfileDao(),
            hiddenGameDao = database.hiddenGameDao(),
            differ = SessionDiffer(),
            time = clock,
            sessionActionWriter = SessionActionWriter(
                database.sessionDao(), database.dailyProgressDao(), database.hiddenGameDao(), clock,
            ),
            recoveryAdapter = PlaytimeRecoveryCommitAdapter(recoveryDao, database.sessionDao()),
        )

        suspend fun seedInitialState() {
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
            seedGame(APP, highWater = 500, pendingMinutes = 10)
        }

        suspend fun seedGame(
            appId: Long,
            highWater: Int,
            pendingMinutes: Int,
            observedAt: Long = BASELINE_AT,
        ) {
            database.gameDao().upsert(
                Game(
                    appId = appId,
                    name = "Crash replay game $appId",
                    iconUrl = "",
                    playtimeForever = highWater,
                    playtime2Weeks = 0,
                    lastPlaytime = highWater,
                    lastSyncedAt = observedAt,
                    source = GameSource.STEAM_OWNED,
                ),
            )
            database.playtimeRecoveryDao().upsertState(
                GameRecoveryState(
                    account = ACCOUNT,
                    appId = appId,
                    baselineEpoch = 1L,
                    highWaterMinutes = highWater.toLong(),
                    lastObservationAt = observedAt,
                    lastObservationDate = TODAY.toString(),
                    lastObservationZone = "UTC",
                    clockState = "VALID",
                    updatedAt = observedAt,
                ),
            )
            if (pendingMinutes <= 0) return

            val from = highWater - pendingMinutes
            val contributionId = PlaytimeRecoveryIdentity.contributionId(
                ACCOUNT, appId, 1L, from.toLong(), highWater.toLong(),
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
                        method = RecoveryAllocationMethods.PENDING_DATE_UNKNOWN,
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

        suspend fun rawMixedCommit(
            appId: Long = APP,
            highWater: Int = 530,
            at: Long = FIRST_NOW,
            recoveryDao: PlaytimeRecoveryDao = database.playtimeRecoveryDao(),
            ruleConfig: RuleConfig = CONFIG,
            ruleConfigVersion: Long = CONFIG_VERSION,
        ): PlaytimeObservationCommitter.Commit = database.withTransaction {
            val achievements = freshAchievements(appId, at)
            database.achievementDao().upsertAll(achievements)
            val snapshots = achievements.mapTo(mutableSetOf()) { achievement ->
                RecoveryAchievementSnapshot(
                    appId = appId,
                    apiName = achievement.apiName,
                    unlockedBefore = false,
                    snapshotPercentBefore = null,
                )
            }
            val commit = newCommitter(recoveryDao).commit(
                observed = listOf(
                    PlaytimeObservationCommitter.ObservedGame(
                        appId = appId,
                        name = "Crash replay game $appId",
                        iconUrl = "",
                        playtimeForever = highWater,
                        playtime2Weeks = 0,
                    ),
                ),
                observedPlayAt = at,
                syncedAt = at,
                expectedAccount = ACCOUNT,
                ruleConfig = ruleConfig,
                ruleConfigVersion = ruleConfigVersion,
                earnedAchievements = snapshots,
            )
            database.playerProfileDao().updateSyncStatus(lastSyncAt = at, lastSyncError = null)
            commit
        }

        private fun freshAchievements(appId: Long, at: Long) =
            (0 until ACHIEVEMENTS_PER_OPERATION).map { index ->
                val apiName = "crash-$appId-achievement-$index"
                Achievement(
                    appId = appId,
                    apiName = apiName,
                    displayName = apiName,
                    unlocked = true,
                    unlockedAt = at,
                    globalPercent = 0.0,
                    snapshotPercent = 0.0,
                    fetchedAt = at,
                )
            }

        suspend fun inputs(commit: PlaytimeObservationCommitter.Commit): RecoveryDerivedFinalizeInputs {
            val operationId = requireNotNull(commit.finalizationOperationId)
            val journal = requireNotNull(database.playtimeRecoveryDao().journal(operationId))
            return RecoveryDerivedFinalizeInputs.fromJson(
                payload = requireNotNull(journal.payloadJson),
                expectedOperationId = operationId,
            )
        }

        fun finalizer(
            recoveryDao: PlaytimeRecoveryDao = database.playtimeRecoveryDao(),
            compute: RecoveryDerivedCompute = GamificationRecoveryDerivedCompute(updater),
        ) = RecoveryDerivedFinalizer(recoveryDao, derivedStateWrites, compute)

        suspend fun reopen() {
            database.close()
            database = Room.databaseBuilder(app, BacklogiumDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            wire()
        }

        suspend fun rawSnapshot() = RawSnapshot(
            games = database.gameDao().getAll(),
            sessions = database.sessionDao().getAll(),
            daily = database.dailyProgressDao().getAllOrdered(),
            profile = database.playerProfileDao().get(),
            achievements = database.achievementDao().getAllUnlocked(),
            states = database.playtimeRecoveryDao().statesForAccount(ACCOUNT),
            contributions = database.playtimeRecoveryDao().contributionsForAccount(ACCOUNT),
            allocations = database.playtimeRecoveryDao().contributionsForAccount(ACCOUNT).flatMap {
                database.playtimeRecoveryDao().activeAllocations(it.contributionId)
            },
        )

        suspend fun assertConservedGraphs() {
            database.playtimeRecoveryDao().contributionsForAccount(ACCOUNT).forEach { contribution ->
                assertTrue(database.playtimeRecoveryDao().validateGraph(contribution.contributionId))
                assertEquals(
                    contribution.authorizedMinutes,
                    database.playtimeRecoveryDao().conservedAllocatedMinutes(contribution.contributionId),
                )
            }
        }

        suspend fun assertEarnedState() {
            assertConservedGraphs()
            val profile = database.playerProfileDao().get()!!
            val today = database.dailyProgressDao().getByDate(TODAY.toString())!!
            assertEquals(30, today.minutesPlayed)
            assertTrue(today.questMet)
            val marks = marksStore.read()
            assertEquals(setOf(TODAY), marks.pendingQuestDates)
            assertEquals(setOf(PendingLevelUp(1, profile.level)), marks.pendingLevelUps)
            val events = eventRepository.pendingEvents.first()
            assertEquals(2, events.size)
            assertTrue(events.contains(ProgressEvent.QuestMet(TODAY)))
            assertTrue(events.contains(ProgressEvent.LevelUp(1, profile.level)))
        }

        suspend fun closeAndDelete() {
            database.close()
            app.deleteDatabase(databaseName)
        }
    }

    private data class RawSnapshot(
        val games: List<Game>,
        val sessions: List<com.example.backlogium.data.local.entity.Session>,
        val daily: List<DailyProgress>,
        val profile: PlayerProfile?,
        val achievements: List<Achievement>,
        val states: List<GameRecoveryState>,
        val contributions: List<PlaytimeContribution>,
        val allocations: List<UnknownTimeAllocation>,
    )

    private class MutableTestTime : TimeProvider {
        var evaluationDate: LocalDate = TODAY
        var currentZone: ZoneId = ZoneOffset.UTC
        override fun nowMillis(): Long = FIRST_NOW
        override fun zone(): ZoneId = currentZone
        override fun today(): LocalDate = evaluationDate
    }

    private companion object {
        const val ACCOUNT = "76561198000000000"
        const val APP = 440L
        const val SECOND_APP = 441L
        const val ACHIEVEMENTS_PER_OPERATION = 4
        const val CONFIG_VERSION = 7L
        val TODAY = LocalDate.parse("2026-09-10")
        val NEXT_DAY = TODAY.plusDays(1)
        val BASELINE_AT = Instant.parse("2026-09-10T11:00:00Z").toEpochMilli()
        val FIRST_NOW = Instant.parse("2026-09-10T12:00:00Z").toEpochMilli()
        val SECOND_NOW = Instant.parse("2026-09-10T12:01:00Z").toEpochMilli()
        val NEXT_DAY_NOW = Instant.parse("2026-09-11T12:00:00Z").toEpochMilli()
        val OLD_WINDOW_START = Instant.parse("2026-09-08T12:00:00Z").toEpochMilli()
        val OLD_WINDOW_END = Instant.parse("2026-09-09T12:00:00Z").toEpochMilli()
        val CONFIG = RuleConfig(questThresholdMin = 30)
    }
}
