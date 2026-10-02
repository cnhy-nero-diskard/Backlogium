package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.data.setup.StageAttemptRecord
import com.example.backlogium.di.ApplicationScope
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a setup surface needs to render, derived from the registry plus stored state.
 *
 * `operations` is the per-stage truth a post-3.x surface projects against ([SetupOperationState]);
 * `outcomes` is the legacy terminal projection kept for surfaces that predate it. [running] is
 * strictly foreground activity — background reconciliation of settled work never reads as
 * "running" — and [finished] is the compat flag for "every selected stage's foreground wait
 * settled" (or setup was declined), independent of whether underlying work reached a terminal state.
 */
data class SetupRunState(
    /** False until stored state has been read; the surface shows nothing definite before that. */
    val loaded: Boolean = false,
    /** True only while at least one selected stage's foreground observation is still owed. */
    val running: Boolean = false,
    /** The stage currently observed in the foreground, or null between stages and when idle. */
    val currentStageId: String? = null,
    /** The current stage's foreground progress, or null when it has not published a usable one. */
    val progress: SetupStageProgress? = null,
    /** Legacy terminal projection, keyed by registered stage id (pending work reads as NeverRun). */
    val outcomes: Map<String, SetupOutcome> = emptyMap(),
    /** Each registered stage's latest real operation state, keyed by stage id. */
    val operations: Map<String, SetupOperationState> = emptyMap(),
    /** What the active foreground attempt covers. Immutable while the attempt is active. */
    val selected: Set<String> = emptySet(),
    /** True once every selected in-screen stage's foreground observation has settled. */
    val inScreenSettled: Boolean = false,
    /** Compat: settled when all selected foreground waits settled, or the user continued/declined. */
    val finished: Boolean = false,
    /** The active foreground attempt, or the most recently used one during a run. */
    val foreground: ForegroundAttempt? = null,
    /** Latest durable per-stage attempt records, keyed by registered stage id. */
    val attempts: Map<String, StageAttemptRecord> = emptyMap(),
)

private const val RECOVERY_EXPLANATION =
    "The work behind this step can't be found. Request it again from Settings."

/**
 * Runs the selected setup stages in registered order, settles each stage's foreground observation
 * independently (per-stage, with its own bounded budget), and keeps observing pending work on the
 * application scope after the surface is gone — reconciling every admitted operation by exact work
 * identity across recreation, Settings re-entry, and process death (design Decision 1 & 2).
 *
 * A singleton on the application scope, deliberately: the setup surface can be recreated without
 * losing the stage it was on, a detached stage's observation is not tied to a composition, and
 * saved attempts are reconciled even when the user never returns to a setup screen.
 *
 * Failure is isolated by construction. Each stage produces its own operation state, the admission
 * loop records it and continues, and setup never reports a global failure. Queued/backoff work is
 * pending, never failed; a missing operation is an explicit recovery request, never a loop or a
 * claimed success; budget expiry is a foreground fact, never a cancellation. No stage work is
 * admitted without an identified, non-cycling account, and a persisted request is never retriggered
 * automatically.
 */
@Singleton
class SetupCoordinator @Inject constructor(
    private val source: SetupStageSource,
    private val store: SetupStateStore,
    @ApplicationScope private val scope: CoroutineScope,
    private val accountIdentity: SetupAccountIdentity,
    private val clock: MonotonicClock,
) : FirstRunSetupGateway {

    /** Test/surface constructor with the explicit test-only account seam and a fixed clock. */
    constructor(source: SetupStageSource, store: SetupStateStore, scope: CoroutineScope) :
        this(source, store, scope, NoSetupAccountIdentity, SystemMonotonicClock())

    /** Whether the app still owes the user a first-run setup surface. Durable takeover flag. */
    override val firstRunSetupActive: Flow<Boolean> = store.firstRunSetupActiveFlow

    private val _state = MutableStateFlow(SetupRunState())
    val state: StateFlow<SetupRunState> = _state.asStateFlow()

    /** Serializes runs, retries, and reconciles so no two can drive admission at once. */
    private val runLock = Mutex()

    /** Serializes observation callbacks and the atomic attempt writes; never nests over [runLock]. */
    private val callbackLock = Mutex()

    private var loadJob: Job? = null

    /** The admission round currently in flight, if any. */
    @Volatile
    private var activeContext: RunContext? = null

    /** App-scope reconciliation observers keyed by stage id (tracking each observer's generation). */
    private val observationJobs = ConcurrentHashMap<String, StageObservation>()

    private class StageObservation(val generation: Long, val job: Job)

    /** In-memory mirror of the store's latest attempt records (eventually consistent, CAS-corrected). */
    private val attemptsCache = ConcurrentHashMap<String, StageAttemptRecord>()

    /** Historical legacy outcomes (pre-3.2 keys) for the terminal projection. Concurrency-safe. */
    private val legacyCache = ConcurrentHashMap<String, SetupOutcome>()

    private data class RunContext(
        val attempt: MutableStateFlow<ForegroundAttempt>,
        val stages: List<SetupStage>,
        val cohortId: String,
        val accountMarker: String?,
        val continueEvents: Channel<Unit> = Channel(Channel.CONFLATED),
    )

    private sealed interface ForegroundSignal {
        data class State(val operation: SetupOperationState) : ForegroundSignal
        data object Continue : ForegroundSignal
        data object Timeout : ForegroundSignal
    }

    // -----------------------------------------------------------------------------------------
    // Operations
    // -----------------------------------------------------------------------------------------

    /**
     * Start [selectedIds], in registered order.
     *
     * Nothing selected is a complete answer, not an error: setup finishes immediately, which with
     * [recordUnselectedAsSkipped] is exactly what declining setup does.
     *
     * [recordUnselectedAsSkipped] separates the two surfaces that start a run. Going through the
     * onboarding checklist is a decision about *every* stage, so the ones left unticked are
     * recorded skipped — but never overwriting an already-recorded outcome; re-running stages from
     * Settings is a decision about those stages alone and leaves the rest untouched.
     */
    fun start(selectedIds: Set<String>, recordUnselectedAsSkipped: Boolean = true) {
        scope.launch {
            if (!prepareRun(selectedIds, recordUnselectedAsSkipped)) return@launch
            finishAdmissionRound()
        }
    }

    /** Decline setup: nothing runs, every never-attempted stage is recorded skipped. */
    fun skipAll() = start(emptySet(), recordUnselectedAsSkipped = true)

    /**
     * Retry one stage. Replaces only that stage's attempt and recorded result; successful siblings
     * are untouched. Guarded at the coordinator boundary so duplicate taps cannot drive two
     * admissions (task 4.2). An explicit user action: it may also recover a previously persisted
     * request that never admitted.
     */
    fun retryStage(stageId: String) {
        scope.launch {
            if (!prepareRetryRun(stageId)) return@launch
            finishAdmissionRound()
        }
    }

    /**
     * Reobserve one stage's pending work (progress/reobserve action). Reattaches its live
     * observation or reconciles an interrupted admission; idempotent.
     */
    fun reobserveStage(stageId: String) {
        scope.launch {
            runLock.withLock {
                val stage = source.stages.firstOrNull { it.id == stageId } ?: return@withLock
                refreshStageLocked(stage)
            }
        }
    }

    /**
     * Explicit Continue/do-later during foreground work (task 4.4). Ends every foreground wait —
     * never the admission intent: already-selected remaining stages are still admitted in order,
     * and any pending admitted work keeps running under its own worker-owned notification.
     *
     * Deliberately NOT under [runLock]: a foreground observation waits *outside* the lock, so the
     * user's Continue can always interrupt it promptly instead of serializing behind an admission.
     */
    fun continueLater() {
        scope.launch {
            val context = activeContext ?: return@launch
            context.attempt.value = context.attempt.value.continueLater()
            context.continueEvents.trySend(Unit)
            publish()
        }
    }

    /**
     * Load and reconcile saved setup work (task 3.7): resume pending associations by exact work
     * identity, recover interrupted admissions by request identity plus the recorded pre-enqueue
     * candidate, admit remaining cohort stages only when that is safe, and convert a legacy active
     * marker. Called after account-change recovery at startup and again on Settings entry. Never
     * reopens an explicitly dismissed journey and never auto-retriggers a persisted request.
     * Idempotent.
     */
    fun reconcile() {
        scope.launch {
            runLock.withLock {
                if (activeContext != null) return@withLock
                releaseObserversForUnknownStages()
                migrateLegacyMarkerLocked()
                reloadFromStoreLocked()
                resumeSavedLocked()
                admitRemainingCohortLocked()
            }
        }
    }

    /**
     * Read stored state once, idempotently. Does not start or resume anything by itself —
     * [reconcile] is the recovery entry point; this only makes the surface render stored state.
     */
    fun ensureLoaded() {
        if (_state.value.loaded || loadJob?.isActive == true) return
        loadJob = scope.launch {
            runLock.withLock {
                if (_state.value.loaded) return@withLock
                reloadFromStoreLocked()
                _state.update { it.copy(loaded = true) }
            }
        }
    }

    /**
     * Claim the first-run takeover, as credentials are persisted on a first configuration. Suspends
     * so the caller can advance the flow only once the claim is durable.
     */
    override suspend fun claimFirstRunSetup() = store.setFirstRunSetupActive(true)

    /**
     * Release the takeover when the user explicitly leaves the first-run setup surface. The claim
     * is *kept* while setup work completes or is declined — the first-run history journey stays
     * owed until the phase agent advances it; only this explicit release clears it.
     */
    override suspend fun releaseFirstRunSetup() = store.setFirstRunSetupActive(false)

    // -----------------------------------------------------------------------------------------
    // Run machinery
    // -----------------------------------------------------------------------------------------

    private suspend fun prepareRun(
        requested: Set<String>,
        recordUnselectedAsSkipped: Boolean,
    ): Boolean = runLock.withLock {
        if (activeContext != null) return@withLock false
        val stages = source.stages
        val registeredIds = stages.map { it.id }
        val toRun = stages.filter { it.isAvailable && it.id in requested }
        val runIds = toRun.map { it.id }.toSet()

        stages.forEach { stage ->
            if (stage.id in runIds || recordUnselectedAsSkipped) {
                store.writeOptIn(stage.id, stage.id in runIds)
            }
        }

        if (runIds.isEmpty()) {
            finishImmediately(recordUnselectedAsSkipped, stages)
            return@withLock false
        }

        val marker = currentConfiguredMarker()
        val cohortId = UUID.randomUUID().toString()
        store.setCohort(cohortId, runIds, marker)

        val attempt = foregroundAttempt(runIds, registeredIds)
        val context = RunContext(
            attempt = MutableStateFlow(attempt),
            stages = toRun,
            cohortId = cohortId,
            accountMarker = marker,
        )
        activeContext = context
        _state.update { current ->
            current.copy(
                loaded = true,
                running = true,
                currentStageId = null,
                progress = null,
                selected = runIds,
                inScreenSettled = toRun.none { it.execution == SetupStageExecution.IN_SCREEN },
                finished = false,
                foreground = attempt,
            )
        }
        if (recordUnselectedAsSkipped) {
            // Only genuinely never-attempted stages become Skipped here; an already-recorded
            // terminal sibling (from an earlier run) is preserved.
            stages.filterNot { it.id in runIds }.forEach { stage ->
                val existing = attemptsCache[stage.id]
                if (existing == null || existing.operation == SetupOperationState.NeverRun) {
                    writeSkipped(stage.id)
                }
            }
        }
        true
    }

    private suspend fun writeSkipped(stageId: String) {
        val record = StageAttemptRecord(stageId = stageId, operation = SetupOperationState.Skipped)
        attemptsCache[stageId] = record
        store.upsertAttempt(record)
    }

    private suspend fun prepareRetryRun(stageId: String): Boolean {
        val stage = source.stages.firstOrNull { it.id == stageId && it.isAvailable } ?: return false
        return runLock.withLock {
            // A retry is a whole focused run: refused while any run context lives, so its
            // admission cannot interleave with a run's remaining admissions.
            if (activeContext != null) return@withLock false
            val marker = currentConfiguredMarker()
            val context = RunContext(
                attempt = MutableStateFlow(foregroundAttempt(setOf(stageId), source.stages.map { it.id })),
                stages = listOf(stage),
                cohortId = UUID.randomUUID().toString(),
                accountMarker = marker,
            )
            activeContext = context
            store.setCohort(context.cohortId, setOf(stageId), marker)
            _state.update {
                it.copy(
                    loaded = true,
                    running = true,
                    currentStageId = null,
                    progress = null,
                    selected = setOf(stageId),
                    finished = false,
                    foreground = context.attempt.value,
                )
            }
            true
        }
    }

    /**
     * Drive one run's admission round. Each stage's admission is a short locked section; its
     * foreground observation waits **outside** the lock so Continue can interrupt it, and retries
     * or new runs are refused for the whole round by the [activeContext] guard. The journey claim
     * is deliberately kept: the phase agent decides when the first-run journey is no longer owed.
     */
    private suspend fun finishAdmissionRound() {
        val context = activeContext ?: return
        admitCohort(context)
        runLock.withLock {
            store.clearCohort()
            store.markCompleted()
            activeContext = null
        }
        publish()
    }

    private suspend fun finishImmediately(
        recordUnselectedAsSkipped: Boolean,
        stages: List<SetupStage>,
    ) {
        if (recordUnselectedAsSkipped) {
            stages.forEach { stage ->
                val existing = attemptsCache[stage.id]
                if (existing == null || existing.operation == SetupOperationState.NeverRun) {
                    writeSkipped(stage.id)
                }
            }
        }
        store.clearCohort()
        store.markCompleted()
        _state.update { current ->
            current.copy(
                loaded = true,
                running = false,
                currentStageId = null,
                progress = null,
                selected = emptySet(),
                inScreenSettled = true,
                finished = true,
                foreground = null,
            )
        }
        publish()
    }

    /**
     * Admit [context]'s selected stages in registered order. Every stage is admitted even after an
     * earlier stage settles or the user continues: settlement ends a stage's *own* wait, never the
     * run's admission intent.
     */
    private suspend fun admitCohort(context: RunContext) {
        for (stage in context.stages) {
            if (stage.id !in context.attempt.value.selected) continue
            val finalized = runLock.withLock {
                persistAdmission(stage, context.cohortId, context.accountMarker)
            }
            awaitStageForeground(context, stage, finalized)
            runLock.withLock {
                _state.update { it.copy(currentStageId = null, progress = null) }
                val latest = attemptsCache[stage.id]
                if (latest != null && latest.admittedWorkId != null && latest.operation.isPending) {
                    observeInBackground(stage, latest)
                }
            }
        }
    }

    /**
     * Persist the admission intent *before* enqueue (request id, unique name, and the recorded
     * pre-enqueue [StageAttemptRecord.candidateWorkId] that `KEEP` was expected to retain) and the
     * exact admitted association *after* (design Decision 2). An unestablishable admission becomes
     * [SetupOperationState.RecoveryRequired], never a guessed historical job; without an identified
     * account no intent is ever persisted.
     *
     * The owning account is revalidated against the live account at every side-effect boundary:
     * once **before** enqueue (the intent store and the pre-`KEEP` candidate read both suspend, so
     * the account may flip while they run) and once **after** the admission await, before the
     * association is written. A stale cohort can never admit work for a replacement account, and a
     * late admission result can never claim a stage a replacement now owns: the association is a
     * conditional CAS against the exact intent (generation, no admitted work, owned account), and
     * `callbackLock` serializes every attempt/cache write here with observation callbacks
     * (lock order: run-lock → callback-lock only; callbacks never take the run-lock).
     */
    private suspend fun persistAdmission(
        stage: SetupStage,
        cohortId: String,
        cohortMarker: String?,
    ): StageAttemptRecord {
        // Gate 1: an identified account that still matches the cohort owner. Without one, no
        // intent is persisted and no work is admitted.
        val activeBefore = currentConfiguredMarker()
        val owner = cohortMarker?.takeIf { it == activeBefore }
            ?: return refuseAdmission(stage, cohortId)

        // Durable intent before enqueue.
        val generation = nextGeneration(stage.id)
        val requestId = UUID.randomUUID().toString()
        var intent = StageAttemptRecord(
            stageId = stage.id,
            generation = generation,
            cohortId = cohortId,
            accountMarker = owner,
            requestId = requestId,
            uniqueWorkName = stage.run.uniqueWorkName,
            operation = SetupOperationState.NeverRun,
        )
        callbackLock.withLock {
            store.upsertAttempt(intent)
            attemptsCache[stage.id] = intent
        }
        _state.update { it.copy(currentStageId = stage.id, progress = null) }

        // The pre-KEEP candidate read may suspend (a real WorkManager read, or a test gate). The
        // durable intent already exists; the recorded candidate is patched under the callback lock
        // once known, still before enqueue.
        val candidate = runCatching { stage.run.currentLiveCandidate() }.getOrNull()
        val withCandidate = intent.copy(candidateWorkId = candidate?.toString())
        if (withCandidate != intent) {
            intent = callbackLock.withLock {
                val current = store.attemptRecord(stage.id)
                if (current?.generation == generation && current.admittedWorkId == null &&
                    current.accountMarker == owner
                ) {
                    store.upsertAttempt(withCandidate)
                    attemptsCache[stage.id] = withCandidate
                    withCandidate
                } else {
                    (current ?: withCandidate).also { attemptsCache[stage.id] = it }
                }
            }
        }

        // Gate 2a: the account may have flipped while the intent was persisted / candidate read.
        val activeBeforeEnqueue = currentConfiguredMarker()
        if (activeBeforeEnqueue == null || activeBeforeEnqueue != owner) {
            return supersedeAdmission(stage, intent, "Steam account changed while this step was starting")
        }

        val admission = try {
            stage.run.admit(requestId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            StageAdmission.NeedsRecovery(error.message ?: "Couldn't start this stage")
        }

        // Gate 2b: revalidate after the admission await, before any association is claimed.
        val activeAfter = currentConfiguredMarker()
        if (activeAfter == null || activeAfter != owner) {
            return supersedeAdmission(stage, intent, "Steam account changed while this step was starting")
        }

        val finalized = when (admission) {
            is StageAdmission.Work -> intent.copy(
                admittedWorkId = admission.workId.toString(),
                uniqueWorkName = admission.uniqueWorkName,
                admissionKind = admission.kind,
                operation = admission.initialState,
            )
            is StageAdmission.NeedsRecovery -> intent.copy(
                operation = SetupOperationState.RecoveryRequired(admission.reason),
            )
        }
        return callbackLock.withLock {
            // Association is exact and account-fenced: only the intent this call wrote may be
            // advanced. A replacement (a concurrent generation bump, a foreign-account drop, an
            // interrupted retry) is never overwritten by a late admission result.
            val current = store.attemptRecord(stage.id) ?: finalized
            if (current.generation != generation ||
                current.admittedWorkId != null ||
                current.accountMarker != owner
            ) {
                attemptsCache[stage.id] = current
                publish()
                return@withLock current
            }
            val swapped = store.compareAndSwapAttempt(
                stage.id,
                expectedGeneration = generation,
                expectedAdmittedWorkId = null,
                expectedAccountMarker = owner,
                newRecord = finalized,
            )
            if (swapped) {
                attemptsCache[stage.id] = finalized
            } else {
                attemptsCache[stage.id] = store.attemptRecord(stage.id) ?: finalized
            }
            publish()
            attemptsCache[stage.id] ?: finalized
        }
    }

    /** No identifiable account (or a cohort whose owner is no longer active): no intent, no work. */
    private suspend fun refuseAdmission(stage: SetupStage, cohortId: String): StageAttemptRecord {
        val refused = StageAttemptRecord(
            stageId = stage.id,
            generation = nextGeneration(stage.id),
            cohortId = cohortId,
            operation = SetupOperationState.RecoveryRequired(
                "Connect your Steam account first — every step needs it.",
            ),
            foregroundSettledReason = ForegroundSettledReason.RECOVERY_REQUIRED,
        )
        callbackLock.withLock {
            store.upsertAttempt(refused)
            attemptsCache[stage.id] = refused
        }
        publish()
        return refused
    }

    /**
     * The account changed between intent and admission: no exact association is claimed for the
     * replacement account. The intent's request identity stays recorded (recovery is explicit), but
     * the stage is marked recovery-required via a conditional CAS so a concurrent replacement is
     * never clobbered.
     */
    private suspend fun supersedeAdmission(
        stage: SetupStage,
        intent: StageAttemptRecord,
        reason: String,
    ): StageAttemptRecord = callbackLock.withLock {
        val superseded = intent.copy(
            operation = SetupOperationState.RecoveryRequired(reason),
            foregroundSettledReason = ForegroundSettledReason.RECOVERY_REQUIRED,
        )
        val current = store.attemptRecord(stage.id)
        if (current == null ||
            current.generation != intent.generation ||
            current.admittedWorkId != null ||
            current.accountMarker != intent.accountMarker
        ) {
            val observed = current ?: superseded
            attemptsCache[stage.id] = observed
            publish()
            return@withLock observed
        }
        val swapped = store.compareAndSwapAttempt(
            stage.id,
            expectedGeneration = intent.generation,
            expectedAdmittedWorkId = null,
            expectedAccountMarker = intent.accountMarker,
            newRecord = superseded,
        )
        if (swapped) attemptsCache[stage.id] = superseded
        publish()
        attemptsCache[stage.id] ?: superseded
    }

    /**
     * Settle one stage's own foreground observation (per-stage settlement, design Decision 1 & 2).
     * An in-screen running stage keeps observing for its own bounded budget; a later selected stage
     * is observed even after an earlier stage settled.
     */
    private suspend fun awaitStageForeground(
        context: RunContext,
        stage: SetupStage,
        record: StageAttemptRecord,
    ) {
        if (record.isForegroundSettled) {
            // The stage's wait already concluded (a refused admission, a resolved record); reflect
            // it on the attempt so `running`/`finished` stay truthful even when no observation runs.
            val reason = record.foregroundSettledReason
            if (reason != null) {
                val stepped = context.attempt.value.settleStage(stage.id, reason)
                if (stepped != context.attempt.value) context.attempt.value = stepped
            }
            return
        }
        val detached = stage.execution == SetupStageExecution.DETACHED
        val initialReason = settlementReasonFor(record.operation, detached, record.hasDurableAdmission)
        if (initialReason != null) {
            settleStage(context, stage, record, initialReason)
            return
        }
        observeStageForegroundUntilSettled(context, stage, record)
        if (context.attempt.value.explicitContinued &&
            context.attempt.value.settlementOf(stage.id) == null
        ) {
            // The user continued before this stage settled: its wait ends with CONTINUE — the work
            // itself is untouched and keeps running.
            settleStage(context, stage, record, ForegroundSettledReason.CONTINUE)
        }
    }

    private suspend fun observeStageForegroundUntilSettled(
        context: RunContext,
        stage: SetupStage,
        record: StageAttemptRecord,
    ) {
        val detached = stage.execution == SetupStageExecution.DETACHED
        val startElapsed = clock.elapsedMonotonicMillis()
        val flow = record.admittedWorkId?.let { stage.run.observe(it) } ?: emptyFlow()
        val events = Channel<SetupOperationState>(Channel.CONFLATED)
        val collector = scope.launch {
            try {
                flow.collect { events.send(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                events.send(
                    SetupOperationState.RecoveryRequired(
                        error.message ?: RECOVERY_EXPLANATION,
                    ),
                )
            }
        }
        var lastState: SetupOperationState = record.operation
        try {
            while (context.attempt.value.stageObserved(stage.id)) {
                val live = context.attempt.value
                val remaining = live.observationBudgetMs -
                    (clock.elapsedMonotonicMillis() - startElapsed)
                when (val signal = select<ForegroundSignal> {
                    events.onReceive { ForegroundSignal.State(it) }
                    context.continueEvents.onReceive { ForegroundSignal.Continue }
                    onTimeout(remaining.coerceAtLeast(0L)) { ForegroundSignal.Timeout }
                }) {
                    is ForegroundSignal.State -> {
                        lastState = signal.operation
                        persistIfCurrent(stage.id, record.generation, record.admittedWorkId, signal.operation)
                        if (signal.operation is SetupOperationState.Running &&
                            signal.operation.progress != null
                        ) {
                            _state.update {
                                it.copy(currentStageId = stage.id, progress = signal.operation.progress)
                            }
                        }
                        settlementReasonFor(signal.operation, detached, admissionDurable = true)?.let { reason ->
                            settleStage(context, stage, record, reason)
                        }
                    }
                    ForegroundSignal.Continue -> {
                        settleStage(context, stage, record, ForegroundSettledReason.CONTINUE)
                    }
                    ForegroundSignal.Timeout -> {
                        // Budget expiry settles this stage's wait; it never fails or cancels the work.
                        settleStage(context, stage, record, ForegroundSettledReason.BUDGET_EXPIRED)
                    }
                }
            }
        } finally {
            collector.cancel()
        }
        _state.update { it.copy(currentStageId = null, progress = null) }
    }

    private suspend fun settleStage(
        context: RunContext,
        stage: SetupStage,
        record: StageAttemptRecord,
        reason: ForegroundSettledReason,
    ) {
        val stepped = context.attempt.value.settleStage(stage.id, reason)
        if (stepped != context.attempt.value) context.attempt.value = stepped
        setForegroundSettledReason(record, reason)
    }

    private suspend fun nextGeneration(stageId: String): Long =
        (store.attemptRecord(stageId)?.generation ?: 0L) + 1L

    // -----------------------------------------------------------------------------------------
    // Atomic persistence (design Decision 2; task 3.6 fence)
    // -----------------------------------------------------------------------------------------

    /**
     * Persist [state] only if it still belongs to the stored attempt. The generation/work/account
     * check and the write are one atomic DataStore edit ([SetupStateStore.compareAndSwapAttempt]),
     * so a superseded callback — an old generation, a different exact work, or a different account —
     * can never regress the replacement it raced.
     */
    private suspend fun persistIfCurrent(
        stageId: String,
        generation: Long,
        workId: String?,
        state: SetupOperationState,
    ) = callbackLock.withLock {
        val current = store.attemptRecord(stageId) ?: return@withLock
        if (current.generation != generation) return@withLock
        if (current.admittedWorkId != workId) return@withLock
        if (!writableByStoredAccount(current.accountMarker)) return@withLock
        val updated = current.copy(operation = state)
        val swapped = store.compareAndSwapAttempt(
            stageId,
            current.generation,
            current.admittedWorkId,
            current.accountMarker,
            updated,
        )
        if (swapped) {
            attemptsCache[stageId] = updated
            publish()
        }
    }

    private suspend fun setForegroundSettledReason(
        record: StageAttemptRecord,
        reason: ForegroundSettledReason,
    ) = callbackLock.withLock {
        val current = store.attemptRecord(record.stageId) ?: return@withLock
        if (current.generation != record.generation) return@withLock
        if (!writableByStoredAccount(current.accountMarker)) return@withLock
        val updated = current.copy(foregroundSettledReason = reason)
        val swapped = store.compareAndSwapAttempt(
            record.stageId,
            current.generation,
            current.admittedWorkId,
            current.accountMarker,
            updated,
        )
        if (swapped) {
            attemptsCache[record.stageId] = updated
            publish()
        }
    }

    // -----------------------------------------------------------------------------------------
    // Background reconciliation (tasks 3.4 / 3.6 / 3.7)
    // -----------------------------------------------------------------------------------------

    private suspend fun reloadFromStoreLocked() {
        attemptsCache.clear()
        attemptsCache.putAll(store.storedAttemptRecords())
        legacyCache.clear()
        legacyCache.putAll(store.storedOutcomes())
        _state.update { it.copy(loaded = true) }
        publish()
    }

    /** Release observers whose stage is no longer registered (future-unknown-stage projection). */
    private fun releaseObserversForUnknownStages() {
        val registered = source.stages.map { it.id }.toSet()
        observationJobs.entries.filter { it.key !in registered }
            .forEach { (stageId, observation) ->
                observation.job.cancel()
                observationJobs.remove(stageId)
            }
    }

    /**
     * Convert the pre-3.2 single active-stage marker into versioned attempt records when the
     * association can be established, and into the equivalent cohort so the remaining selected
     * admissions still run. A marker without a job id stays historical until the user's explicit
     * recovery — no arbitrary job is guessed for it.
     */
    private suspend fun migrateLegacyMarkerLocked() {
        val legacy = store.readLegacyActiveMarker() ?: return
        if (legacy.stageId.isNotBlank()) {
            val marker = currentConfiguredMarker()
            val existing = attemptsCache[legacy.stageId]
            if (existing == null) {
                val synth = StageAttemptRecord(
                    stageId = legacy.stageId,
                    generation = 1L,
                    cohortId = "legacy-${legacy.stageId}",
                    accountMarker = marker,
                    admittedWorkId = legacy.workId,
                    uniqueWorkName = legacy.workId?.let { WORK_NAME_UNKNOWN },
                    operation = legacy.workId?.let {
                        SetupOperationState.Waiting("Waiting for a moment to run")
                    } ?: SetupOperationState.NeverRun,
                    foregroundSettledReason = legacy.workId?.let { ForegroundSettledReason.DETACHED },
                )
                store.upsertAttempt(synth)
                attemptsCache[legacy.stageId] = synth
            }
            if (store.cohort().cohortId == null && legacy.selectedStageIds.isNotEmpty()) {
                store.setCohort("legacy-${legacy.stageId}", legacy.selectedStageIds, marker)
            }
        }
        store.consumeLegacyActiveMarker()
    }

    private suspend fun resumeSavedLocked() {
        val snapshots = attemptsCache.values.toList()
        for (record in snapshots) {
            val stage = source.stages.firstOrNull { it.id == record.stageId } ?: continue
            when {
                !visibleOwnership(record) -> Unit // foreign-account record; dropped below
                record.admittedWorkId != null -> {
                    if (record.operation.isPending && writable(record)) observeInBackground(stage, record)
                }
                record.requestId != null && writable(record) ->
                    recoverAdmissionLocked(stage, record, record.candidateWorkId?.let(UUID::fromString))
                else -> Unit // historical terminal record: no live job is fabricated
            }
        }
        attemptsCache.entries.filterNot { visibleOwnership(it.value) }
            .map { it.key }
            .forEach { stageId ->
                observationJobs.remove(stageId)?.job?.cancel()
                store.removeAttempt(stageId)
                attemptsCache.remove(stageId)
            }
        publish()
    }

    /**
     * Recover an interrupted admission by exact identity: the tagged request job first, then the
     * unique-name chain only if it still names the recorded pre-enqueue candidate. No arbitrary
     * historical or untagged live job is ever attached; failure records an explicit recovery
     * request instead of looping, enqueueing a duplicate, or claiming success.
     */
    private suspend fun recoverAdmissionLocked(
        stage: SetupStage,
        record: StageAttemptRecord,
        candidateWorkId: UUID?,
    ) = callbackLock.withLock {
        val requestId = record.requestId ?: return@withLock
        val located = try {
            stage.run.locate(requestId, candidateWorkId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val current = store.attemptRecord(stage.id) ?: return@withLock
        val updated = if (located == null) {
            current.copy(operation = SetupOperationState.RecoveryRequired(RECOVERY_EXPLANATION))
        } else {
            current.copy(
                admittedWorkId = located.workId.toString(),
                uniqueWorkName = current.uniqueWorkName ?: stage.run.uniqueWorkName,
                admissionKind = when (located) {
                    is AdmittedWork.New -> AdmissionKind.NEW
                    is AdmittedWork.Reused -> AdmissionKind.REUSED
                },
            )
        }
        val swapped = store.compareAndSwapAttempt(
            stage.id,
            current.generation,
            current.admittedWorkId,
            current.accountMarker,
            updated,
        )
        if (swapped) {
            attemptsCache[stage.id] = updated
            publish()
            if (updated.admittedWorkId != null && updated.operation.isPending) {
                observeInBackground(stage, updated)
            }
        }
    }

    /**
     * Admit the cohort's remaining selected stages in registered order, **only** when it is safe:
     * an identified account, a cohort owned by that account, and no previously persisted request
     * that never admitted. A persisted request is never auto-retriggered; recovery is the user's
     * explicit action.
     */
    private suspend fun admitRemainingCohortLocked() {
        val cohort = store.cohort()
        val cohortId = cohort.cohortId ?: return
        val state = accountIdentity.state()
        val marker = (state as? SetupAccountState.Configured)?.marker ?: return
        if (cohort.accountMarker != null && cohort.accountMarker != marker) return
        val owner = cohort.accountMarker ?: marker
        for (stage in source.stages) {
            if (!stage.isAvailable || stage.id !in cohort.selectedStageIds) continue
            val record = attemptsCache[stage.id]
            if (record == null || record.cohortId != cohortId) {
                admitStageInBackground(stage, cohortId, owner)
                continue
            }
            if (record.hasDurableAdmission) continue
            if (record.operation.isTerminal) continue
            if (record.cohortId == cohortId && record.requestId != null) continue
            admitStageInBackground(stage, cohortId, owner)
        }
    }

    private suspend fun admitStageInBackground(stage: SetupStage, cohortId: String, marker: String?) {
        val finalized = persistAdmission(stage, cohortId, marker)
        // Background admissions have no foreground wait; the recorded per-stage reason is the
        // worker-owned reporting that follows.
        if (finalized.admittedWorkId != null && finalized.foregroundSettledReason == null) {
            setForegroundSettledReason(finalized, ForegroundSettledReason.DETACHED)
        }
        val latest = attemptsCache[stage.id]
        if (latest != null && latest.admittedWorkId != null && latest.operation.isPending) {
            observeInBackground(stage, latest)
        }
    }

    private suspend fun refreshStageLocked(stage: SetupStage) {
        val record = attemptsCache[stage.id] ?: return
        when {
            record.admittedWorkId == null && record.requestId != null ->
                recoverAdmissionLocked(stage, record, record.candidateWorkId?.let(UUID::fromString))
            record.admittedWorkId != null -> {
                val workId = record.admittedWorkId
                val fresh = try {
                    stage.run.observe(workId).first()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (fresh != null) persistIfCurrent(stage.id, record.generation, workId, fresh)
                if ((attemptsCache[stage.id]?.operation?.isPending) == true) {
                    observeInBackground(stage, attemptsCache[stage.id] ?: return)
                }
            }
            else -> publish()
        }
    }

    /**
     * Start (or keep) the app-scope observation for one admitted stage. Every callback is fenced by
     * attempt generation, exact work identity, and the recorded account (task 3.6) and persisted
     * atomically; an old attempt's or an old-account's completion can never overwrite a replacement
     * attempt. Terminal or recovery states end the observer, and the map entry is removed by the
     * exact stored reference so completed jobs never leak.
     */
    private fun observeInBackground(stage: SetupStage, record: StageAttemptRecord) {
        val workId = record.admittedWorkId ?: return
        val generation = record.generation
        val existing = observationJobs[stage.id]
        if (existing != null && existing.generation == generation && existing.job.isActive) return
        existing?.job?.cancel()
        val job = scope.launch {
            var stop = false
            val self = coroutineContext[Job]
            try {
                stage.run.observe(workId)
                    .onEach { state ->
                        persistIfCurrent(stage.id, generation, workId, state)
                        stop = state.isTerminal || state is SetupOperationState.RecoveryRequired
                        if (stop) removeObserver(stage.id, self)
                    }
                    .takeWhile { !stop }
                    .collect { }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                persistIfCurrent(
                    stage.id,
                    generation,
                    workId,
                    SetupOperationState.RecoveryRequired(
                        error.message ?: RECOVERY_EXPLANATION,
                    ),
                )
                removeObserver(stage.id, self)
            }
        }
        observationJobs[stage.id] = StageObservation(generation, job)
    }

    private fun removeObserver(stageId: String, selfJob: Job?) {
        if (selfJob == null) return
        val holder = observationJobs[stageId]
        if (holder?.job === selfJob) observationJobs.remove(stageId)
    }

    // -----------------------------------------------------------------------------------------
    // Account scope
    // -----------------------------------------------------------------------------------------

    private suspend fun currentConfiguredMarker(): String? =
        (accountIdentity.state() as? SetupAccountState.Configured)?.marker

    /** A record is visible when it predates account ownership or belongs to the active account. */
    private suspend fun visibleOwnership(record: StageAttemptRecord): Boolean =
        record.accountMarker == null || record.accountMarker == currentConfiguredMarker()

    /** A record may be written only by (and for) a concretely identified active account. */
    private suspend fun writable(record: StageAttemptRecord): Boolean {
        if (record.accountMarker == null) return false
        return record.accountMarker == currentConfiguredMarker()
    }

    private suspend fun writableByStoredAccount(marker: String?): Boolean {
        if (marker == null) return false
        return marker == currentConfiguredMarker()
    }

    // -----------------------------------------------------------------------------------------
    // Projection
    // -----------------------------------------------------------------------------------------

    private fun publish() {
        val stages = source.stages
        val registeredIds = stages.map { it.id }
        val attempt = activeContext?.attempt?.value
        _state.update { current ->
            current.copy(
                operations = registeredIds.associateWith { id ->
                    attemptsCache[id]?.operation ?: SetupOperationState.NeverRun
                },
                outcomes = registeredIds.associateWith { id ->
                    attemptsCache[id]?.let { outcomeOfOperation(it.operation) }
                        ?: legacyCache[id]
                        ?: SetupOutcome.NeverRun
                },
                attempts = registeredIds.mapNotNull { id ->
                    attemptsCache[id]?.let { id to it }
                }.toMap(),
                running = attempt?.let { a -> a.isActive && a.selected.any(a::stageObserved) } == true,
                selected = attempt?.selected ?: current.selected,
                foreground = attempt ?: current.foreground,
                finished = if (activeContext != null) attempt?.isSettled == true else current.finished,
                inScreenSettled = inScreenSettledProjection(stages, attempt),
            )
        }
    }

    private fun inScreenSettledProjection(
        stages: List<SetupStage>,
        attempt: ForegroundAttempt?,
    ): Boolean {
        if (attempt == null) return true
        val inScreenIds = stages
            .filter { it.execution == SetupStageExecution.IN_SCREEN }
            .map { it.id }
            .toSet()
        return attempt.selected.intersect(inScreenIds).all { id ->
            val record = attemptsCache[id]
            record?.isForegroundSettled == true || record?.operation?.isTerminal == true
        }
    }

    private companion object {
        /** A legacy active marker carries only a work id, never a known unique work name. */
        const val WORK_NAME_UNKNOWN = "unknown_legacy_work"
    }
}
