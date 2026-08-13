package com.example.backlogium.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single process-wide serialization point for the progress-event persist/recovery protocol.
 *
 * The protocol spans two storage engines that share no transaction: the pending-transition
 * write-ahead record and the finalized marks live in DataStore, the derived values they describe
 * live in Room. Between the write-ahead record and the finalize, the two deliberately describe
 * different logical versions of the same state — which is exactly what makes recovery possible
 * after a crash, and exactly what makes a *second* concurrent participant dangerous:
 *
 * - a recovery pass running while a `persist()` is between its WAL and its finalize would resolve a
 *   transition that is not abandoned at all, clearing the live call's WAL and consuming its
 *   transition on its behalf against a half-written Room state;
 * - two overlapping `persist()` calls would each capture "previous state" from a Room row the other
 *   is mid-way through replacing, and each would clear a WAL record written by the other — so one
 *   provenance's recovery state can be attributed to, or destroyed by, the other's.
 *
 * Neither is fixable by ordering the individual atomic writes more carefully: the hazard is the
 * *interleaving of the protocol's phases*, so the protocol as a whole is what has to be serialized.
 * Every path that mutates or resolves transition recovery state acquires this coordinator for the
 * duration of its critical section, so "a pending transition exists" always means either "this
 * coroutine put it there" or "the process died holding it".
 *
 * Injected as a singleton: one instance per process is the whole point, and two instances are
 * indistinguishable from no coordination at all. Callers that construct the pipeline by hand (tests
 * and direct [GamificationUpdater] construction) must pass the *same* instance to every participant
 * for the guarantee to hold.
 *
 * [withTransition] is **not reentrant**. A body that needs a step which itself acquires the
 * coordinator must call that step's `…WithinProtocol` variant instead of its public wrapper.
 */
@Singleton
class ProgressTransitionCoordinator @Inject constructor() {
    private val mutex = Mutex()

    /** Run [block] with exclusive ownership of the persist/recovery protocol. */
    suspend fun <T> withTransition(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Whether some coroutine currently owns the protocol. For diagnostics and tests only —
     * a false result is stale the moment it is returned, so it can never be used to decide
     * whether entering the protocol is safe.
     */
    val isBusy: Boolean get() = mutex.isLocked
}
