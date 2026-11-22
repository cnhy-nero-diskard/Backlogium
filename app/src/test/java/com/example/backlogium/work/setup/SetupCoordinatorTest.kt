package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.SetupJournal
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.data.setup.SetupStageRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupCoordinatorTest {
    @Test
    fun runningLibraryWaitsTwoMinutesWhileDuplicateTapsAreIgnored() = runTest {
        val library = pendingRunner(SetupStageActivity.RUNNING)
        val later = FakeStageRunner()
        val subject = coordinator(listOf(fakeStage("library", library), fakeStage("later", later)))

        subject.start(setOf("library", "later"))
        subject.start(setOf("library"))
        runCurrent()
        assertTrue(subject.state.value.running)
        assertEquals("library", subject.state.value.currentStageId)
        assertEquals(1, library.attempts)
        advanceTimeBy(119_999)
        runCurrent()
        assertFalse(later.started)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(later.started)
        assertTrue(subject.state.value.finished)
        assertEquals(setOf("library"), subject.state.value.pending)
        assertEquals(SetupOutcome.Succeeded, subject.state.value.outcomes["later"])
        library.gate.complete(Unit)
        runCurrent()
        assertEquals(SetupOutcome.Succeeded, subject.state.value.outcomes["library"])
        assertTrue(subject.state.value.pending.isEmpty())
    }

    @Test
    fun queuedAndBackingOffStagesSettleTheWaitInRegistryOrder() = runTest {
        val first = pendingRunner(SetupStageActivity.WAITING)
        val second = pendingRunner(SetupStageActivity.RETRY_BACKOFF)
        val third = FakeStageRunner()
        val subject = coordinator(listOf(fakeStage("first", first), fakeStage("second", second), fakeStage("third", third)))

        subject.start(setOf("third", "first", "second"))
        runCurrent()

        assertTrue(subject.state.value.inScreenSettled)
        assertEquals(setOf("first", "second"), subject.state.value.pending)
        assertEquals(listOf("first", "second"), subject.state.value.stageProgress.keys.toList())
        assertEquals(SetupStageActivity.RETRY_BACKOFF, subject.state.value.stageProgress["second"]?.activity)
        assertEquals(SetupOutcome.Succeeded, subject.state.value.outcomes["third"])
        assertEquals(1, third.attempts)
    }

    @Test
    fun continueAllowsLaterAdmissionsWhileTheEarlierWorkerStaysPending() = runTest {
        val library = pendingRunner(SetupStageActivity.RUNNING)
        val later = pendingRunner(SetupStageActivity.RUNNING)
        val subject = coordinator(listOf(
            fakeStage("library", library),
            fakeStage("detached", later, execution = SetupStageExecution.DETACHED),
        ))
        subject.claimFirstRunSetup()
        subject.start(setOf("library", "detached"))
        runCurrent()
        assertFalse(later.started)

        subject.advanceToHistory()
        runCurrent()

        assertEquals(FirstRunPhase.HISTORY, subject.firstRunPhase.first())
        assertEquals(setOf("library", "detached"), subject.state.value.pending)
        assertEquals(1, later.attempts)
        assertTrue(subject.state.value.finished)
        subject.releaseFirstRunSetup()
        later.gate.complete(Unit)
        runCurrent()
        assertNull(subject.firstRunPhase.first())
    }

    @Test
    fun restartAttachesMultiplePendingStagesAndAdmitsTheRemainingSelectedStage() = runTest {
        val before = SetupJournal(
            owner = fixtureSetupOwner,
            stages = linkedMapOf(
                "earlier" to SetupStageRecord(encodedOutcome = "ok"),
                "library" to SetupStageRecord("req-library", "kept-work"),
                "assets" to SetupStageRecord("req-assets"),
                "times" to SetupStageRecord(encodedOutcome = "ok"),
            ),
            selected = setOf("library", "assets", "times"),
            remaining = listOf("times"),
            runOpen = true,
        )
        val store = FakeSetupStateStore(before)
        val library = pendingRunner(SetupStageActivity.WAITING)
        val assets = pendingRunner(SetupStageActivity.RUNNING)
        val times = FakeStageRunner()
        val subject = coordinator(listOf(
            fakeStage("earlier"), fakeStage("library", library), fakeStage("assets", assets), fakeStage("times", times),
        ), store)

        assertEquals(listOf("kept-work"), library.recoveredIds)
        assertEquals(listOf("req-assets"), assets.recoveredIds)
        assertEquals(0, library.attempts + assets.attempts)
        assertEquals(1, times.attempts)
        assertEquals("req-assets", store.journal.stages["assets"]?.workId)
        assertEquals(setOf("library", "assets"), subject.state.value.pending)
        assertEquals(SetupOutcome.Succeeded, store.outcomes["earlier"])
        assertEquals(SetupOutcome.Succeeded, store.outcomes["times"])
    }

    @Test
    fun lostAdmissionRequiresExplicitRecoveryAndDoesNotBlockTheRemainingStage() = runTest {
        val store = FakeSetupStateStore(SetupJournal(
            owner = fixtureSetupOwner,
            stages = mapOf("library" to SetupStageRecord(requestId = "never-enqueued")),
            selected = setOf("library", "times"),
            remaining = listOf("times"),
            runOpen = true,
        ))
        var attempts = 0
        val library = SetupStageRunner { attempts++; SetupOutcome.Succeeded }
        val subject = coordinator(listOf(fakeStage("library", library), fakeStage("times")), store)

        assertEquals(0, attempts)
        assertEquals(SetupOutcome.RecoveryRequired, subject.state.value.outcomes["library"])
        assertEquals(SetupOutcome.Succeeded, subject.state.value.outcomes["times"])
        subject.start(setOf("library"), recordUnselectedAsSkipped = false)
        runCurrent()
        assertEquals(1, attempts)
        assertEquals(SetupOutcome.Succeeded, subject.state.value.outcomes["library"])
        assertEquals(SetupOutcome.Succeeded, subject.state.value.outcomes["times"])
    }

    @Test
    fun replacementAccountSupersedesAnOlderCallback() = runTest {
        val ownerChanges = FakeSetupAccounts()
        val store = FakeSetupStateStore()
        var previousProgress: ((SetupStageProgress) -> Unit)? = null
        val original = SetupStageRunner { onProgress ->
            if (previousProgress == null) previousProgress = onProgress
            SetupOutcome.Succeeded
        }
        val subject = coordinator(listOf(fakeStage("library", original)), store, ownerChanges)
        subject.start(setOf("library"))
        runCurrent()
        assertEquals(SetupOutcome.Succeeded, store.outcomes["library"])

        ownerChanges.owners.value = null
        runCurrent()
        ownerChanges.owners.value = SetupOwner(fixtureSetupOwner.steamId, 2)
        runCurrent()
        subject.start(setOf("library"), recordUnselectedAsSkipped = false)
        runCurrent()
        previousProgress?.invoke(SetupStageProgress(1, 2))
        runCurrent()

        assertEquals(2L, store.journal.owner.generation)
        assertEquals(SetupOutcome.Succeeded, store.outcomes["library"])
        assertTrue(subject.state.value.stageProgress.isEmpty())
    }

    @Test
    fun staleHistoryCompletionCannotDismissTheReplacementAccountsDecision() = runTest {
        val owners = FakeSetupAccounts()
        val subject = coordinator(listOf(fakeStage("library")), accounts = owners)
        subject.claimFirstRunSetup()
        subject.advanceToHistory()
        val oldOwner = subject.firstRunOwner.first()

        owners.owners.value = SetupOwner("replacement", 1)
        runCurrent()
        assertNull(subject.firstRunPhase.first())
        subject.claimFirstRunSetup()
        subject.advanceToHistory()
        subject.releaseFirstRunSetup(oldOwner)

        assertEquals(FirstRunPhase.HISTORY, subject.firstRunPhase.first())
        assertEquals("replacement", subject.firstRunOwner.first()?.steamId)
    }

    @Test
    fun unavailableAndUnselectedStagesNeverStartAndFailuresAreIsolated() = runTest {
        val throwing = FakeStageRunner(throws = IllegalStateException("fixture failure"))
        val blocked = FakeStageRunner()
        val unselected = FakeStageRunner()
        val after = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(
            fakeStage("throwing", throwing),
            fakeStage("blocked", blocked, unavailableReason = "Unavailable"),
            fakeStage("unselected", unselected),
            fakeStage("after", after),
        ), store)

        subject.start(setOf("throwing", "blocked", "after"))
        runCurrent()

        assertEquals(SetupOutcome.Failed("fixture failure"), store.outcomes["throwing"])
        assertEquals(SetupOutcome.Skipped, store.outcomes["unselected"])
        assertFalse(blocked.started)
        assertFalse(unselected.started)
        assertTrue(after.started)
        assertEquals(SetupOutcome.Succeeded, store.outcomes["after"])
    }

    @Test
    fun decliningSetupRecordsSkippedAndAdvancesTheFirstRunPhase() = runTest {
        val runner = FakeStageRunner()
        val store = FakeSetupStateStore()
        val subject = coordinator(listOf(fakeStage("library", runner)), store)

        assertNull(subject.firstRunPhase.first())
        subject.claimFirstRunSetup()
        assertTrue(subject.skipAll())

        assertEquals(FirstRunPhase.HISTORY, subject.firstRunPhase.first())
        assertEquals(SetupOutcome.Skipped, store.outcomes["library"])
        assertFalse(runner.started)
        subject.releaseFirstRunSetup()
        assertNull(subject.firstRunPhase.first())
    }

    @Test
    fun restartAfterAnAdmissionIntentDoesNotReenqueueIt() = runTest {
        val store = FakeSetupStateStore()
        val dyingScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val interrupted = SetupStageRunner { kotlinx.coroutines.awaitCancellation() }
        val first = coordinator(listOf(fakeStage("a", interrupted), fakeStage("b")), store, scope = dyingScope)
        first.start(setOf("b", "a"))
        runCurrent()
        assertNull(store.journal.stages["a"]?.workId)
        assertEquals(listOf("b"), store.journal.remaining)
        dyingScope.cancel()

        val later = FakeStageRunner()
        val recovered = coordinator(listOf(fakeStage("a", interrupted), fakeStage("b", later)), store)

        assertEquals(SetupOutcome.RecoveryRequired, recovered.state.value.outcomes["a"])
        assertTrue(later.started)
    }

    private fun pendingRunner(activity: SetupStageActivity) =
        FakeStageRunner().emitting(SetupStageProgress(0, 0, activity = activity)).also { it.autoComplete = false }

    private fun TestScope.coordinator(
        stages: List<SetupStage>,
        store: FakeSetupStateStore = FakeSetupStateStore(),
        accounts: FakeSetupAccounts = FakeSetupAccounts(),
        scope: CoroutineScope = backgroundScope,
    ) = SetupCoordinator(FakeStageSource(stages), store, accounts, scope).also {
        it.ensureLoaded()
        runCurrent()
    }
}
