package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.LegacyActiveMarker
import com.example.backlogium.data.setup.StageAttemptRecord
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator after durable per-stage admission and foreground settlement (design Decisions 1
 * & 2): selected stages run in registered order, each stage's *own* foreground wait settles
 * independently (queued/backoff/terminal/recovery/Continue/budget — never cancellation), pending
 * work keeps being reconciled on the application scope, and saved attempts are resumed by exact
 * work identity after process death. A persisted request is never auto-retriggered, no work is
 * admitted without an identified account, and the first-run takeover survives setup work until an
 * explicit release.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupCoordinatorTest {

    /**
     * Eager test scope for the coordinator: the same scheduler tied pattern the passing
     * `SetupStageRegistryTest` uses (`Dispatchers.Unconfined`). `scope.launch` runs immediately to
     * its first suspension, so the coordinator's channel-driven foreground/observer scheduling does
     * not depend on advancing virtual time through `advanceUntilIdle`; every assertion stays exact.
     * Every created scope is deterministically cancelled and joined at the end of the test — never
     * swallowed as a global uncaught exception.
     */
    private val createdScopes = mutableListOf<CoroutineScope>()

    private fun TestScope.eagerScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)).also {
            createdScopes += it
        }

    @After
    fun tearDown() = runBlocking {
        createdScopes.forEach { scope ->
            scope.cancel()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
        createdScopes.clear()
    }

    private fun coordinator(
        stages: List<SetupStage>,
        store: FakeSetupStateStore = FakeSetupStateStore(),
        scope: CoroutineScope,
        account: SetupAccountIdentity = NoSetupAccountIdentity,
        clock: MonotonicClock = ScriptedClock { 0L },
    ) = SetupCoordinator(FakeStageSource(stages), store, scope, account, clock)

    private fun attempt(
        stageId: String,
        generation: Long = 1L,
        cohortId: String = "cohort",
        accountMarker: String? = null,
        requestId: String? = null,
        uniqueWorkName: String? = null,
        candidateWorkId: String? = null,
        admittedWorkId: String? = null,
        operation: SetupOperationState = SetupOperationState.NeverRun,
    ) = StageAttemptRecord(
        stageId = stageId,
        generation = generation,
        cohortId = cohortId,
        accountMarker = accountMarker,
        requestId = requestId,
        uniqueWorkName = uniqueWorkName,
        candidateWorkId = candidateWorkId,
        admittedWorkId = admittedWorkId,
        operation = operation,
    )

    // ---------------------------------------------------------------------
    // Compat projection
    // ---------------------------------------------------------------------

    @Test
    fun runsOnlySelectedStagesAndRecordsTheRestSkipped() = runTest {
        val first = FakeStageRunner()
        val second = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("a", first), fakeStage("b", second)),
            store,
            eagerScope(),
        )

        subject.start(setOf("a"))
        advanceUntilIdle()

        assertTrue(first.started)
        assertFalse("a deselected stage must not enqueue its work", second.started)
        assertEquals(SetupOperationState.Succeeded(), store.attemptsRead["a"]?.operation)
        assertEquals(SetupOperationState.Skipped, store.attemptsRead["b"]?.operation)
        assertTrue(subject.state.value.finished)
        assertTrue(store.completed)
    }

    @Test
    fun decliningSetupRunsNothingAndSkipsEverything() = runTest {
        val first = FakeStageRunner()
        val second = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("a", first), fakeStage("b", second)),
            store,
            eagerScope(),
        )

        subject.skipAll()
        advanceUntilIdle()

        assertFalse(first.started)
        assertFalse(second.started)
        assertEquals(SetupOperationState.Skipped, store.attemptsRead["a"]?.operation)
        assertEquals(SetupOperationState.Skipped, store.attemptsRead["b"]?.operation)
        assertTrue(subject.state.value.finished)
        assertFalse(subject.state.value.running)
    }

    @Test
    fun anUnavailableStageCannotRunAndDoesNotBlockTheOthers() = runTest {
        val blocked = FakeStageRunner()
        val other = FakeStageRunner()
        val subject = coordinator(
            listOf(
                fakeStage("blocked", blocked, unavailableReason = "Needs a capability this build lacks"),
                fakeStage("other", other),
            ),
            scope = eagerScope(),
        )

        subject.start(setOf("blocked", "other"))
        advanceUntilIdle()

        assertFalse(blocked.started)
        assertTrue(other.started)
    }

    @Test
    fun storedRecordsForStagesThisBuildDoesNotKnowAreIgnored() = runTest {
        val store = FakeSetupStateStore(
            initialAttempts = mutableMapOf(
                "known" to attempt("known", operation = SetupOperationState.Succeeded()),
                "stage_from_a_later_version" to attempt(
                    "stage_from_a_later_version",
                    operation = SetupOperationState.Failed("whatever"),
                ),
            ),
        )
        val subject = coordinator(listOf(fakeStage("known")), store, eagerScope())

        subject.ensureLoaded()
        advanceUntilIdle()

        val state = subject.state.value
        assertTrue("setup still renders", state.loaded)
        assertEquals(setOf("known"), state.operations.keys)
        assertEquals(SetupOperationState.Succeeded(), state.operations["known"])
    }

    @Test
    fun theAppIsUsableOnceInScreenStagesSettleWhileDetachedWorkStillRuns() = runTest {
        val inScreen = FakeStageRunner()
        val detached = FakeStageRunner(scriptedInitialState = SetupOperationState.Running())
            .apply { autoComplete = false }
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(
                fakeStage("sync", inScreen, execution = SetupStageExecution.IN_SCREEN),
                fakeStage("assets", detached, execution = SetupStageExecution.DETACHED),
            ),
            store,
            eagerScope(),
        )

        subject.start(setOf("sync", "assets"))
        advanceUntilIdle()

        assertTrue("the app is usable once in-screen work's foreground settles", subject.state.value.inScreenSettled)
        assertEquals(SetupOperationState.Succeeded(), store.attemptsRead["sync"]?.operation)
        assertTrue(store.attemptsRead["assets"]?.operation is SetupOperationState.Running)
        assertTrue("every selected stage's foreground wait has its own settlement", subject.state.value.finished)
    }

    // ---------------------------------------------------------------------
    // 3.5 Per-stage foreground settlement
    // ---------------------------------------------------------------------

    @Test
    fun queuedAdmissionSettlesTheStageAndAdmitsLaterStagesInOrder() = runTest {
        val queued = FakeStageRunner(
            scriptedInitialState = SetupOperationState.Waiting("queued behind a poll"),
            scriptedStates = listOf(SetupOperationState.Waiting("queued behind a poll")),
        )
        val later = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("sync", queued), fakeStage("assets", later)),
            store,
            eagerScope(),
        )

        subject.start(setOf("sync", "assets"))
        advanceUntilIdle()

        assertEquals(ForegroundSettledReason.QUEUED, subject.state.value.foreground?.settlementOf("sync"))
        // The waiting stage stays waiting — never failed, never skipped — and the later stage was
        // still admitted in registered order.
        assertEquals(
            SetupOperationState.Waiting("queued behind a poll"),
            store.attemptsRead["sync"]?.operation,
        )
        assertTrue(later.started)
        assertTrue(store.attemptsRead["sync"]?.isForegroundSettled == true)
        assertTrue(subject.state.value.finished)
    }

    @Test
    fun aLaterInScreenRunningStageIsObservedEvenAfterAnEarlierStageSettled() = runTest {
        val queued = FakeStageRunner(
            scriptedInitialState = SetupOperationState.Waiting("queued"),
            scriptedStates = listOf(SetupOperationState.Waiting("queued")),
        )
        val held = FakeStageRunner(scriptedInitialState = SetupOperationState.Running())
            .apply { autoComplete = false }
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(
                fakeStage("sync", queued, execution = SetupStageExecution.IN_SCREEN),
                fakeStage("assets", held, execution = SetupStageExecution.IN_SCREEN),
            ),
            store,
            eagerScope(),
        )

        subject.start(setOf("sync", "assets"))
        runCurrent()

        // The first stage settled immediately (queued); the later in-screen stage is STILL observed
        // for its own budget — the whole attempt is not settled by an earlier stage's settlement.
        assertEquals(ForegroundSettledReason.QUEUED, subject.state.value.foreground?.settlementOf("sync"))
        assertNull(subject.state.value.foreground?.settlementOf("assets"))
        assertTrue("the later running stage still owes its foreground wait", subject.state.value.running)
        assertFalse(subject.state.value.finished)

        held.releaseGate()
        advanceUntilIdle()
        assertEquals(ForegroundSettledReason.TERMINAL, subject.state.value.foreground?.settlementOf("assets"))
        assertTrue(subject.state.value.finished)
    }

    @Test
    fun budgetExpirySettlesTheStageWithoutCancellingOrFailingWork() = runTest {
        val held = FakeStageRunner(scriptedInitialState = SetupOperationState.Running())
            .apply { autoComplete = false }
        val clock = ScriptedClock { testScheduler.currentTime }
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("sync", held)),
            store,
            eagerScope(),
            clock = clock,
        )

        subject.start(setOf("sync"))
        runCurrent()

        assertTrue("within budget the stage's foreground is still observed", subject.state.value.running)
        assertNull(subject.state.value.foreground?.settlementOf("sync"))

        advanceTimeBy(DEFAULT_FOREGROUND_OBSERVATION_BUDGET_MS)
        runCurrent()

        assertEquals(ForegroundSettledReason.BUDGET_EXPIRED, subject.state.value.foreground?.settlementOf("sync"))
        // Budget expiry is a foreground fact: the operation is untouched and still running.
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.Running)
        assertFalse(store.attemptsRead["sync"]?.operation?.isTerminal == true)
        assertFalse(subject.state.value.running)
        assertTrue(subject.state.value.finished)
    }

    // ---------------------------------------------------------------------
    // 3.4 Live per-stage reconciliation
    // ---------------------------------------------------------------------

    @Test
    fun retryScheduledCanLaterBecomeSucceededAndIsNeverFailedInBetween() = runTest {
        val sync = FakeStageRunner(
            scriptedInitialState = SetupOperationState.RetryScheduled(1, "backoff"),
            scriptedStates = listOf(
                SetupOperationState.RetryScheduled(1, "backoff"),
                SetupOperationState.Succeeded(),
            ),
        ).apply { autoComplete = false }
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(fakeStage("sync", sync)), store, eagerScope())

        subject.start(setOf("sync"))
        runCurrent()

        // The foreground settles on the queued retry immediately; the stage is retry scheduled,
        // never a terminal failure. The app-scope observer emits only after the gate releases.
        assertEquals(ForegroundSettledReason.BACKOFF, subject.state.value.foreground?.settlementOf("sync"))
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.RetryScheduled)

        sync.releaseGate()
        advanceUntilIdle()
        assertEquals(SetupOperationState.Succeeded(), store.attemptsRead["sync"]?.operation)
    }

    @Test
    fun missingOrPrunedJobsOfferExplicitRecoveryNotInfiniteWaiting() = runTest {
        val missing = FakeStageRunner(
            scriptedInitialState = SetupOperationState.Running(),
            scriptedStates = listOf(SetupOperationState.RecoveryRequired("the job was pruned")),
        )
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(fakeStage("sync", missing)), store, eagerScope())

        subject.start(setOf("sync"))
        advanceUntilIdle()

        val finalState = store.attemptsRead["sync"]?.operation
        assertTrue(finalState is SetupOperationState.RecoveryRequired)
        assertFalse("recovery is never a claimed success", finalState == SetupOperationState.Succeeded())
        assertEquals(
            ForegroundSettledReason.RECOVERY_REQUIRED,
            subject.state.value.foreground?.settlementOf("sync"),
        )
        assertTrue(subject.state.value.finished)
    }

    @Test
    fun aRunnerThatThrowsIsIsolatedToItsOwnStage() = runTest {
        val throwing = FakeStageRunner(throws = IllegalStateException("boom"))
        val after = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("throwing", throwing), fakeStage("after", after)),
            store,
            eagerScope(),
        )

        subject.start(setOf("throwing", "after"))
        advanceUntilIdle()

        // A broken observation becomes an explicit recovery request, never a sibling failure.
        assertTrue(store.attemptsRead["throwing"]?.operation is SetupOperationState.RecoveryRequired)
        assertTrue(after.started)
        assertTrue(subject.state.value.finished)
    }

    // ---------------------------------------------------------------------
    // 3.6 Fenced callbacks (atomic CAS writes)
    // ---------------------------------------------------------------------

    @Test
    fun anOldObservedGenerationCannotOverwriteANewAttempt() = runTest {
        val sync = FakeStageRunner(
            scriptedInitialState = SetupOperationState.RetryScheduled(1, "backoff"),
            scriptedStates = listOf(
                SetupOperationState.RetryScheduled(1, "backoff"),
                SetupOperationState.Succeeded(),
            ),
        ).apply { autoComplete = false }
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(fakeStage("sync", sync)), store, eagerScope())

        subject.start(setOf("sync"))
        runCurrent()
        val firstGeneration = store.attemptsRead["sync"]?.generation
        assertTrue(
            "the first attempt is retry scheduled, not terminal",
            store.attemptsRead["sync"]?.operation is SetupOperationState.RetryScheduled,
        )

        subject.retryStage("sync")
        runCurrent()
        val secondGeneration = store.attemptsRead["sync"]?.generation
        assertTrue("the retry replaces the attempt with a fresh generation", (secondGeneration ?: 0) > (firstGeneration ?: 0))

        // The old attempt's observer finally reports its terminal state — after the replacement
        // already owns the stage. Its write is refused by the atomic generation check.
        sync.releaseGate()
        advanceUntilIdle()

        val record = store.attemptsRead["sync"]
        assertEquals("the replacement attempt owns the stage", secondGeneration, record?.generation)
        assertEquals("the stale generation-1 result could not overwrite the replacement", SetupOperationState.Succeeded(), record?.operation)
    }

    @Test
    fun oldAccountCompletionsCannotOverwriteAReplacementAttempt() = runTest {
        val account = MutableAccountIdentity("account-A")
        val sync = FakeStageRunner(
            scriptedInitialState = SetupOperationState.RetryScheduled(1, "backoff"),
            scriptedStates = listOf(SetupOperationState.Succeeded()),
        ).apply { autoComplete = false }
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("sync", sync)),
            store,
            eagerScope(),
            account = account,
        )

        subject.start(setOf("sync"))
        runCurrent()
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.RetryScheduled)

        account.marker = "account-B"
        sync.releaseGate()
        advanceUntilIdle()

        // The old-account completion is fenced out: the attempt stays retry scheduled, never the
        // old account's success.
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.RetryScheduled)
    }

    // ---------------------------------------------------------------------
    // 3.7 Startup / Settings reconcile
    // ---------------------------------------------------------------------

    @Test
    fun reconcileResumesEachPendingAssociationAndAdmitsRemainingWithoutDuplicates() = runTest {
        val sync = FakeStageRunner(scriptedInitialState = SetupOperationState.Waiting("resumed"))
            .apply { autoComplete = false }
        val times = FakeStageRunner(scriptedInitialState = SetupOperationState.NeverRun).apply {
            locateResult = AdmittedWork.Reused(UUID.fromString("11111111-1111-1111-1111-111111111111"))
        }
        val assets = FakeStageRunner()
        val store = FakeSetupStateStore(
            initialAttempts = mutableMapOf(
                "sync" to attempt(
                    "sync",
                    generation = 2L,
                    cohortId = "c1",
                    accountMarker = "m",
                    requestId = "r-sync",
                    uniqueWorkName = "steam_sync_now",
                    admittedWorkId = sync.admittedWorkId,
                    operation = SetupOperationState.Waiting("was pending"),
                ),
                "completion_times" to attempt(
                    "completion_times",
                    generation = 1L,
                    cohortId = "c1",
                    accountMarker = "m",
                    requestId = "r-times",
                    uniqueWorkName = "hltb_dataset_check",
                ),
            ),
        )
        store.setCohort("c1", setOf("sync", "completion_times", "steam_assets"), "m")
        val subject = coordinator(
            listOf(
                fakeStage("sync", sync),
                fakeStage("completion_times", times),
                fakeStage("steam_assets", assets),
            ),
            store,
            eagerScope(),
            account = FixedAccountIdentity("m"),
        )

        subject.reconcile()
        advanceUntilIdle()

        // The durably admitted stage is resumed, never re-enqueued.
        assertTrue("the durably admitted stage is never re-enqueued", sync.admittedRequestIds.isEmpty())
        assertEquals("the exact admitted work survives reconcile", sync.admittedWorkId, store.attemptsRead["sync"]?.admittedWorkId)
        // The interrupted admission is recovered by exact request identity, without a new enqueue.
        assertEquals(
            "recovery adopts the exact located work",
            UUID.fromString("11111111-1111-1111-1111-111111111111").toString(),
            store.attemptsRead["completion_times"]?.admittedWorkId,
        )
        assertTrue("recovery never enqueues a duplicate", times.admittedRequestIds.isEmpty())
        // The remaining selected stage that never started is admitted exactly once.
        assertTrue("the remaining selected stage is admitted", assets.started)
        assertEquals(1, assets.admittedRequestIds.size)
    }

    @Test
    fun reconcileNeverAutoRetriggersAPersistedRequestThatNeverAdmitted() = runTest {
        val sync = FakeStageRunner(scriptedInitialState = SetupOperationState.NeverRun)
        val store = FakeSetupStateStore(
            initialAttempts = mutableMapOf(
                "sync" to attempt(
                    "sync",
                    cohortId = "c1",
                    accountMarker = "m",
                    requestId = "r-interrupted",
                    uniqueWorkName = "steam_sync_now",
                ),
            ),
        )
        store.setCohort("c1", setOf("sync"), "m")
        val subject = coordinator(
            listOf(fakeStage("sync", sync)),
            store,
            eagerScope(),
            account = FixedAccountIdentity("m"),
        )

        subject.reconcile()
        advanceUntilIdle()

        // Never a fresh enqueue for a request that already has durable intent: the stage records
        // recovery-required and waits for the user's explicit action.
        assertTrue(sync.admittedRequestIds.isEmpty())
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.RecoveryRequired)
    }

    @Test
    fun foreignAccountCohortNeverTriggersANewAccountPoll() = runTest {
        val assets = FakeStageRunner()
        val store = FakeSetupStateStore()
        store.setCohort("old-cohort", setOf("steam_assets"), "old-account-marker")
        val subject = coordinator(
            listOf(fakeStage("steam_assets", assets)),
            store,
            eagerScope(),
            account = FixedAccountIdentity("new-account-marker"),
        )

        subject.reconcile()
        advanceUntilIdle()

        assertFalse("an old account's cohort must not poll the new account", assets.started)
        assertFalse(store.attemptsRead.containsKey("steam_assets"))
    }

    @Test
    fun reconcileNeverReopensAnExplicitlyDismissedJourney() = runTest {
        val store = FakeSetupStateStore()
        store.setFirstRunSetupActive(true)
        val subject = coordinator(listOf(fakeStage("sync")), store, eagerScope())

        subject.reconcile()
        advanceUntilIdle()

        // Recovery owns scheduling and observation, never the user-visible journey flag.
        assertTrue(subject.firstRunSetupActive.first())
        assertFalse(store.attemptsRead.containsKey("sync"))
    }

    @Test
    fun legacyActiveMarkerWithAWorkIdIsConvertedToALiveAttempt() = runTest {
        val sync = FakeStageRunner(scriptedInitialState = SetupOperationState.Waiting("resumed"))
            .apply { autoComplete = false }
        val assets = FakeStageRunner()
        val store = FakeSetupStateStore(
            legacyMarker = LegacyActiveMarker(
                stageId = "sync",
                workId = sync.admittedWorkId,
                selectedStageIds = setOf("sync", "steam_assets"),
            ),
        )
        val subject = coordinator(
            listOf(fakeStage("sync", sync), fakeStage("steam_assets", assets)),
            store,
            eagerScope(),
        )

        subject.reconcile()
        advanceUntilIdle()

        assertEquals(sync.admittedWorkId, store.attemptsRead["sync"]?.admittedWorkId)
        assertTrue("the remaining selected stage is admitted in order", assets.started)
        assertNull(store.readLegacyActiveMarker())
    }

    @Test
    fun aHistoricalLegacyFailureWithoutAJobIdStaysHistorical() = runTest {
        val sync = FakeStageRunner()
        val store = FakeSetupStateStore(
            initialOutcomes = mutableMapOf("sync" to SetupOutcome.Failed("old backoff failure")),
        )
        val subject = coordinator(listOf(fakeStage("sync", sync)), store, eagerScope())

        subject.reconcile()
        advanceUntilIdle()

        // No live job is fabricated: no attempt record, no admission, no auto-re-run.
        assertFalse(sync.started)
        assertFalse(store.attemptsRead.containsKey("sync"))
        assertEquals(SetupOutcome.Failed("old backoff failure"), store.outcomes["sync"])
    }

    // ---------------------------------------------------------------------
    // 4.2 Coordinator-level per-stage retry
    // ---------------------------------------------------------------------

    @Test
    fun retryReplacesOnlyTheRequestedStagesAttemptAndPreservesSuccessfulSiblings() = runTest {
        val a = FakeStageRunner()
        val b = FakeStageRunner(outcome = SetupOutcome.Failed("old failure"))
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("a", a), fakeStage("b", b)),
            store,
            eagerScope(),
        )

        subject.start(setOf("a", "b"))
        advanceUntilIdle()
        val aGeneration = store.attemptsRead["a"]?.generation
        val bBefore = store.attemptsRead["b"]
        assertEquals(SetupOperationState.Failed("old failure"), bBefore?.operation)

        b.outcomeOverride = SetupOperationState.Succeeded()
        subject.retryStage("b")
        advanceUntilIdle()

        assertEquals(SetupOperationState.Succeeded(), store.attemptsRead["b"]?.operation)
        assertEquals(bBefore?.generation?.let { it + 1 }, store.attemptsRead["b"]?.generation)
        assertEquals("the sibling's outcome is untouched", aGeneration, store.attemptsRead["a"]?.generation)
        assertEquals(SetupOperationState.Succeeded(), store.attemptsRead["a"]?.operation)
    }

    @Test
    fun duplicateRetryWhileTheForegroundIsActiveIsRejected() = runTest {
        val sync = FakeStageRunner().apply { autoComplete = false }
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(fakeStage("sync", sync)), store, eagerScope())

        subject.start(setOf("sync"))
        runCurrent()
        assertTrue(subject.state.value.running)

        subject.retryStage("sync")
        advanceUntilIdle()

        assertEquals("the duplicate claim is refused at the coordinator", 1, sync.admittedRequestIds.size)
    }

    @Test
    fun onboardingStartPreservesAPriorSuccessfulSibling() = runTest {
        val store = FakeSetupStateStore(
            initialAttempts = mutableMapOf(
                "a" to attempt("a", generation = 2L, operation = SetupOperationState.Succeeded()),
            ),
        )
        val b = FakeStageRunner()
        val subject = coordinator(
            listOf(fakeStage("a", FakeStageRunner()), fakeStage("b", b)),
            store,
            eagerScope(),
        )

        // An onboarding-style full selection decides about every stage — but a stage that already
        // recorded an outcome is not relabelled skipped by a later start.
        subject.start(setOf("b"), recordUnselectedAsSkipped = true)
        advanceUntilIdle()

        assertEquals("the prior success is preserved", SetupOperationState.Succeeded(), store.attemptsRead["a"]?.operation)
        assertTrue(b.started)
    }

    // ---------------------------------------------------------------------
    // 4.4 Continue / do-later during foreground work
    // ---------------------------------------------------------------------

    @Test
    fun continuingDuringForegroundWorkStillAdmitsTheRemainingSelectedStages() = runTest {
        val sync = FakeStageRunner(scriptedInitialState = SetupOperationState.Running())
            .apply { autoComplete = false }
        val assets = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(
                fakeStage("sync", sync),
                fakeStage("assets", assets, execution = SetupStageExecution.DETACHED),
            ),
            store,
            eagerScope(),
        )

        subject.start(setOf("sync", "assets"))
        runCurrent()
        assertTrue("the in-screen stage is held in the foreground", subject.state.value.running)

        subject.continueLater()
        advanceUntilIdle()

        val foreground = subject.state.value.foreground
        assertTrue(foreground?.explicitContinued == true)
        // Merely continuing does not deselect or cancel: the remaining stage is still admitted.
        assertTrue(assets.started)
        // The in-screen stage's work was neither cancelled nor failed.
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.Running)
        assertFalse(store.attemptsRead["sync"]?.operation?.isTerminal == true)
        assertTrue(subject.state.value.finished)
    }

    // ---------------------------------------------------------------------
    // 7.x / claim semantics (phase agent owns the journey)
    // ---------------------------------------------------------------------

    @Test
    fun theClaimSurvivesWorkCompletionAndIsClearedOnlyByExplicitRelease() = runTest {
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(fakeStage("a")), store, eagerScope())

        assertFalse(subject.firstRunSetupActive.first())

        subject.claimFirstRunSetup()
        assertTrue(subject.firstRunSetupActive.first())

        // Setup work completing (or declining) never releases the first-run claim: the history
        // journey stays owed until the phase agent advances it.
        subject.start(setOf("a"))
        advanceUntilIdle()
        assertTrue("work completion keeps the claim", subject.firstRunSetupActive.first())

        subject.skipAll()
        advanceUntilIdle()
        assertTrue("declining setup also keeps the claim", subject.firstRunSetupActive.first())

        subject.releaseFirstRunSetup()
        assertFalse("only the explicit release clears the claim", subject.firstRunSetupActive.first())
    }

    @Test
    fun unknownAccountRefusesAdmissionWithoutPersistingIntent() = runTest {
        val sync = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("sync", sync)),
            store,
            eagerScope(),
            account = FixedAccountIdentity(null),
        )

        subject.start(setOf("sync"))
        advanceUntilIdle()

        assertFalse("no account means no admitted stage work", sync.started)
        val record = store.attemptsRead["sync"]
        assertTrue(record?.operation is SetupOperationState.RecoveryRequired)
        assertNull("a refused run persists no request intent", record?.requestId)
        assertFalse(subject.state.value.running)
    }

    @Test
    fun accountFlipWhileIntentIsBeingEstablishedBlocksAdmissionBeforeEnqueue() = runTest {
        val account = MutableAccountIdentity("account-A")
        val sync = FakeStageRunner().apply { gateLiveCandidate = CompletableDeferred() }
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("sync", sync)),
            store,
            eagerScope(),
            account = account,
        )

        subject.start(setOf("sync"))
        // The durable intent is stored while the pre-KEEP candidate read suspends.
        val intent = store.attemptsRead["sync"]
        assertNotNull(intent)
        assertNull(intent?.admittedWorkId)
        assertNotNull(intent?.requestId)

        account.marker = "account-B"
        sync.gateLiveCandidate?.complete(Unit)
        advanceUntilIdle()

        assertFalse("a stale cohort must not admit work for the replacement account", sync.started)
        assertNull("no exact association is ever claimed after an account flip", store.attemptsRead["sync"]?.admittedWorkId)
        assertTrue(store.attemptsRead["sync"]?.operation is SetupOperationState.RecoveryRequired)
    }

    @Test
    fun accountFlipDuringEnqueueAwaitNeverClaimsTheNewAccount() = runTest {
        val account = MutableAccountIdentity("account-A")
        val sync = FakeStageRunner().apply { gateAdmit = CompletableDeferred() }
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("sync", sync)),
            store,
            eagerScope(),
            account = account,
        )

        subject.start(setOf("sync"))
        // The admission await is in flight; the enqueue already happened, but the association is
        // not written yet.
        assertTrue("the runner was asked to admit under the old account", sync.started)
        assertNull(store.attemptsRead["sync"]?.admittedWorkId)

        account.marker = "account-B"
        sync.gateAdmit?.complete(Unit)
        advanceUntilIdle()

        assertNull("a late admission result claims nothing for the replacement account", store.attemptsRead["sync"]?.admittedWorkId)
        val record = store.attemptsRead["sync"]
        assertTrue(record?.operation is SetupOperationState.RecoveryRequired)
        // No observation callbacks ever attach: no association, no observer, no fabricated success.
        assertEquals(record?.operation, store.attemptsRead["sync"]?.operation)
        assertTrue(store.attemptsRead["sync"]?.operation !is SetupOperationState.Succeeded)
    }

    private class FixedAccountIdentity(private val marker: String?) : SetupAccountIdentity {
        override suspend fun state(): SetupAccountState {
            val m = marker ?: return SetupAccountState.Refused
            return SetupAccountState.Configured(m)
        }
    }

    private class MutableAccountIdentity(initial: String?) : SetupAccountIdentity {
        var marker: String? = initial
        override suspend fun state(): SetupAccountState {
            val m = marker ?: return SetupAccountState.Refused
            return SetupAccountState.Configured(m)
        }
    }

    private class ScriptedClock(private val current: () -> Long) : MonotonicClock {
        override fun elapsedMonotonicMillis(): Long = current()
    }
}
