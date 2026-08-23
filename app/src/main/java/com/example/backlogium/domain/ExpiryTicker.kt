package com.example.backlogium.domain

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Emits immediately and again when the next supplied expiry deadline arrives.
 *
 * The caller should create this inside [kotlinx.coroutines.flow.flatMapLatest] for the state whose
 * deadline is being watched. When no deadline remains, this waits until that state changes and
 * cancels the collector-scoped timer without polling.
 */
internal fun exactExpiryTicks(
    nowMillis: () -> Long,
    nextExpiryAt: (now: Long) -> Long?,
): Flow<Long> = flow {
    while (currentCoroutineContext().isActive) {
        val now = nowMillis()
        emit(now)
        val expiryAt = nextExpiryAt(now)
        if (expiryAt == null || expiryAt <= now) {
            awaitCancellation()
        } else {
            delay(expiryAt - now)
        }
    }
}
