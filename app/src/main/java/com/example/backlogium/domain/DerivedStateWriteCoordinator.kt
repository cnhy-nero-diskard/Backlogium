package com.example.backlogium.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-scoped lock for rule changes and every versioned derived-state write.
 *
 * DataStore and Room are separate stores, so a version check and a derived write cannot be one
 * database transaction. Serializing the rule-change transaction and the final derived write in
 * the same process closes that cross-store TOCTOU window; the version checks remain as a safe
 * fallback for callers or future work that does not share the lock.
 */
@Singleton
class DerivedStateWriteCoordinator @Inject constructor() {

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    private val mutex = Mutex()
}
