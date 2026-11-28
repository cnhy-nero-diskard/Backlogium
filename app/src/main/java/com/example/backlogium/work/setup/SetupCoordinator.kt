package com.example.backlogium.work.setup

import com.example.backlogium.data.setup.SetupAccounts
import com.example.backlogium.data.setup.SetupJournal
import com.example.backlogium.data.setup.SetupOwner
import com.example.backlogium.data.setup.SetupStageRecord
import com.example.backlogium.data.setup.SetupStateStore
import com.example.backlogium.di.ApplicationScope
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class SetupRunState(
    val loaded: Boolean = false,
    /** A cohort still has foreground observation or admissions to settle. */
    val running: Boolean = false,
    val currentStageId: String? = null,
    val progress: SetupStageProgress? = null,
    val outcomes: Map<String, SetupOutcome> = emptyMap(),
    val selected: Set<String> = emptySet(),
    val inScreenSettled: Boolean = false,
    /** The cohort has settled. Its admitted workers may still be pending. */
    val finished: Boolean = false,
    val pending: Set<String> = emptySet(),
    val stageProgress: Map<String, SetupStageProgress> = emptyMap(),
    val workIds: Map<String, String> = emptyMap(),
)

@Singleton
class SetupCoordinator @Inject constructor(
    private val source: SetupStageSource,
    private val store: SetupStateStore,
    private val accounts: SetupAccounts,
    @ApplicationScope private val scope: CoroutineScope,
) : FirstRunSetupGateway {
    private val _state = MutableStateFlow(SetupRunState())
    val state: StateFlow<SetupRunState> = _state.asStateFlow()
    private val firstRun = combine(store.firstRunRecordFlow, accounts.owners) { saved, owner ->
        owner?.takeIf { saved?.belongsTo(it) == true }?.let { it to saved!!.phase }
    }
    override val firstRunSetupActive = firstRun.map { it != null }
    override val firstRunPhase = firstRun.map { it?.second }
    override val firstRunOwner = firstRun.map { it?.first }

    @Volatile private var current: AccountSession? = null
    private var loadJob: Job? = null

    @Synchronized
    fun ensureLoaded() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            accounts.owners.collectLatest { owner ->
                _state.value = SetupRunState(loaded = owner == null)
                if (owner == null) return@collectLatest
                supervisorScope {
                    val session = AccountSession(owner, this)
                    if (!session.edit { it }) return@supervisorScope
                    current = session
                    try {
                        session.resume()
                        awaitCancellation()
                    } finally {
                        current = null
                    }
                }
            }
        }
    }

    fun start(selectedIds: Set<String>, recordUnselectedAsSkipped: Boolean = true) {
        val session = current ?: return
        if (_state.value.running || !session.claim()) return
        session.scope.launch { session.start(selectedIds, recordUnselectedAsSkipped) }
    }

    suspend fun skipAll(): Boolean {
        val session = current ?: return false
        if (_state.value.running || !session.claim()) return false
        session.start(emptySet(), recordUnselectedAsSkipped = true)
        return true
    }

    override suspend fun advanceToHistory() {
        val owner = firstRunOwner.first() ?: return
        accounts.ifCurrent(owner) { store.advanceToHistory(owner) }
        current?.takeIf { it.owner == owner }?.continueWaiting()
    }

    override suspend fun claimFirstRunSetup() {
        val owner = accounts.owners.first() ?: return
        accounts.ifCurrent(owner) { store.setFirstRunSetupActive(true, owner) }
    }

    override suspend fun releaseFirstRunSetup() = releaseFirstRunSetup(firstRunOwner.first())

    override suspend fun releaseFirstRunSetup(owner: SetupOwner?) {
        if (owner == null) return
        accounts.ifCurrent(owner) { store.setFirstRunSetupActive(false, owner) }
        current?.takeIf { it.owner == owner }?.continueWaiting()
    }

    private inner class AccountSession(val owner: SetupOwner, val scope: CoroutineScope) {
        private val writes = Mutex()
        private val claimed = AtomicBoolean(false)
        private var exit = CompletableDeferred<Unit>()
        private lateinit var journal: SetupJournal
        private var foregroundStage: String? = null

        fun claim(): Boolean {
            if (!claimed.compareAndSet(false, true)) return false
            exit = CompletableDeferred()
            return true
        }

        fun continueWaiting() {
            exit.complete(Unit)
        }

        suspend fun edit(
            finishRun: Boolean = false,
            transform: (SetupJournal) -> SetupJournal,
        ): Boolean = writes.withLock {
            accounts.ifCurrent(owner) {
                journal = store.update(owner, finishRun, transform)
                publish()
                true
            } ?: false
        }

        fun resume() {
            val restored = journal
            if (restored.runOpen) claim()
            source.stages.filter { it.isAvailable }.forEach { stage ->
                restored.stages[stage.id]?.takeIf { it.pending }?.let { observe(stage, it, restoring = true) }
            }
            if (restored.runOpen) scope.launch {
                try {
                    admitRemaining(restored.remaining)
                } finally {
                    claimed.set(false)
                }
            }
        }

        suspend fun start(selectedIds: Set<String>, recordUnselectedAsSkipped: Boolean) {
            try {
                var remaining = emptyList<String>()
                if (!edit { saved ->
                        remaining = source.stages
                            .filter { it.isAvailable && it.id in selectedIds && saved.stages[it.id]?.pending != true }
                            .map { it.id }
                        val skipped = if (recordUnselectedAsSkipped) {
                            source.stages.filter { it.id !in selectedIds && saved.stages[it.id]?.pending != true }
                                .associate { it.id to SetupStageRecord(encodedOutcome = encodeSetupOutcome(SetupOutcome.Skipped)) }
                        } else emptyMap()
                        saved.copy(
                            stages = saved.stages + skipped,
                            selected = remaining.toSet(),
                            remaining = remaining,
                            runOpen = true,
                            settled = false,
                        )
                    }
                ) return
                admitRemaining(remaining)
            } finally {
                claimed.set(false)
            }
        }

        private suspend fun admitRemaining(remaining: List<String>) {
            for (stage in source.stages.filter { it.isAvailable && it.id in remaining }) {
                val attempt = SetupStageRecord(requestId = UUID.randomUUID().toString())
                if (!edit {
                        foregroundStage = stage.id
                        it.copy(
                            stages = it.stages + (stage.id to attempt),
                            remaining = it.remaining - stage.id,
                        )
                    }
                ) return
                val (admitted, settled) = observe(stage, attempt, restoring = false)
                admitted.await()
                withTimeoutOrNull(FOREGROUND_BUDGET_MILLIS) {
                    select<Unit> {
                        settled.onAwait { }
                        exit.onAwait { }
                    }
                }
            }
            edit(finishRun = true) {
                foregroundStage = null
                it.copy(remaining = emptyList(), runOpen = false, settled = true)
            }
        }

        private fun observe(
            stage: SetupStage,
            attempt: SetupStageRecord,
            restoring: Boolean,
        ): Pair<CompletableDeferred<Unit>, CompletableDeferred<Unit>> {
            val admitted = CompletableDeferred<Unit>()
            val settled = CompletableDeferred<Unit>()
            val requestId = checkNotNull(attempt.requestId)
            val recoveredId = attempt.workId ?: requestId
            var admittedWorkId = attempt.workId

            fun onProgress(progress: SetupStageProgress) {
                scope.launch {
                    writes.withLock {
                        accounts.ifCurrent(owner) {
                            if (journal.stages[stage.id]?.requestId == requestId &&
                                journal.stages[stage.id]?.pending == true
                            ) {
                                _state.update { state ->
                                    state.copy(
                                        stageProgress = state.stageProgress + (stage.id to progress),
                                        progress = if (state.currentStageId == stage.id) progress else state.progress,
                                    )
                                }
                            }
                        }
                    }
                }
                if (progress.activity != SetupStageActivity.RUNNING) settled.complete(Unit)
                if (stage.execution == SetupStageExecution.DETACHED) settled.complete(Unit)
            }

            scope.launch {
                try {
                    val outcome = try {
                        if (restoring) {
                            stage.run.recoverRequest(recoveredId, ::onProgress) { workId ->
                                admittedWorkId = workId
                                edit { it.updateAttempt(stage.id, requestId) { record -> record.copy(workId = workId) } }
                            } ?: SetupOutcome.RecoveryRequired
                        } else {
                            stage.run.runRequest(requestId, owner.generation, ::onProgress) { workId ->
                                admittedWorkId = workId
                                edit { it.updateAttempt(stage.id, requestId) { record -> record.copy(workId = workId) } }
                                admitted.complete(Unit)
                                if (stage.execution == SetupStageExecution.DETACHED) settled.complete(Unit)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        SetupOutcome.Failed(error.message ?: "Didn't finish")
                    }
                    edit { saved ->
                        saved.updateAttempt(stage.id, requestId) {
                            it.copy(workId = admittedWorkId, encodedOutcome = encodeSetupOutcome(outcome))
                        }
                    }
                } finally {
                    admitted.complete(Unit)
                    settled.complete(Unit)
                }
            }
            return admitted to settled
        }

        private fun publish() {
            val ids = source.stages.map { it.id }
            val pending = ids.filterTo(mutableSetOf()) { journal.stages[it]?.pending == true }
            _state.update { state ->
                state.copy(
                    loaded = true,
                    running = journal.runOpen,
                    outcomes = ids.associateWith { journal.stages[it]?.outcome ?: SetupOutcome.NeverRun },
                    selected = journal.selected.intersect(ids.toSet()),
                    pending = pending,
                    workIds = journal.stages.mapNotNull { (id, record) -> record.workId?.let { id to it } }.toMap(),
                    stageProgress = state.stageProgress.filterKeys { it in pending },
                    currentStageId = foregroundStage,
                    progress = state.stageProgress[foregroundStage]?.takeIf { foregroundStage in pending },
                    finished = journal.settled,
                    inScreenSettled = journal.settled,
                )
            }
        }
    }

    private companion object {
        const val FOREGROUND_BUDGET_MILLIS = 120_000L
    }
}

private fun SetupJournal.updateAttempt(
    stageId: String,
    requestId: String,
    transform: (SetupStageRecord) -> SetupStageRecord,
): SetupJournal {
    val current = stages[stageId]?.takeIf { it.pending && it.requestId == requestId } ?: return this
    return copy(stages = stages + (stageId to transform(current)))
}
