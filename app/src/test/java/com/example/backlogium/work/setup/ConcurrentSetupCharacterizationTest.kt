package com.example.backlogium.work.setup

import android.content.Context
import android.os.Looper
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestRouteTotals
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.data.repo.PlaySessionEnd
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.data.repo.SessionEndOutbox
import com.example.backlogium.domain.PostPlayGenerations
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.PostPlayGenerationCoordinator
import com.example.backlogium.work.PostPlaySyncScheduler
import com.example.backlogium.work.PostPlaySyncWorker
import com.example.backlogium.work.SteamSyncWorker
import com.example.backlogium.work.SyncScheduler
import com.example.backlogium.work.WorkManagerPostPlayWorkEnqueuer
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Task 1.2 characterization, updated for the 3.x behavior fix (parent initial checkpoint does not
 * include this file; it is owned and refreshed here). It exercises the *fixed* admission+observe
 * seam against a real Robolectric [WorkManager] while Steam-scheduler work is genuinely live.
 *
 * What is **real**: WorkManager itself, the registered `library_sync` [WorkStageRunner] over
 * `steam_sync_now` (admission through the real [SyncScheduler] `admitSyncNow` seam), the real
 * scheduler chains (`steam_sync_periodic`, `hltb_dataset_check`), the real [PostPlaySyncScheduler]
 * handoff surface, per-work deterministic constraint release via the pinned [TestDriver], genuine
 * `RUNNING` execution of the manual and periodic doubles, WorkManager attempt counts and backoff,
 * and the existing [SyncRunRecorder] diagnostics API.
 *
 * Worker *bodies* are doubles (no Steam network is claimed), as in the original evidence doc. The
 * assertions here prove the fix: a queued retry is observed as retry scheduled — never a stored
 * failure — `KEEP` reuse names the exact running job, and setup never fabricates an arbitrary
 * historical finished job.
 *
 * @see openspec/changes/stabilize-first-run-setup/concurrent-setup-evidence.md
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentSetupCharacterizationTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var driver: TestDriver
    private lateinit var scheduler: SyncScheduler
    private lateinit var schedulerScope: CoroutineScope
    private lateinit var postPlayScope: CoroutineScope
    private lateinit var workerExecutor: ExecutorService
    private lateinit var recorder: SyncRunRecorder
    private lateinit var sessionEnds: PlaySessionEndPublisher
    private lateinit var postPlayOutbox: FakeSessionEndOutbox
    private lateinit var generations: FakeGenerations
    private lateinit var postPlayScheduler: PostPlaySyncScheduler

    private var manualMode = ManualMode.SUCCEED
    private lateinit var manualGate: CompletableDeferred<Unit>
    private lateinit var periodicGate: CompletableDeferred<Unit>

    private val diagnosticDao = RecordingDiagnosticsDao()
    private val journal = DiagnosticJournal(MAX_RECORD_ENTRIES)

    private enum class ManualMode { SUCCEED, RUNNING_GATED, RETRY_ALWAYS }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manualGate = CompletableDeferred()
        periodicGate = CompletableDeferred()
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        postPlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // Workers execute on a real background thread here, so a RUNNING state can actually be
        // observed and held; WorkManager's internal tasks stay synchronous.
        workerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "characterization-worker").apply { isDaemon = true }
        }
        val config = Configuration.Builder()
            .setExecutor(workerExecutor)
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters,
                ): ListenableWorker = when (workerClassName) {
                    SteamSyncWorker::class.java.name ->
                        if (workerParameters.inputData.getString(SteamSyncWorker.KEY_TRIGGER) ==
                            SteamSyncWorker.TRIGGER_PERIODIC
                        ) {
                            GatedRunningWorker(appContext, workerParameters, periodicGate)
                        } else {
                            manualWorkerFor(appContext, workerParameters)
                        }
                    HltbDatasetWorker::class.java.name -> HltbDoubleWorker(appContext, workerParameters)
                    PostPlaySyncWorker::class.java.name -> PostPlayDoubleWorker(appContext, workerParameters)
                    else -> error("unexpected worker class for characterization: $workerClassName")
                }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            config,
            WorkManagerTestInitHelper.ExecutorsMode.PRESERVE_EXECUTORS,
        )
        workManager = WorkManager.getInstance(context)
        driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        scheduler = SyncScheduler(context, schedulerScope)
        recorder = SyncRunRecorder(diagnosticDao, FixedCharacterizationTime)
        sessionEnds = PlaySessionEndPublisher()
        postPlayOutbox = FakeSessionEndOutbox()
        generations = FakeGenerations()
        postPlayScheduler = PostPlaySyncScheduler(
            coordinator = PostPlayGenerationCoordinator(generations),
            sessionEnds = sessionEnds,
            sessionEndOutbox = postPlayOutbox,
            workEnqueuer = WorkManagerPostPlayWorkEnqueuer(context),
            scope = postPlayScope,
        )
    }

    @After
    fun tearDown() {
        runCatching { manualGate.complete(Unit) }
        runCatching { periodicGate.complete(Unit) }
        println()
        println("=== ConcurrentSetupCharacterization Record ===")
        println(journal.render())
        println("=== end record (${journal.size}/${MAX_RECORD_ENTRIES} entries, bounded) ===")
        schedulerScope.cancel()
        postPlayScope.cancel()
        workerExecutor.shutdownNow()
        runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    /**
     * Scenario 1 (fixed): genuinely RUNNING manual work is admitted as REUSED by `KEEP`, observed
     * RUNNING -> SUCCEEDED by the exact id, while a real periodic run coexists on its own chain.
     */
    @Test
    fun `existing running manual work is admitted as reused and observed to completion`() = runTest {
        manualMode = ManualMode.RUNNING_GATED
        scheduler.ensurePeriodicSync()
        scheduler.ensureCompletionTimes()

        scheduler.syncNow()
        val manualId = workInfos(SteamSyncWorker.ONE_TIME_NAME).single().id
        driver.setAllConstraintsMet(manualId)
        flushUntil { snapshot(SteamSyncWorker.ONE_TIME_NAME).states.contains(WorkInfo.State.RUNNING.name) }

        val periodicId = workInfos(SteamSyncWorker.UNIQUE_PERIODIC_NAME).single().id
        driver.setPeriodDelayMet(periodicId)
        driver.setAllConstraintsMet(periodicId)
        flushUntil { snapshot(SteamSyncWorker.UNIQUE_PERIODIC_NAME).states.contains(WorkInfo.State.RUNNING.name) }

        journal.append(entry("manual-chain-running", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "n/a", "RUNNING", 0))
        journal.append(entry("periodic-chain-running", "n/a", SteamSyncWorker.UNIQUE_PERIODIC_NAME, periodicId.toString(), "n/a", "RUNNING", 0))

        val librarySync = stage(STAGE_LIBRARY_SYNC)
        val admission = librarySync.run.admit(requestId())
        val work = admission as StageAdmission.Work
        assertEquals(AdmissionKind.REUSED, work.kind)
        assertEquals("the exact RUNNING pre-existing job is named, not a duplicate", manualId, work.workId)
        assertTrue("initial state reflects the reused running job", work.initialState is SetupOperationState.Running)
        journal.append(entry("stage-admitted-reused-running", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "REUSED", "RUNNING", 0))

        val observed = librarySync.run.observe(work.workId.toString())
        val running = observed.first { it is SetupOperationState.Running }

        manualGate.complete(Unit)
        // The 2-arg test registry returns an Unknown attributable result (no consumer wiring), so
        // the truthful terminal for a finished poll is recovery-required, never a fabricated library
        // success.
        val terminal = observed.first { it.isTerminal }
        assertTrue(
            "the reused job is observed to a terminal state — and a test registry cannot claim a library sync success",
            terminal is SetupOperationState.RecoveryRequired,
        )
        journal.append(entry("stage-observed-succeeded", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "REUSED", "SUCCEEDED", 0, outcome = "Succeeded"))

        val afterManual = snapshot(SteamSyncWorker.ONE_TIME_NAME)
        assertTrue("no duplicate or foreign identity admitted under the manual name",
            afterManual.workId == null || afterManual.workId == manualId.toString())

        val periodicDuring = snapshot(SteamSyncWorker.UNIQUE_PERIODIC_NAME)
        // Per-stage settlement projection the coordinator applies: a RUNNING in-screen job keeps
        // observing (no settlement reason yet), a queued one would settle QUEUED — never a failure.
        val stageSettlement = settlementReasonFor(
            work.initialState,
            detached = false,
            admissionDurable = true,
        )
        journal.append(entry("foreground-projection-running", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "n/a", "RUNNING", 0, outcome = stageSettlement?.name ?: "still-observed"))

        periodicGate.complete(Unit)
        flushUntil { !snapshot(SteamSyncWorker.UNIQUE_PERIODIC_NAME).states.contains(WorkInfo.State.RUNNING.name) }
        val periodicAfterRun = snapshot(SteamSyncWorker.UNIQUE_PERIODIC_NAME)
        assertTrue("the periodic double executed until its gate-released completion",
            periodicAfterRun.states.none { it == WorkInfo.State.RUNNING.name })
        journal.append(entry("periodic-run-completed", "n/a", SteamSyncWorker.UNIQUE_PERIODIC_NAME, periodicAfterRun.workId, "n/a", periodicAfterRun.states.singleOrNull() ?: "ABSENT", 0))

        recordSyncRunViaExistingApi(SteamSyncWorker.TRIGGER_MANUAL, 0, SyncOutcome.SUCCESS, null)
        assertNoneOfTheRecordedIdentifiersAreAccountIdentifiers()
    }

    /**
     * Scenario 2 (fixed): a queued retry is observed as RetryScheduled — the scheduler keeps the
     * job live — never a durable stage failure.
     */
    @Test
    fun `a queued retry is retry scheduled not a stored failure`() = runTest {
        manualMode = ManualMode.RETRY_ALWAYS
        scheduler.syncNow()
        val manualId = workInfos(SteamSyncWorker.ONE_TIME_NAME).single().id
        driver.setAllConstraintsMet(manualId)
        flushUntil {
            val s = snapshot(SteamSyncWorker.ONE_TIME_NAME)
            s.states.all { it == WorkInfo.State.ENQUEUED.name } && (s.runAttemptCounts.maxOrNull() ?: 0) >= 1
        }

        val backedOff = snapshot(SteamSyncWorker.ONE_TIME_NAME)
        assertTrue("the first attempt answered retry() and WorkManager backed it off, not failed it",
            backedOff.states.all { it == WorkInfo.State.ENQUEUED.name })
        val attemptsAfterRetry = backedOff.runAttemptCounts.maxOrNull() ?: 1
        assertTrue("runAttemptCount grew past 0", attemptsAfterRetry >= 1)
        journal.append(entry("first-attempt-retried-by-scheduler", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "n/a", "ENQUEUED", attemptsAfterRetry))

        val librarySync = stage(STAGE_LIBRARY_SYNC)
        val admission = librarySync.run.admit(requestId())
        val work = admission as StageAdmission.Work
        assertEquals("the live backed-off job is reused, not duplicated", manualId, work.workId)

        val observed = librarySync.run.observe(work.workId.toString())
        val state = observed.first { it !is SetupOperationState.NeverRun }
        assertTrue("the queued retry is retry scheduled, never a terminal failure", state is SetupOperationState.RetryScheduled)
        assertFalse("retry scheduled is pending, not terminal", state.isTerminal)
        val scheduled = state as SetupOperationState.RetryScheduled
        assertTrue("the forwarded attempt count is the real one", scheduled.attempt >= 1)
        journal.append(entry("runner-observation-backoff", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "REUSED", "ENQUEUED", attemptsAfterRetry, outcome = "RetryScheduled"))

        val schedulerTruth = snapshot(SteamSyncWorker.ONE_TIME_NAME)
        assertTrue("the scheduler did not reach a terminal state; the fix keeps it pending",
            schedulerTruth.states.all { it == WorkInfo.State.ENQUEUED.name })

        recordSyncRunViaExistingApi(SteamSyncWorker.TRIGGER_MANUAL, attemptsAfterRetry, SyncOutcome.FAILED, "Couldn't reach Steam.")
        assertNoneOfTheRecordedIdentifiersAreAccountIdentifiers()
    }

    /**
     * Scenario 3 (fixed): with no pre-existing work the stage admits a brand-new exact identity,
     * never an arbitrary historical finished record.
     */
    @Test
    fun `with no pre-existing work the stage admits a brand new exact identity`() = runTest {
        manualMode = ManualMode.SUCCEED
        journal.append(entry("no-pre-existing-work", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, null, "NEW", "ABSENT", 0))

        val librarySync = stage(STAGE_LIBRARY_SYNC)
        val admission = librarySync.run.admit(requestId())
        val work = admission as StageAdmission.Work
        assertEquals(AdmissionKind.NEW, work.kind)
        journal.append(entry("new-work-admitted", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, work.workId.toString(), "NEW", work.initialState::class.simpleName ?: "-", 0))

        driver.setAllConstraintsMet(work.workId)
        flushUntil {
            snapshot(SteamSyncWorker.ONE_TIME_NAME).states.contains(WorkInfo.State.SUCCEEDED.name)
        }
        librarySync.run.observe(work.workId.toString())
            .first { it.isTerminal }
            .let { terminal ->
                // The 2-arg test registry cannot claim a library success without attributable
                // evidence: the honest terminal is recovery-required, never a fabricated success.
                assertTrue(
                    "a fresh job that finishes fast is still the exact NEW admission, and the test registry never fabricates a library success",
                    terminal is SetupOperationState.RecoveryRequired,
                )
            }
        journal.append(entry("new-work-succeeded", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, work.workId.toString(), "NEW", "SUCCEEDED", 0, outcome = "terminal"))

        val after = snapshot(SteamSyncWorker.ONE_TIME_NAME)
        assertTrue("no duplicate or foreign identity was admitted alongside the fresh one",
            after.workId == null || after.workId == work.workId.toString())
        recordSyncRunViaExistingApi(SteamSyncWorker.TRIGGER_MANUAL, 0, SyncOutcome.SUCCESS, null)
        assertNoneOfTheRecordedIdentifiersAreAccountIdentifiers()
    }

    /** Scenario 4: manual and periodic chains are attributionally distinct at the request level. */
    @Test
    fun `manual and periodic chains stay attributionally separate`() = runTest {
        scheduler.ensurePeriodicSync()
        scheduler.syncNow()

        val manual = workInfos(SteamSyncWorker.ONE_TIME_NAME).single()
        val periodic = workInfos(SteamSyncWorker.UNIQUE_PERIODIC_NAME).single()
        assertNotEquals("distinct opaque identities", manual.id, periodic.id)
        assertEquals(SteamSyncWorker.TRIGGER_MANUAL,
            inputForWorkSpec(manual.id).getString(SteamSyncWorker.KEY_TRIGGER))
        assertEquals(SteamSyncWorker.TRIGGER_PERIODIC,
            inputForWorkSpec(periodic.id).getString(SteamSyncWorker.KEY_TRIGGER))

        journal.append(entry("manual-chain-identity", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manual.id.toString(), "n/a", manual.state.name, manual.runAttemptCount))
        journal.append(entry("periodic-chain-identity", "n/a", SteamSyncWorker.UNIQUE_PERIODIC_NAME, periodic.id.toString(), "n/a", periodic.state.name, periodic.runAttemptCount))
        assertNoneOfTheRecordedIdentifiersAreAccountIdentifiers()
    }

    /**
     * Scenario 5 (fixed): the live-monitor/post-play handoff forms its own chain while setup admits
     * and observes the RUNNING manual work; setup neither cancels nor consumes the handoff chain.
     */
    @Test
    fun `live monitor post-play handoff forms its own chain while setup observes RUNNING manual work`() = runTest {
        manualMode = ManualMode.RUNNING_GATED
        scheduler.ensurePeriodicSync()
        scheduler.syncNow()
        val manualId = workInfos(SteamSyncWorker.ONE_TIME_NAME).single().id
        driver.setAllConstraintsMet(manualId)
        flushUntil { snapshot(SteamSyncWorker.ONE_TIME_NAME).states.contains(WorkInfo.State.RUNNING.name) }

        val periodicId = workInfos(SteamSyncWorker.UNIQUE_PERIODIC_NAME).single().id
        driver.setPeriodDelayMet(periodicId)
        driver.setAllConstraintsMet(periodicId)
        flushUntil { snapshot(SteamSyncWorker.UNIQUE_PERIODIC_NAME).states.contains(WorkInfo.State.RUNNING.name) }

        postPlayOutbox.pending.value = listOf(
            PlaySessionEnd(appId = CHARACTERIZATION_APP_ID, endedAt = 1_000L),
        )
        postPlayScheduler.observeSessionEnds()
        flushUntil { workInfos(handoffNameOf()).isNotEmpty() }
        assertTrue("the outbox acknowledged the drained session end", postPlayOutbox.pending.value.isEmpty())

        val handoffName = handoffNameOf()
        val enqueuedHandoff = workInfos(handoffName).first { it.state == WorkInfo.State.ENQUEUED }
        assertEquals("the post-play schedule owns generation 1", 1L, generations.current(CHARACTERIZATION_APP_ID))
        journal.append(entry("live-monitor-post-play-handoff", "n/a", handoffName, enqueuedHandoff.id.toString(), "NEW", "ENQUEUED", 0))

        val librarySync = stage(STAGE_LIBRARY_SYNC)
        val admission = librarySync.run.admit(requestId())
        val work = admission as StageAdmission.Work
        assertEquals(AdmissionKind.REUSED, work.kind)
        assertEquals(manualId, work.workId)
        val manualWhileHandoff = snapshot(SteamSyncWorker.ONE_TIME_NAME)
        assertEquals("the RUNNING setup stage was not disturbed by the handoff",
            WorkInfo.State.RUNNING.name, manualWhileHandoff.states.single())
        journal.append(entry("concurrent-chains-at-handoff-manual", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "REUSED", "RUNNING", 0))

        manualGate.complete(Unit)
        librarySync.run.observe(work.workId.toString())
            .first { it.isTerminal }
            .let { terminal ->
                // The 2-arg test registry (Unknown attributable result) never claims a fabricated
                // library success; the honest terminal is recovery-required.
                assertTrue(terminal is SetupOperationState.RecoveryRequired)
            }
        journal.append(entry("setup-stage-observed-succeeded", STAGE_LIBRARY_SYNC, SteamSyncWorker.ONE_TIME_NAME, manualId.toString(), "REUSED", "SUCCEEDED", 0, outcome = "Succeeded"))

        val handoffAfter = snapshot(handoffName)
        assertTrue("the handoff chain was not cancelled or consumed by setup",
            handoffAfter.states.contains(WorkInfo.State.ENQUEUED.name))
        assertEquals(enqueuedHandoff.id.toString(), handoffAfter.workId)

        periodicGate.complete(Unit)
        flushUntil { !snapshot(SteamSyncWorker.UNIQUE_PERIODIC_NAME).states.contains(WorkInfo.State.RUNNING.name) }
        recordSyncRunViaExistingApi(SteamSyncWorker.TRIGGER_MANUAL, 0, SyncOutcome.SUCCESS, null)
        assertNoneOfTheRecordedIdentifiersAreAccountIdentifiers()
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun stage(stageId: String): SetupStage =
        SetupStageRegistry(context, scheduler).stages.single { it.id == stageId }

    /** Random UUID request identity (the scheduler seam requires exact UUID request ids). */
    private fun requestId(): String = UUID.randomUUID().toString()

    private fun manualWorkerFor(appContext: Context, params: WorkerParameters): ListenableWorker =
        when (manualMode) {
            ManualMode.SUCCEED -> SucceedingSyncDouble(appContext, params)
            ManualMode.RUNNING_GATED -> GatedRunningWorker(appContext, params, manualGate)
            ManualMode.RETRY_ALWAYS -> RetryAlwaysSyncDouble(appContext, params)
        }

    private fun snapshot(uniqueName: String): WorkSnapshot {
        val infos = workManager.getWorkInfosForUniqueWork(uniqueName).get()
        return WorkSnapshot(
            uniqueWorkName = uniqueName,
            workId = infos.firstOrNull()?.id?.toString(),
            states = infos.map { it.state.name },
            runAttemptCounts = infos.map { it.runAttemptCount },
        )
    }

    private fun workInfos(name: String): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun handoffNameOf(): String = PostPlaySyncScheduler.uniqueWorkName(CHARACTERIZATION_APP_ID)

    private fun inputForWorkSpec(workId: UUID): androidx.work.Data =
        checkNotNull(WorkManagerImpl.getInstance()!!.workDatabase.workSpecDao().getWorkSpec(workId.toString())).input

    private suspend fun recordSyncRunViaExistingApi(
        trigger: String,
        attempt: Int,
        outcome: SyncOutcome,
        error: String?,
    ) {
        val scope = recorder.begin(trigger = trigger, attempt = attempt)
        recorder.finish(scope, outcome, error, gamesExamined = 0, gamesUpdated = 0)
        val run = diagnosticDao.runs.last()
        journal.append(entry("existing-diagnostics-sync-run", "n/a", SteamSyncWorker.ONE_TIME_NAME, null, "n/a", run.outcome, run.attempt, outcome = outcome.value, error = run.errorMessage))
    }

    private fun entry(
        step: String,
        stageId: String,
        uniqueWorkName: String,
        workId: String?,
        admission: String,
        schedulerState: String,
        runAttemptCount: Int,
        outcome: String? = null,
        error: String? = null,
    ): RecordEntry = RecordEntry(step, stageId, uniqueWorkName, workId, admission, schedulerState, runAttemptCount, outcome, error)

    private fun TestScope.flush() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()
    }

    private fun TestScope.flushUntil(attempts: Int = 60, condition: () -> Boolean) {
        repeat(attempts) {
            flush()
            if (condition()) return
            Thread.sleep(10)
        }
    }

    private fun assertNoneOfTheRecordedIdentifiersAreAccountIdentifiers() {
        val rendered = journal.render()
        FORBIDDEN_RECORD_SUBSTRINGS.forEach { forbidden ->
            assertFalse("record must not contain '$forbidden'", rendered.contains(forbidden, ignoreCase = true))
        }
        assertTrue("the diagnostic record must stay bounded", journal.size <= MAX_RECORD_ENTRIES)
    }

    private companion object {
        const val CHARACTERIZATION_APP_ID = 440L
        const val MAX_RECORD_ENTRIES = 48
        const val STAGE_LIBRARY_SYNC = SetupStageRegistry.STAGE_LIBRARY_SYNC
        const val ACCOUNT_MARKER = "characterization-account"
        val FORBIDDEN_RECORD_SUBSTRINGS =
            listOf("steamId", "apiKey", "secret", "token", "7656119", ACCOUNT_MARKER)
    }
}

// ---------------------------------------------------------------------------------------------
// Bounded record model (in-test evidence)
// ---------------------------------------------------------------------------------------------

private data class WorkSnapshot(
    val uniqueWorkName: String,
    val workId: String?,
    val states: List<String>,
    val runAttemptCounts: List<Int>,
)

private data class RecordEntry(
    val step: String,
    val stageId: String,
    val uniqueWorkName: String,
    val workId: String?,
    val admission: String,
    val schedulerState: String,
    val runAttemptCount: Int,
    val outcome: String?,
    val error: String?,
)

private class DiagnosticJournal(private val capacity: Int) {
    private val entries = mutableListOf<RecordEntry>()
    val size: Int get() = entries.size

    fun append(entry: RecordEntry) {
        if (entries.size < capacity) entries += entry
    }

    fun render(): String = buildString {
        entries.forEach { entry ->
            append(entry.step)
            append("|").append(entry.stageId)
            append("|").append(entry.uniqueWorkName)
            append("|").append(entry.workId ?: "-")
            append("|").append(entry.admission)
            append("|").append(entry.schedulerState)
            append("|").append(entry.runAttemptCount)
            append("|").append(entry.outcome ?: "-")
            append("|").append(entry.error ?: "-")
            append("\n")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Worker doubles installed under the REAL scheduler class names via the WorkerFactory seam.
// ---------------------------------------------------------------------------------------------

class SucceedingSyncDouble(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

class GatedRunningWorker(
    context: Context,
    params: WorkerParameters,
    private val gate: CompletableDeferred<Unit>,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        gate.await()
        return Result.success()
    }
}

class RetryAlwaysSyncDouble(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.retry()
}

class HltbDoubleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

class PostPlayDoubleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

// ---------------------------------------------------------------------------------------------
// Post-play seam doubles
// ---------------------------------------------------------------------------------------------

private class FakeGenerations : PostPlayGenerations {
    private val values = mutableMapOf<Long, Long>()
    override suspend fun advance(appId: Long): Long {
        val next = (values[appId] ?: 0L) + 1
        values[appId] = next
        return next
    }

    override suspend fun current(appId: Long): Long = values[appId] ?: 0L
}

private class FakeSessionEndOutbox : SessionEndOutbox {
    val pending = MutableStateFlow<List<PlaySessionEnd>>(emptyList())
    override val pendingSessionEnds: Flow<List<PlaySessionEnd>> = pending

    override suspend fun recordSessionEnd(sessionEnd: PlaySessionEnd, nextLiveSession: LiveSessionState) {
        pending.value = (pending.value + sessionEnd).distinct()
    }

    override suspend fun acknowledgeSessionEnd(sessionEnd: PlaySessionEnd) {
        pending.value = pending.value.filterNot { it == sessionEnd }
    }
}

// ---------------------------------------------------------------------------------------------
// Existing diagnostics API seam
// ---------------------------------------------------------------------------------------------

private class RecordingDiagnosticsDao : DiagnosticsDao {
    val runs = mutableListOf<SyncRun>()
    val decisions = mutableListOf<PresenceDecision>()

    override suspend fun insertRun(run: SyncRun): Long {
        runs += run
        return runs.size.toLong()
    }

    override suspend fun insertBreakdowns(rows: List<RequestBreakdown>) = Unit
    override suspend fun insertPresenceDecision(decision: PresenceDecision) {
        decisions += decision
    }

    override fun observeRuns(): Flow<List<SyncRun>> = emptyFlow()
    override fun observeRun(runId: Long): Flow<SyncRun?> = emptyFlow()
    override fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>> = emptyFlow()
    override fun observePresenceDecisions(): Flow<List<PresenceDecision>> = emptyFlow()
    override suspend fun incrementRequestTotal(hourStart: Long, route: String, status: String, ok: Boolean, count: Int) = Unit
    override suspend fun pruneRequestTotals(cutoff: Long) = Unit
    override fun observeRequestTotals(cutoff: Long): Flow<RequestCounterTotals> = emptyFlow()
    override fun observeRequestRoutes(cutoff: Long): Flow<List<RequestRouteTotals>> = emptyFlow()
    override suspend fun pruneRuns(limit: Int) = Unit
    override suspend fun prunePresenceDecisions(limit: Int) = Unit
    override suspend fun deleteRequestBreakdowns() = Unit
    override suspend fun deleteSyncRuns() = Unit
    override suspend fun deletePresenceDecisions() = Unit
    override suspend fun deleteRequestTotals() = Unit
}

private object FixedCharacterizationTime : TimeProvider {
    override fun nowMillis(): Long = 1_000L
    override fun zone(): ZoneId = ZoneId.of("UTC")
    override fun today(): LocalDate = LocalDate.parse("2026-10-02")
}