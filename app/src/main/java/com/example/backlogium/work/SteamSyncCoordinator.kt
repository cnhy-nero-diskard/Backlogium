package com.example.backlogium.work

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped coordination for Steam sync and achievement reconciliation.
 *
 * The mutex serializes Steam poll and reconciliation operations in this process. It is not the
 * correctness boundary: database commits still re-read their baselines, because WorkManager may
 * run work in another process in a future build and tests must not need this lock to prove that
 * concurrent observations cannot double-count.
 */
@Singleton
class SteamSyncCoordinator @Inject constructor() {

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    private val mutex = Mutex()
}
