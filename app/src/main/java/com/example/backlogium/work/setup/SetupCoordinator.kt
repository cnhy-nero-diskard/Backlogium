package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything a setup surface needs to render, derived from the registry plus stored state.
 *
 * Finished stages keep their outcomes in [outcomes] while later ones run, which is what lets the
 * surface show the whole run rather than only its current step.
 */
data class SetupRunState(
    /** False until stored outcomes have been read; the surface shows nothing definite before that. */
    val loaded: Boolean = false,
    val running: Boolean = false,
    /** The stage currently being observed, or null between stages and when idle. */
    val currentStageId: String? = null,
    /** The current stage's progress, or null when it has not published a usable one. */
    val progress: SetupStageProgress? = null,
    val outcomes: Map<String, SetupOutcome> = emptyMap(),
    /** What the run covers. Persisted, so a surface recreated in a new process still knows. */
    val selected: Set<String> = emptySet(),
    /**
     * True once every selected in-screen stage has settled. The surface offers entry into the app
     * from this point — detached stages keep running and reporting in their own notifications.
     */
    val inScreenSettled: Boolean = false,
    /** True once every selected stage has reached a terminal outcome, or setup was declined. */
    val finished: Boolean = false,
)

/**
 * Runs the selected setup stages in registered order and records what each one did.
 *
 * A singleton on the application scope, deliberately: the setup surface can be recreated — rotated,
 * navigated away from, re-entered — without restarting the run or losing the stage it was on, and a
 * detached stage's observation is not tied to a composition that no longer exists.
 *
 * Failure is isolated by construction. Each stage's runner produces its own terminal outcome, the
 * loop records it and continues, and setup completes reporting the per-stage summary. It never
 * cancels a sibling and never discards a result: the stages are unrelated, so aborting a
 * nearly-complete artwork download because a different service timed out would throw away real work
 * for no reason.
 */
@Singleton
class SetupCoordinator @Inject constructor(
    private val source: SetupStageSource,
    private val store: SetupStateStore,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(SetupRunState())
    val state: StateFlow<SetupRunState> = _state.asStateFlow()

    /** Serializes runs so two surfaces cannot drive the loop at the same time. */
    private val runLock = Mutex()
    private var loadJob: Job? = null

    /** Read stored outcomes and opt-ins once, so a surface opened later starts from the truth. */
    fun ensureLoaded() {
        if (_state.value.loaded || loadJob?.isActive == true) return
        loadJob = scope.launch {
            val registeredIds = source.stages.map { it.id }
            val outcomes = projectSetupOutcomes(store.storedOutcomes(), registeredIds)
            val optIns = store.storedOptIns()
            _state.update { current ->
                if (current.loaded) {
                    current
                } else {
                    current.copy(
                        loaded = true,
                        outcomes = outcomes,
                        selected = registeredIds.filter { optIns[it] == true }.toSet(),
                    )
                }
            }
        }
    }

    /**
     * Start [selectedIds], in registered order, skipping everything else.
     *
     * Nothing selected is a complete answer, not an error: setup finishes immediately with every
     * stage recorded as skipped, which is also exactly what declining does.
     */
    fun start(selectedIds: Set<String>) {
        scope.launch {
            runLock.withLock {
                val stages = source.stages
                // An unavailable stage cannot be selected in any surface, but a stale selection
                // handed in from a recreated one must not slip past that.
                val toRun = stages.filter { it.isAvailable && it.id in selectedIds }
                val runIds = toRun.map { it.id }.toSet()

                stages.forEach { store.writeOptIn(it.id, it.id in runIds) }
                _state.update {
                    it.copy(
                        loaded = true,
                        running = runIds.isNotEmpty(),
                        selected = runIds,
                        finished = false,
                        currentStageId = null,
                        progress = null,
                        // Anything not in this run is skipped *for this run*; the stages that are in
                        // it start from a clean slate rather than showing a previous verdict.
                        outcomes = stages.associate { stage ->
                            stage.id to if (stage.id in runIds) {
                                SetupOutcome.NeverRun
                            } else {
                                SetupOutcome.Skipped
                            }
                        },
                        inScreenSettled = toRun.none {
                            it.execution == SetupStageExecution.IN_SCREEN
                        },
                    )
                }
                stages.filterNot { it.id in runIds }
                    .forEach { store.writeOutcome(it.id, SetupOutcome.Skipped) }

                toRun.forEach { stage -> runStage(stage, remaining = toRun) }

                store.markCompleted()
                _state.update {
                    it.copy(
                        running = false,
                        currentStageId = null,
                        progress = null,
                        inScreenSettled = true,
                        finished = true,
                    )
                }
            }
        }
    }

    /** Decline setup: nothing runs, every stage is recorded skipped, and the app is fully usable. */
    fun skipAll() = start(emptySet())

    private suspend fun runStage(stage: SetupStage, remaining: List<SetupStage>) {
        _state.update { it.copy(currentStageId = stage.id, progress = null) }
        val outcome = try {
            stage.run.run { progress ->
                _state.update { current ->
                    // A late progress callback from a stage we have already moved past must not
                    // overwrite the current one's bar.
                    if (current.currentStageId == stage.id) current.copy(progress = progress) else current
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A runner that throws is this stage's failure and nothing else's — the loop below
            // still reaches every remaining stage.
            SetupOutcome.Failed(error.message ?: "Didn't finish")
        }
        store.writeOutcome(stage.id, outcome)
        _state.update { current ->
            val outcomes = current.outcomes + (stage.id to outcome)
            current.copy(
                outcomes = outcomes,
                progress = null,
                inScreenSettled = remaining
                    .filter { it.execution == SetupStageExecution.IN_SCREEN }
                    .all { outcomes[it.id] !is SetupOutcome.NeverRun },
            )
        }
    }
}
