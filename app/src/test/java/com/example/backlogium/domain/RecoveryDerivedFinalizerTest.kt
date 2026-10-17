package com.example.backlogium.domain

import androidx.room.Room
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.RecoveryProvenance
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import com.example.backlogium.gamification.QuestResult
import com.example.backlogium.gamification.RuleConfig
import com.example.backlogium.gamification.XpState
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Staged corrective-then-earned derived finalization for a recovery commit (tasks 5.5/5.6): the
 * operation's own stage flags decide which passes run, every stage resumes from the journal's
 * durable state at exactly the next unfinished write, and a persisted/acknowledged transition is
 * never re-derived into a duplicate — including a real persist crash between the earned write and
 * its journal mark, with the user acknowledgement happening in between.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryDerivedFinalizerTest {

    private lateinit var database: BacklogiumDatabase

    private val account = "76561198000000000"
    private val appId = 440L
    private val today = LocalDate.parse("2026-09-01")
    private val contributionId = PlaytimeRecoveryIdentity.contributionId(account, appId, 1L, 500L, 605L)
    private val operationId = "op:1"
    private val capturedConfig = RuleConfig()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            BacklogiumDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.playerProfileDao().upsert(PlayerProfile(steamId = account))
            database.playtimeRecoveryDao().insertContributionChecked(contribution)
        }
    }

    @After
    fun tearDown() = database.close()

    private val contribution = PlaytimeContribution(
        contributionId = contributionId,
        account = account,
        appId = appId,
        baselineEpoch = 1L,
        fromMinutes = 500L,
        toMinutes = 605L,
        windowEndAt = 2L,
        authorizedMinutes = 105L,
        revision = 2L,
        provenance = RecoveryProvenance.RETROACTIVE_PLAY,
        zone = "UTC",
        createdAt = 1L,
    )

    private val earned: RecoveryEarnedInputs = RecoveryEarnedInputs(
        contributions = setOf(
            RecoveryContributionSnapshot(
                account = account,
                appId = appId,
                baselineEpoch = 1L,
                fromMinutes = 535L,
                toMinutes = 545L,
                revision = 1L,
                authorizedMinutes = 10L,
            ),
        ),
        achievements = setOf(
            RecoveryAchievementSnapshot(appId = appId, apiName = "win_5"),
        ),
        datedCredits = setOf(
            RecoveryDatedCreditSnapshot(
                account = account,
                appId = appId,
                contributionId = RecoveryContributionSnapshot(
                    account = account,
                    appId = appId,
                    baselineEpoch = 1L,
                    fromMinutes = 535L,
                    toMinutes = 545L,
                    revision = 1L,
                    authorizedMinutes = 10L,
                ).identity(),
                revision = 1L,
                date = today.toString(),
                minutes = 10,
                goalMinutes = 10,
            ),
        ),
    )

    private fun inputs(
        kind: RecoveryFinalizeKind,
        corrective: Set<RecoveryContributionSnapshot>? = null,
        earned: RecoveryEarnedInputs? = null,
    ) = RecoveryDerivedFinalizeInputs(
        kind = kind,
        operationId = operationId,
        account = account,
        evaluationDate = today,
        configVersion = 7L,
        config = capturedConfig,
        correctiveContributions = corrective ?: if (kind == RecoveryFinalizeKind.EARNED_ONLY) {
            emptySet()
        } else {
            setOf(
                RecoveryContributionSnapshot(
                    account = account,
                    appId = appId,
                    baselineEpoch = 1L,
                    fromMinutes = 500L,
                    toMinutes = 535L,
                    revision = 1L,
                    authorizedMinutes = 35L,
                ),
            )
        },
        earned = earned ?: if (kind == RecoveryFinalizeKind.CORRECTIVE_ONLY) {
            RecoveryEarnedInputs()
        } else {
            this.earned
        },
    )

    private fun journal(status: String, forInputs: RecoveryDerivedFinalizeInputs) =
        PlaytimeRecoveryJournal(
            operationId = operationId,
            contributionId = contributionId,
            account = account,
            appId = appId,
            priorRevision = 1L,
            currentRevision = 2L,
            affectedDatesCsv = "2026-08-31,2026-09-01",
            derivedStatus = status,
            payloadJson = RecoveryDerivedFinalizeInputs.encode(forInputs),
            createdAt = 1L,
            updatedAt = 1L,
        )

    /** A fake [RecoveryDerivedCompute] recording every stage it was asked to run and persisting. */
    private class FakeCompute : RecoveryDerivedCompute {
        val correctiveCalls = mutableListOf<Pair<LocalDate, RecoveryEarnedInputs>>()
        val earnedCalls = mutableListOf<LocalDate>()
        val persisted = mutableListOf<Triple<RecomputeSource, Long, String?>>()
        var seenConfig: RuleConfig? = null
        var failOnPersistCall: Int? = null
        private var persistCount = 0

        var correctiveLevel = 12
        var earnedLevel = 13

        private fun result(evaluationDate: LocalDate, level: Int) = GamificationResult(
            xpState = XpState(totalXp = 0, level = level, xpIntoLevel = 0, xpForNext = 100),
            questResults = listOf(QuestResult(evaluationDate, false)),
            currentStreak = 0,
            longestStreak = 0,
            changedDays = emptyList(),
            evaluationDate = evaluationDate,
        )

        override suspend fun computeCorrective(
            today: LocalDate,
            config: RuleConfig,
            excludedEarned: RecoveryEarnedInputs,
        ): GamificationResult {
            correctiveCalls += today to excludedEarned
            seenConfig = config
            return result(today, correctiveLevel)
        }

        override suspend fun computeEarned(today: LocalDate, config: RuleConfig): GamificationResult {
            earnedCalls += today
            seenConfig = config
            return result(today, earnedLevel)
        }

        override suspend fun persist(
            result: GamificationResult,
            source: RecomputeSource,
            configVersion: Long,
            operationId: String?,
        ) {
            persistCount++
            if (failOnPersistCall == persistCount) {
                throw IllegalStateException("simulated process death after persist #$persistCount")
            }
            persisted += Triple(source, configVersion, operationId)
        }
    }

    private fun finalizer(compute: RecoveryDerivedCompute) = RecoveryDerivedFinalizer(
        recoveryDao = database.playtimeRecoveryDao(),
        derivedStateWrites = DerivedStateWriteCoordinator(),
        compute = compute,
    )

    @Test
    fun mixedFinalizationRunsCorrectiveThenEarnedWithCapturedInputsAndFinishes() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        val compute = FakeCompute()

        finalizer(compute).finalize(inputs, now = 2L)

        // Corrective ran first and received the full fresh earned exclusion set.
        assertEquals(listOf(today to earned), compute.correctiveCalls)
        // Earned ran second.
        assertEquals(listOf(today), compute.earnedCalls)
        assertEquals(
            listOf(
                Triple(RecomputeSource.RETROACTIVE_PLAY, 7L, operationId),
                Triple(RecomputeSource.SYNC, 7L, operationId),
            ),
            compute.persisted,
        )
        // The captured rule config, not whatever settings currently hold, drove both passes.
        assertEquals(capturedConfig, compute.seenConfig)
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
        assertTrue(database.playtimeRecoveryDao().pendingFinalization(account).isEmpty())
    }

    @Test
    fun earnedOnlyFinalizationNeverRunsTheCorrectiveBaseline() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.EARNED_ONLY)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        val compute = FakeCompute()

        finalizer(compute).finalize(inputs, now = 2L)

        // The single-stage rule: an earned-only sync must not run a corrective baseline first,
        // because that would swallow the fresh earned event into a reseed.
        assertTrue(compute.correctiveCalls.isEmpty())
        assertEquals(listOf(today), compute.earnedCalls)
        assertEquals(listOf(Triple(RecomputeSource.SYNC, 7L, operationId)), compute.persisted)
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
    }

    @Test
    fun correctiveOnlyFinalizationNeverRunsTheEarnedPass() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.CORRECTIVE_ONLY, earned = RecoveryEarnedInputs())
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        val compute = FakeCompute()

        finalizer(compute).finalize(inputs, now = 2L)

        assertTrue(compute.earnedCalls.isEmpty())
        assertEquals(1, compute.correctiveCalls.size)
        assertEquals(listOf(Triple(RecomputeSource.RETROACTIVE_PLAY, 7L, operationId)), compute.persisted)
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
    }

    @Test
    fun finalizeIsANoOpWhenTheJournalIsAlreadyFinalized() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.FINALIZED, inputs))
        val compute = FakeCompute()

        finalizer(compute).finalize(inputs, now = 2L)

        assertTrue(compute.correctiveCalls.isEmpty())
        assertTrue(compute.earnedCalls.isEmpty())
        assertTrue(compute.persisted.isEmpty())
    }

    @Test
    fun crashBetweenCorrectivePersistAndJournalMarkResumesFromTheEarnedStage() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        val crashed = FakeCompute().apply { failOnPersistCall = 1 }

        val thrown = runCatching { finalizer(crashed).finalize(inputs, now = 2L) }.exceptionOrNull()
        assertTrue("corrective persist failure must propagate, was $thrown", thrown != null)
        assertEquals(RecoveryJournalStatus.RAW_COMMITTED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)

        // Restart: corrective is retried idempotently (journal still RAW), then earned runs once.
        val restarted = FakeCompute()
        finalizer(restarted).finalize(inputs, now = 3L)
        assertEquals(1, restarted.correctiveCalls.size)
        assertEquals(1, restarted.earnedCalls.size)
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
    }

    @Test
    fun resumeFromCorrectiveDoneRunsTheEarnedPassOnly() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.CORRECTIVE_DONE, inputs))
        val compute = FakeCompute()

        finalizer(compute).finalize(inputs, now = 2L)

        assertTrue("corrective stage already done must not recompute", compute.correctiveCalls.isEmpty())
        assertEquals(listOf(today), compute.earnedCalls)
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
    }

    @Test
    fun resumeFromEarnedDoneFinalizesWithoutRecomputingEitherStage() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.EARNED_DONE, inputs))
        val compute = FakeCompute()

        finalizer(compute).finalize(inputs, now = 2L)

        assertTrue(compute.correctiveCalls.isEmpty())
        assertTrue(compute.earnedCalls.isEmpty())
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
    }

    @Test
    fun payloadRoundTripsIdentitiesQuantitiesAndKind() {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        val decoded = RecoveryDerivedFinalizeInputs.fromJson(
            RecoveryDerivedFinalizeInputs.encode(inputs),
            operationId,
        )
        assertEquals(inputs, decoded)
        assertEquals(earned, decoded.earned)
        assertEquals(35L, decoded.correctiveContributions.single().authorizedMinutes)
    }

    @Test
    fun fromJsonRejectsAMismatchedOperationIdentityOrMalformedPayload() {
        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        val payload = RecoveryDerivedFinalizeInputs.encode(inputs)

        val wrongId = runCatching { RecoveryDerivedFinalizeInputs.fromJson(payload, "op:other") }.exceptionOrNull()
        assertTrue("payload for another operation must be rejected, was $wrongId", wrongId != null)

        val raw = runCatching { RecoveryDerivedFinalizeInputs.fromJson("not-a-finalize-payload", operationId) }.exceptionOrNull()
        assertTrue("raw non-finalize payload must be rejected, was $raw", raw != null)
    }

    @Test
    fun structurallyInconsistentKindsAreRejectedAtConstruction() {
        val wrong = runCatching {
            // Corrective-only carrying a fresh earned input is a contradiction on its face.
            inputs(RecoveryFinalizeKind.CORRECTIVE_ONLY, earned = earned)
        }.exceptionOrNull()
        assertTrue("corrective-only with earned inputs must be rejected, was $wrong", wrong != null)

        val earnedWrong = runCatching {
            inputs(RecoveryFinalizeKind.EARNED_ONLY, corrective = setOf(
                RecoveryContributionSnapshot(
                    account = account,
                    appId = appId,
                    baselineEpoch = 1L,
                    fromMinutes = 500L,
                    toMinutes = 535L,
                    revision = 1L,
                    authorizedMinutes = 35L,
                ),
            ))
        }.exceptionOrNull()
        assertTrue("earned-only with corrective contributions must be rejected, was $earnedWrong", earnedWrong != null)
    }

    @Test
    fun resumePendingFinalizesEveryUnfinishedOperationForTheAccount() = runBlocking {
        val inputs = inputs(RecoveryFinalizeKind.EARNED_ONLY)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        val compute = FakeCompute()

        finalizer(compute).resumePending(account, now = 2L)

        assertEquals(1, compute.earnedCalls.size)
        assertTrue(compute.correctiveCalls.isEmpty())
        assertTrue(database.playtimeRecoveryDao().pendingFinalization(account).isEmpty())
    }

    @Test
    fun ackBetweenEarnedPersistAndJournalMarkDoesNotReemitOnResume() = runBlocking {
        // The reviewer-flagged failpoint, with REAL persist through the marks protocol:
        // 1) earned stage persists and finalizes marks (fresh LevelUp(12,13) now owed, mark 12);
        //    the process dies before the EARNED_DONE journal mark lands;
        // 2) the user acknowledges the event (mark 13, obligation cleared);
        // 3) resume must not re-emit that acknowledged transition.
        val coordinator = ProgressTransitionCoordinator()
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 12, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 12))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val updater = testUpdater(marksStore, coordinator, profileDao, dailyDao)

        val crashCompute = object : RecoveryDerivedCompute {
            private var crashed = false
            override suspend fun computeCorrective(today: LocalDate, config: RuleConfig, excludedEarned: RecoveryEarnedInputs) =
                progressResult(today, level = 12)
            override suspend fun computeEarned(today: LocalDate, config: RuleConfig) =
                progressResult(today, level = 13)
            override suspend fun persist(result: GamificationResult, source: RecomputeSource, configVersion: Long, operationId: String?) {
                if (source == RecomputeSource.SYNC && !crashed) {
                    updater.persist(result, source, configVersion, operationId)
                    crashed = true
                    throw IllegalStateException("simulated death after earned persist, before EARNED_DONE")
                }
                updater.persist(result, source, configVersion, operationId)
            }
        }

        val inputs = inputs(RecoveryFinalizeKind.EARNED_ONLY, corrective = emptySet())
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        runCatching { finalizer(crashCompute).finalize(inputs, now = 2L) }.exceptionOrNull()

        // Room and marks reflect the earned write; the journal still claims raw-committed.
        assertEquals(13, profileDao.get()!!.level)
        assertEquals(RecoveryJournalStatus.RAW_COMMITTED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)

        // The fresh event is owed now and gets acknowledged — the deadline between stage writes.
        val repository = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertEquals(listOf(ProgressEvent.LevelUp(12, 13)), repository.pendingEvents.first())
        repository.acknowledge(ProgressEvent.LevelUp(12, 13))
        assertEquals(13, marksStore.read().lastCelebratedLevel)
        assertTrue(marksStore.read().pendingLevelUps.isEmpty())

        // Resume: earned-only, so no corrective pass; the re-run measures 13 against an
        // acknowledged mark of 13 and emits nothing.
        finalizer(crashCompute).finalize(inputs, now = 3L)
        assertEquals(RecoveryJournalStatus.FINALIZED, database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus)
        assertEquals(13, profileDao.get()!!.level)
        assertTrue(marksStore.read().pendingLevelUps.isEmpty())
        assertTrue(repository.pendingEvents.first().isEmpty())
    }

    @Test
    fun mixedAckBetweenEarnedPersistAndJournalMarkNeverRerunsCorrectiveOrReemits() = runBlocking {
        // The reviewer's worst case for a MIXED operation: corrective persists and its CORRECTIVE_DONE
        // mark lands, the earned persist lands and finalizes marks (fresh LevelUp(12,13) owed), then
        // the process dies before EARNED_DONE. The user acknowledges the event. The retry must treat
        // CORRECTIVE_DONE as authoritative — never recompute the corrective baseline (which would
        // reseed the marks past the acknowledged level) and never re-derive the acknowledged event.
        val coordinator = ProgressTransitionCoordinator()
        val marksStore = InMemoryProgressMarksStore(
            ProgressMarks(lastCelebratedLevel = 12, initialized = true),
        )
        val profileDao = FakePlayerProfileDao(PlayerProfile(level = 12))
        val dailyDao = FakeDailyProgressDao(emptyList())
        val updater = testUpdater(marksStore, coordinator, profileDao, dailyDao)

        val crashCompute = object : RecoveryDerivedCompute {
            private var crashed = false
            override suspend fun computeCorrective(today: LocalDate, config: RuleConfig, excludedEarned: RecoveryEarnedInputs) =
                progressResult(today, level = 12)
            override suspend fun computeEarned(today: LocalDate, config: RuleConfig) =
                progressResult(today, level = 13)
            override suspend fun persist(result: GamificationResult, source: RecomputeSource, configVersion: Long, operationId: String?) {
                updater.persist(result, source, configVersion, operationId)
                if (source == RecomputeSource.SYNC && !crashed) {
                    crashed = true
                    throw IllegalStateException("simulated death after earned persist, before EARNED_DONE")
                }
            }
        }

        val inputs = inputs(RecoveryFinalizeKind.MIXED)
        database.playtimeRecoveryDao().upsertJournal(journal(RecoveryJournalStatus.RAW_COMMITTED, inputs))
        runCatching { finalizer(crashCompute).finalize(inputs, now = 2L) }.exceptionOrNull()

        // Corrective completed durably before the crash; earned persisted; journal sits at
        // CORRECTIVE_DONE — the retry can never re-run the corrective stage.
        assertEquals(
            RecoveryJournalStatus.CORRECTIVE_DONE,
            database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus,
        )
        assertEquals(13, profileDao.get()!!.level)

        val repository = testRepository(marksStore, coordinator, profileDao, dailyDao)
        assertEquals(listOf(ProgressEvent.LevelUp(12, 13)), repository.pendingEvents.first())
        repository.acknowledge(ProgressEvent.LevelUp(12, 13))
        assertEquals(13, marksStore.read().lastCelebratedLevel)
        assertTrue(marksStore.read().pendingLevelUps.isEmpty())

        finalizer(crashCompute).finalize(inputs, now = 3L)

        assertEquals(
            RecoveryJournalStatus.FINALIZED,
            database.playtimeRecoveryDao().journal(operationId)!!.derivedStatus,
        )
        assertEquals(13, marksStore.read().lastCelebratedLevel)
        assertTrue(marksStore.read().pendingLevelUps.isEmpty())
        assertTrue(repository.pendingEvents.first().isEmpty())
    }
}
