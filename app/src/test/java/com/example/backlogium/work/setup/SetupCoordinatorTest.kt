package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.ActiveSetupStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator's contract: run what was selected in registered order, record each stage's own
 * terminal outcome, and never let one stage's failure reach another.
 *
 * Runners here are latches rather than real work, so ordering and isolation are asserted directly
 * instead of being inferred from how long something took.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupCoordinatorTest {

    private fun coordinator(
        stages: List<SetupStage>,
        store: FakeSetupStateStore = FakeSetupStateStore(),
        scope: TestScope,
    ) = SetupCoordinator(FakeStageSource(stages), store, scope)

    @Test
    fun runsOnlySelectedStagesAndRecordsTheRestSkipped() = runTest(StandardTestDispatcher()) {
        val first = FakeStageRunner()
        val second = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("a", first), fakeStage("b", second)),
            store,
            this,
        )

        subject.start(setOf("a"))
        advanceUntilIdle()

        assertTrue(first.started)
        assertFalse("a deselected stage must not enqueue its work", second.started)
        assertEquals(SetupOutcome.Succeeded, store.outcomes["a"])
        assertEquals(SetupOutcome.Skipped, store.outcomes["b"])
        assertTrue(subject.state.value.finished)
        assertTrue(store.completed)
    }

    @Test
    fun decliningSetupRunsNothingAndSkipsEverything() = runTest(StandardTestDispatcher()) {
        val first = FakeStageRunner()
        val second = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("a", first), fakeStage("b", second)),
            store,
            this,
        )

        subject.skipAll()
        advanceUntilIdle()

        assertFalse(first.started)
        assertFalse(second.started)
        assertEquals(SetupOutcome.Skipped, store.outcomes["a"])
        assertEquals(SetupOutcome.Skipped, store.outcomes["b"])
        // Setup completes rather than sitting unresolved: declining is an answer, not an omission.
        assertTrue(subject.state.value.finished)
        assertFalse(subject.state.value.running)
    }

    @Test
    fun aFailingStageLeavesLaterStagesRunningAndEarlierResultsIntact() =
        runTest(StandardTestDispatcher()) {
            val ok = FakeStageRunner(SetupOutcome.Succeeded)
            val bad = FakeStageRunner(SetupOutcome.Failed("service was slow"))
            val after = FakeStageRunner(SetupOutcome.Succeeded)
            val store = FakeSetupStateStore()
            val subject = coordinator(
                listOf(fakeStage("ok", ok), fakeStage("bad", bad), fakeStage("after", after)),
                store,
                this,
            )

            subject.start(setOf("ok", "bad", "after"))
            advanceUntilIdle()

            assertTrue("a later stage must still run after a failure", after.started)
            assertEquals(SetupOutcome.Succeeded, store.outcomes["ok"])
            assertEquals(SetupOutcome.Failed("service was slow"), store.outcomes["bad"])
            assertEquals(SetupOutcome.Succeeded, store.outcomes["after"])
            // Setup completes; it does not itself fail. "Setup failed" is never the right report
            // when the stages are unrelated and only one of them went wrong.
            assertTrue(subject.state.value.finished)
        }

    @Test
    fun aRunnerThatThrowsIsThatStagesFailureAndNoOnesElse() = runTest(StandardTestDispatcher()) {
        val throwing = FakeStageRunner(throws = IllegalStateException("boom"))
        val after = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(fakeStage("throwing", throwing), fakeStage("after", after)),
            store,
            this,
        )

        subject.start(setOf("throwing", "after"))
        advanceUntilIdle()

        assertEquals(SetupOutcome.Failed("boom"), store.outcomes["throwing"])
        assertTrue(after.started)
        assertTrue(subject.state.value.finished)
    }

    @Test
    fun anUnavailableStageCannotRunAndDoesNotBlockTheOthers() = runTest(StandardTestDispatcher()) {
        val blocked = FakeStageRunner()
        val other = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(
            listOf(
                fakeStage("blocked", blocked, unavailableReason = "Needs a capability this build lacks"),
                fakeStage("other", other),
            ),
            store,
            this,
        )

        // Even handed in explicitly — a stale selection from a recreated surface — it must not run.
        subject.start(setOf("blocked", "other"))
        advanceUntilIdle()

        assertFalse(blocked.started)
        assertTrue(other.started)
        assertEquals(SetupOutcome.Succeeded, store.outcomes["other"])
        assertTrue(subject.state.value.finished)
    }

    @Test
    fun aStageInProgressIsReportedWithItsProgress() = runTest(StandardTestDispatcher()) {
        val running = FakeStageRunner().emitting(SetupStageProgress(3, 10, "Portal"))
        running.autoComplete = false
        val subject = coordinator(listOf(fakeStage("running", running)), scope = this)

        subject.start(setOf("running"))
        advanceUntilIdle()

        val state = subject.state.value
        assertEquals("running", state.currentStageId)
        assertEquals(SetupStageProgress(3, 10, "Portal"), state.progress)
        assertTrue(state.running)
        assertFalse(state.finished)

        running.gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(subject.state.value.finished)
    }

    @Test
    fun theAppCanBeEnteredOnceInScreenStagesSettle() = runTest(StandardTestDispatcher()) {
        val inScreen = FakeStageRunner()
        val detached = FakeStageRunner()
        detached.autoComplete = false
        val subject = coordinator(
            listOf(
                fakeStage("sync", inScreen, execution = SetupStageExecution.IN_SCREEN),
                fakeStage("assets", detached, execution = SetupStageExecution.DETACHED),
            ),
            scope = this,
        )

        subject.start(setOf("sync", "assets"))
        advanceUntilIdle()

        val state = subject.state.value
        assertTrue("in-screen work is done, so the app is usable", state.inScreenSettled)
        assertFalse("the detached stage is still going", state.finished)

        // Release the latch so the test scope has nothing left suspended on it.
        detached.gate.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun aSettingsReRunReplacesOnlyItsOwnStagesOutcomes() = runTest(StandardTestDispatcher()) {
        val store = FakeSetupStateStore(
            initialOutcomes = mutableMapOf(
                "a" to SetupOutcome.Succeeded,
                "b" to SetupOutcome.Failed("earlier failure"),
            ),
        )
        val subject = coordinator(
            listOf(fakeStage("a"), fakeStage("b", FakeStageRunner(SetupOutcome.Succeeded))),
            store,
            this,
        )
        subject.ensureLoaded()
        advanceUntilIdle()

        subject.start(setOf("b"), recordUnselectedAsSkipped = false)
        advanceUntilIdle()

        assertEquals(SetupOutcome.Succeeded, store.outcomes["b"])
        // Retrying one stage from Settings is a decision about that stage. Recording the rest
        // skipped would erase the outcomes that list exists to show.
        assertEquals(SetupOutcome.Succeeded, store.outcomes["a"])
    }

    @Test
    fun storedOutcomesForStagesThisBuildDoesNotKnowAreIgnored() = runTest(StandardTestDispatcher()) {
        val store = FakeSetupStateStore(
            initialOutcomes = mutableMapOf(
                "known" to SetupOutcome.Succeeded,
                "stage_from_a_later_version" to SetupOutcome.Failed("whatever"),
            ),
        )
        val subject = coordinator(listOf(fakeStage("known")), store, this)

        subject.ensureLoaded()
        advanceUntilIdle()

        val state = subject.state.value
        assertTrue("setup still renders", state.loaded)
        assertEquals(setOf("known"), state.outcomes.keys)
    }

    @Test
    fun aStoredSelectionIsRestoredSoARecreatedSurfaceKnowsWhatTheRunCovered() =
        runTest(StandardTestDispatcher()) {
            val store = FakeSetupStateStore(
                initialOptIns = mutableMapOf("a" to true, "b" to false),
            )
            val subject = coordinator(listOf(fakeStage("a"), fakeStage("b")), store, this)

            subject.ensureLoaded()
            advanceUntilIdle()

            assertEquals(setOf("a"), subject.state.value.selected)
        }

    @Test
    fun recoversDetachedWorkAndContinuesTheRemainingStages() =
        runTest(StandardTestDispatcher()) {
            var recoveredWorkId: String? = null
            val recovering = object : SetupStageRunner {
                override suspend fun run(
                    onProgress: (SetupStageProgress) -> Unit,
                ): SetupOutcome = error("recovery should not enqueue a new job")

                override suspend fun recover(
                    workId: String,
                    onProgress: (SetupStageProgress) -> Unit,
                ): SetupOutcome {
                    recoveredWorkId = workId
                    return SetupOutcome.Succeeded
                }
            }
            val later = FakeStageRunner()
            val store = FakeSetupStateStore(
                initialOutcomes = mutableMapOf(
                    "sync" to SetupOutcome.NeverRun,
                    "assets" to SetupOutcome.NeverRun,
                ),
                initialOptIns = mutableMapOf(
                    "sync" to true,
                    "assets" to true,
                ),
                activeStage = ActiveSetupStage(
                    stageId = "sync",
                    workId = "detached-work-id",
                    selectedStageIds = setOf("sync", "assets"),
                ),
            )
            val subject = coordinator(
                listOf(
                    fakeStage("sync", recovering),
                    fakeStage("assets", later, execution = SetupStageExecution.DETACHED),
                ),
                store,
                this
            )

            subject.ensureLoaded()
            advanceUntilIdle()

            assertEquals("detached-work-id", recoveredWorkId)
            assertEquals(SetupOutcome.Succeeded, store.outcomes["sync"])
            assertTrue(later.started)
            assertEquals(SetupOutcome.Succeeded, store.outcomes["assets"])
            assertNull(store.active)
            assertTrue(subject.state.value.finished)
        }

    @Test
    fun aRunResetsEveryStagesStoredOutcomeBeforeTheFirstOneStarts() =
        runTest(StandardTestDispatcher()) {
            val held = FakeStageRunner().also { it.autoComplete = false }
            val store = FakeSetupStateStore(
                initialOutcomes = mutableMapOf(
                    "a" to SetupOutcome.Succeeded,
                    "b" to SetupOutcome.Succeeded,
                ),
            )
            val subject = coordinator(
                listOf(fakeStage("a", held), fakeStage("b")),
                store,
                this,
            )

            subject.start(setOf("a", "b"), recordUnselectedAsSkipped = false)
            advanceUntilIdle()

            // Both selected stages are durably NeverRun while the first is still going. A stale
            // `Succeeded` left on "b" here is what recovery would read as "already done this run".
            assertEquals(SetupOutcome.NeverRun, store.outcomes["a"])
            assertEquals(SetupOutcome.NeverRun, store.outcomes["b"])

            held.gate.complete(Unit)
            advanceUntilIdle()
        }

    @Test
    fun recoveryRunsALaterSelectedStageThatSucceededInAnEarlierRun() =
        runTest(StandardTestDispatcher()) {
            val store = FakeSetupStateStore(
                initialOutcomes = mutableMapOf(
                    "a" to SetupOutcome.Succeeded,
                    "b" to SetupOutcome.Succeeded,
                ),
            )

            // The process that starts the re-run and dies while the first stage is still going.
            val dyingProcess = TestScope(StandardTestDispatcher(testScheduler))
            val held = FakeStageRunner().also { it.autoComplete = false }
            SetupCoordinator(
                FakeStageSource(listOf(fakeStage("a", held), fakeStage("b"))),
                store,
                dyingProcess,
            ).start(setOf("a", "b"), recordUnselectedAsSkipped = false)
            advanceUntilIdle()
            dyingProcess.cancel()

            val recoveredA = FakeStageRunner()
            val recoveredB = FakeStageRunner()
            val next = coordinator(
                listOf(fakeStage("a", recoveredA), fakeStage("b", recoveredB)),
                store,
                this,
            )
            next.ensureLoaded()
            advanceUntilIdle()

            assertTrue("the stage the old process died on is resumed", recoveredA.started)
            // The reported defect: "b" still held its previous run's Succeeded, so recovery read it
            // as complete and the re-run silently dropped it.
            assertTrue("a later selected stage is not skipped by a stale outcome", recoveredB.started)
            assertEquals(SetupOutcome.Succeeded, store.outcomes["b"])
            assertTrue(next.state.value.finished)
        }

    @Test
    fun theFirstRunTakeoverIsClaimedDurablyAndReleasedOnlyWhenDismissed() =
        runTest(StandardTestDispatcher()) {
            val store = FakeSetupStateStore()
            val subject = coordinator(listOf(fakeStage("a")), store, this)

            assertFalse(
                "an install that predates the flag is not sent to setup",
                subject.firstRunSetupActive.first(),
            )

            subject.claimFirstRunSetup()
            assertTrue(subject.firstRunSetupActive.first())

            // Completing the run is not dismissing the surface: the user still has to leave it, and
            // a process killed on the finished summary must come back to it.
            subject.start(setOf("a"))
            advanceUntilIdle()
            assertTrue(subject.firstRunSetupActive.first())

            subject.releaseFirstRunSetup()
            assertFalse(subject.firstRunSetupActive.first())
        }

}
