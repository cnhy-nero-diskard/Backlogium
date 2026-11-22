package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.SetupAccounts
import com.example.backlogium.data.setup.SetupJournal
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.data.setup.SetupStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

val fixtureSetupOwner = SetupOwner("fixture-account", 0)

class FakeSetupAccounts : SetupAccounts {
    override val owners = MutableStateFlow<SetupOwner?>(fixtureSetupOwner)
    override suspend fun <T> ifCurrent(owner: SetupOwner, block: suspend () -> T): T? =
        if (owner == owners.value) block() else null
}

class FakeSetupStateStore(
    var journal: SetupJournal = SetupJournal(fixtureSetupOwner),
    private val loadGate: CompletableDeferred<Unit>? = null,
) : SetupStateStore {
    val outcomes get() = journal.stages.mapValues { it.value.outcome }
    override val firstRunPhaseFlow = MutableStateFlow<FirstRunPhase?>(null)

    override suspend fun update(
        owner: SetupOwner,
        finishRun: Boolean,
        transform: (SetupJournal) -> SetupJournal,
    ): SetupJournal {
        loadGate?.await()
        journal = transform(journal.takeIf { it.owner == owner } ?: SetupJournal(owner))
        if (finishRun) advanceToHistory()
        return journal
    }

    override suspend fun advanceToHistory() {
        if (firstRunPhaseFlow.value == FirstRunPhase.SETUP) firstRunPhaseFlow.value = FirstRunPhase.HISTORY
    }

    override suspend fun setFirstRunSetupActive(active: Boolean) {
        firstRunPhaseFlow.value = if (active) FirstRunPhase.SETUP else null
    }
}

/**
 * A stage whose work is a latch a test releases, so the ordering the coordinator is supposed to
 * guarantee is observable rather than inferred from timing.
 */
class FakeStageRunner(
    private val outcome: SetupOutcome = SetupOutcome.Succeeded,
    private val throws: Exception? = null,
) : SetupStageRunner {
    /** Completed by the test to let this stage finish. Auto-completed when [autoComplete]. */
    val gate = CompletableDeferred<Unit>()
    var started = false
        private set
    private val progressToEmit = mutableListOf<SetupStageProgress>()

    var autoComplete = true

    fun emitting(vararg progress: SetupStageProgress): FakeStageRunner {
        progressToEmit += progress
        return this
    }

    var attempts = 0
        private set
    val recoveredIds = mutableListOf<String>()

    override suspend fun runRequest(
        requestId: String,
        accountGeneration: Long,
        onProgress: (SetupStageProgress) -> Unit,
        onWorkStarted: suspend (String) -> Unit,
    ): SetupOutcome {
        attempts++
        onWorkStarted("work-$requestId")
        return run(onProgress)
    }

    override suspend fun recoverRequest(
        workId: String,
        onProgress: (SetupStageProgress) -> Unit,
        onWorkFound: suspend (String) -> Unit,
    ): SetupOutcome {
        recoveredIds += workId
        onWorkFound(workId)
        return run(onProgress)
    }

    override suspend fun run(onProgress: (SetupStageProgress) -> Unit): SetupOutcome {
        started = true
        progressToEmit.forEach(onProgress)
        if (!autoComplete) gate.await()
        throws?.let { throw it }
        return outcome
    }
}

fun fakeStage(
    id: String,
    runner: SetupStageRunner = FakeStageRunner(),
    defaultOptIn: Boolean = false,
    execution: SetupStageExecution = SetupStageExecution.IN_SCREEN,
    unavailableReason: String? = null,
): SetupStage = SetupStage(
    id = id,
    title = "Stage $id",
    detail = "What $id does",
    defaultOptIn = defaultOptIn,
    execution = execution,
    unavailableReason = unavailableReason,
    run = runner,
)

class FakeStageSource(override val stages: List<SetupStage>) : SetupStageSource
