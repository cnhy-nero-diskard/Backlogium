package com.example.backlogium.work

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped coordination for raw session and daily-progress ledger boundaries, plus the
 * account-change admission barrier that must keep an identity reset ahead of every writer.
 *
 * For raw ledger work, the mutex is an optimization against redundant Steam requests, not the
 * correctness mechanism: database commits still re-read their baselines, because WorkManager may
 * run work in another process in a future build and tests must not need this lock to prove that
 * concurrent observations cannot double-count. The historical backfill shares this boundary so
 * its ledger snapshot cannot race a sync's session and daily-progress commit. The account-change
 * coordinator is the deliberate second use: its durable marker remains set while the reset waits
 * for this process-scoped barrier.
 */
@Singleton
class SteamSyncCoordinator @Inject constructor() {

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    private val mutex = Mutex()
}

/**
 * Runs work after taking the account-change admission check without holding the process mutex
 * across the work itself. The marker check remains a barrier; the long-running caller work does
 * not become a global lock by accident.
 */
internal suspend fun <T> runAfterAccountChangeAdmission(
    coordinator: SteamSyncCoordinator,
    accountChangePending: suspend () -> Boolean,
    work: suspend () -> T,
): T? {
    val pending = coordinator.withLock { accountChangePending() }
    if (pending) return null
    return work()
}
