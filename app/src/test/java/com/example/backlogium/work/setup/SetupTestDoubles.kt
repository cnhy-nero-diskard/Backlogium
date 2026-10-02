package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.LegacyActiveMarker
import com.example.backlogium.data.setup.SetupCohort
import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.data.setup.StageAttemptRecord
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/** In-memory [SetupStateStore]: the coordinator's persistence, observable by a test. */
class FakeSetupStateStore(
    initialOutcomes: MutableMap<String, SetupOutcome> = mutableMapOf(),
    private val initialOptIns: MutableMap<String, Boolean> = mutableMapOf(),
    private val initialAttempts: MutableMap<String, StageAttemptRecord> = mutableMapOf(),
    private var legacyMarker: LegacyActiveMarker? = null,
) : SetupStateStore {

    private val completedState = MutableStateFlow(false)
    private val firstRunActiveState = MutableStateFlow(false)
    private val legacyOutcomes = initialOutcomes
    private val optIns = initialOptIns
    private val attempts = initialAttempts
    private var cohortState = SetupCohort()

    /** Legacy terminal projection: legacy keys plus each attempt record's terminal projection. */
    val outcomes: Map<String, SetupOutcome>
        get() = legacyOutcomes.toMap() +
            attempts.mapValues { outcomeOfOperation(it.value.operation) }
    val optInsRead: Map<String, Boolean> get() = optIns.toMap()
    val attemptsRead: Map<String, StageAttemptRecord> get() = attempts.toMap()
    val completed: Boolean get() = completedState.value
    val firstRunSetupActive: Boolean get() = firstRunActiveState.value
    val cohort: SetupCohort get() = cohortState

    override val completedFlow: Flow<Boolean> = completedState

    override val firstRunSetupActiveFlow: Flow<Boolean> = firstRunActiveState

    override suspend fun storedOutcomes(): Map<String, SetupOutcome> = outcomes

    override suspend fun storedOptIns(): Map<String, Boolean> = optIns.toMap()

    override suspend fun storedAttemptRecords(): Map<String, StageAttemptRecord> = attempts.toMap()

    override suspend fun attemptRecord(stageId: String): StageAttemptRecord? = attempts[stageId]

    override suspend fun upsertAttempt(record: StageAttemptRecord) {
        attempts[record.stageId] = record
    }

    override suspend fun compareAndSwapAttempt(
        stageId: String,
        expectedGeneration: Long,
        expectedAdmittedWorkId: String?,
        expectedAccountMarker: String?,
        newRecord: StageAttemptRecord,
    ): Boolean {
        val current = attempts[stageId]
        return if (current != null &&
            current.generation == expectedGeneration &&
            current.admittedWorkId == expectedAdmittedWorkId &&
            current.accountMarker == expectedAccountMarker
        ) {
            attempts[stageId] = newRecord
            true
        } else {
            false
        }
    }

    override suspend fun removeAttempt(stageId: String) {
        attempts.remove(stageId)
    }

    override suspend fun cohort(): SetupCohort = cohortState

    override suspend fun setCohort(
        cohortId: String,
        selectedStageIds: Set<String>,
        accountMarker: String?,
    ) {
        cohortState = SetupCohort(cohortId, selectedStageIds, accountMarker)
    }

    override suspend fun clearCohort() {
        cohortState = SetupCohort()
    }

    override suspend fun writeOutcome(stageId: String, outcome: SetupOutcome) {
        legacyOutcomes[stageId] = outcome
    }

    override suspend fun writeOptIn(stageId: String, optIn: Boolean) {
        optIns[stageId] = optIn
    }

    override suspend fun markCompleted() {
        completedState.value = true
    }

    override suspend fun setFirstRunSetupActive(active: Boolean) {
        firstRunActiveState.value = active
    }

    override suspend fun readLegacyActiveMarker(): LegacyActiveMarker? = legacyMarker

    override suspend fun consumeLegacyActiveMarker() {
        legacyMarker = null
    }
}

/**
 * A stage whose work is a latch a test releases, so the ordering and settlement the coordinator is
 * supposed to guarantee are observable rather than inferred from timing.
 *
 * [admit] reports a [StageAdmission.Work] with [scriptedInitialState]; [observe] emits the scripted
 * states (or progress plus one terminal state), waiting on [gate] first when [autoComplete] is
 * false. [locateResult] drives [locate] for interrupted-admission recovery.
 */
class FakeStageRunner(
    private val outcome: SetupOutcome = SetupOutcome.Succeeded,
    private val throws: Exception? = null,
    scriptedInitialState: SetupOperationState = SetupOperationState.Running(),
    scriptedStates: List<SetupOperationState>? = null,
) : SetupStageRunner {
    /** Completed by the test to let this stage finish. Auto-completed when [autoComplete]. */
    val gate = CompletableDeferred<Unit>()

    var started = false
        private set

    val admittedRequestIds = mutableListOf<String>()
    private val progressToEmit = mutableListOf<SetupStageProgress>()

    var autoComplete = true

    /** The operation reported immediately after [admit] (defaults to running). */
    var scriptedInitialState: SetupOperationState = scriptedInitialState

    /** The states [observe] emits, in order (defaults to progress then the terminal state). */
    var scriptedStates: List<SetupOperationState>? = scriptedStates

    /** When non-null, [locate] returns this exact admission instead of failing. */
    var locateResult: AdmittedWork? = null

    /** When non-null, [observe]'s final state uses this instead of the constructor [outcome]. */
    var outcomeOverride: SetupOperationState? = null

    /** When non-null, [currentLiveCandidate] returns this exact job. */
    var scriptedLiveCandidate: UUID? = null

    override val uniqueWorkName: String = "fake_work"

    /** The exact id every [admit] returns; distinct per `FakeStageRunner` instance. */
    val admittedWorkId: String = UUID.randomUUID().toString()

    fun emitting(vararg progress: SetupStageProgress): FakeStageRunner {
        progressToEmit += progress
        return this
    }

    /** When non-null, [currentLiveCandidate] suspends on this gate first (account-flip fixture). */
    var gateLiveCandidate: CompletableDeferred<Unit>? = null

    /** When non-null, [admit] suspends on this gate before returning the admission (enqueue-await fixture). */
    var gateAdmit: CompletableDeferred<Unit>? = null

    override suspend fun admit(requestId: String): StageAdmission {
        started = true
        admittedRequestIds += requestId
        gateAdmit?.await()
        return StageAdmission.Work(
            workId = UUID.fromString(admittedWorkId),
            uniqueWorkName = uniqueWorkName,
            kind = AdmissionKind.NEW,
            initialState = scriptedInitialState,
        )
    }

    override fun observe(workId: String): Flow<SetupOperationState> = flow {
        scriptedStates?.let { states ->
            // A gated scripted flow delays every state until the gate releases, so "later"
            // settlements can be observed after other coordinator actions.
            if (!autoComplete) gate.await()
            states.forEach { emit(it) }
            gate.completeIfNotDone()
            return@flow
        }
        progressToEmit.forEach { emit(SetupOperationState.Running(it)) }
        if (!autoComplete) gate.await()
        throws?.let { throw it }
        emit(outcomeOverride ?: operationOf(outcome))
    }

    override suspend fun currentLiveCandidate(): UUID? {
        gateLiveCandidate?.await()
        return scriptedLiveCandidate
    }

    override suspend fun locate(requestId: String, candidateWorkId: UUID?): AdmittedWork? = locateResult

    /** Release the observation gate exactly once (idempotent). */
    fun releaseGate() {
        runCatching { gate.complete(Unit) }
    }

    private fun CompletableDeferred<Unit>.completeIfNotDone() {
        if (!isCompleted) complete(Unit)
    }

    private fun operationOf(outcome: SetupOutcome): SetupOperationState = when (outcome) {
        SetupOutcome.Succeeded -> SetupOperationState.Succeeded()
        SetupOutcome.Skipped -> SetupOperationState.Skipped
        SetupOutcome.NeverRun -> SetupOperationState.NeverRun
        is SetupOutcome.Failed -> SetupOperationState.Failed(outcome.reason)
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

class FakeStageSource(
    override val stages: List<SetupStage>,
) : SetupStageSource