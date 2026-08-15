package com.example.backlogium.work

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped coordination for Steam sync, achievement reconciliation, and historical
 * daily-progress correction.
 *
 * The mutex serializes operations that read or write the raw session/daily-progress ledger in this
 * process. Database commits still re-read their baselines, because WorkManager may run work in
 * another process in a future build and tests must not need this lock to prove that concurrent
 * observations cannot double-count. The historical backfill shares this boundary so its ledger
 * snapshot cannot race a sync's session and daily-progress commit.
 */
@Singleton
class SteamSyncCoordinator @Inject constructor() {

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    private val mutex = Mutex()
}
