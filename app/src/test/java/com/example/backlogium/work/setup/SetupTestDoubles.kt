package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.ActiveSetupStage
import com.example.backlogium.data.setup.SetupStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory [SetupStateStore]: the coordinator's persistence, observable by a test. */
class FakeSetupStateStore(
    private val initialOutcomes: MutableMap<String, SetupOutcome> = mutableMapOf(),
    private val initialOptIns: MutableMap<String, Boolean> = mutableMapOf(),
    private var activeStage: ActiveSetupStage? = null,
) : SetupStateStore {

    private val completedState = MutableStateFlow(false)
    private val firstRunActiveState = MutableStateFlow(false)

    val outcomes: Map<String, SetupOutcome> get() = initialOutcomes
    val optIns: Map<String, Boolean> get() = initialOptIns
    val completed: Boolean get() = completedState.value
    val active: ActiveSetupStage? get() = activeStage

    override val completedFlow: Flow<Boolean> = completedState

    override val firstRunSetupActiveFlow: Flow<Boolean> = firstRunActiveState

    val firstRunSetupActive: Boolean get() = firstRunActiveState.value

    override suspend fun storedOutcomes(): Map<String, SetupOutcome> = initialOutcomes.toMap()

    override suspend fun storedOptIns(): Map<String, Boolean> = initialOptIns.toMap()

    override suspend fun storedActiveStage(): ActiveSetupStage? = activeStage

    override suspend fun beginRun(firstStageId: String, selectedStageIds: Set<String>) {
        activeStage = ActiveSetupStage(firstStageId, null, selectedStageIds)
        selectedStageIds.forEach { initialOutcomes[it] = SetupOutcome.NeverRun }
    }

    override suspend fun markStageStarted(
        stageId: String,
        selectedStageIds: Set<String>,
    ) {
        activeStage = ActiveSetupStage(stageId, null, selectedStageIds)
        initialOutcomes[stageId] = SetupOutcome.NeverRun
    }

    override suspend fun markStageWorkStarted(stageId: String, workId: String) {
        if (activeStage?.stageId == stageId) activeStage = activeStage?.copy(workId = workId)
    }

    override suspend fun clearActiveStage() {
        activeStage = null
    }

    override suspend fun writeOutcome(stageId: String, outcome: SetupOutcome) {
        initialOutcomes[stageId] = outcome
    }

    override suspend fun writeOptIn(stageId: String, optIn: Boolean) {
        initialOptIns[stageId] = optIn
    }

    override suspend fun markCompleted() {
        completedState.value = true
    }

    override suspend fun setFirstRunSetupActive(active: Boolean) {
        firstRunActiveState.value = active
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
