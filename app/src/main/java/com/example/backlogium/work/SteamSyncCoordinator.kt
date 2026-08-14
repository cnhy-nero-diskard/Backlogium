package com.example.backlogium.work

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Process-scoped coordination for Steam sync and achievement reconciliation.
 *
 * The mutex prevents redundant remote work while a poll or reconciliation is active. It is not
 * the correctness boundary: database commits still re-read their baselines, because WorkManager
 * may run work in another process in a future build and tests must not need this lock to prove
 * that concurrent observations cannot double-count.
 */
@Singleton
class SteamSyncCoordinator @Inject constructor() {

    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    private val mutex = Mutex()
}
