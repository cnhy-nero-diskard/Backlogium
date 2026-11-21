package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.entity.LocalTimingEvidence
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.LocalPlaytimeSample
import com.example.backlogium.domain.LocalPlaytimeTiming
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persists timing from the existing presence observer; this class never writes play quantity. */
@Singleton
class LocalPlaytimeEvidenceRecorder @Inject constructor(
    private val recovery: PlaytimeRecoveryDao,
    private val games: GameDao,
    private val transaction: DatabaseTransactionScope,
) {
    private val mutex = Mutex()
    // Intentionally not rehydrated: a process restart cannot prove continuity through its absence.
    private var active: LocalTimingEvidence? = null
    private val continuityEpoch = AtomicLong()
    private var activeEpoch = 0L

    /** Non-suspending lifecycle fence: the next sample cannot race an asynchronous stop write. */
    fun invalidateContinuity() { continuityEpoch.incrementAndGet() }

    suspend fun observation(account: String?, appId: Long?, observedAt: Long) = mutex.withLock {
        val prior = active
        val currentGeneration = prior?.generation ?: generation()
        val current = LocalPlaytimeSample(account.orEmpty(), currentGeneration.toString(), appId, observedAt, true)
        val previous = prior?.let {
            LocalPlaytimeSample(it.account, it.generation.toString(), it.appId, it.lastSuccessAt!!, true)
        }
        val epoch = continuityEpoch.get()
        val continuous = activeEpoch == epoch && LocalPlaytimeTiming.span(previous, current) != null
        if (!continuous && prior != null) closeCoverage(prior, observedAt)

        if (account.isNullOrBlank() || appId == null || appId <= 0) {
            active = null
            return@withLock
        }
        val next = if (continuous) {
            prior!!.copy(lastSuccessAt = observedAt, observedUntil = observedAt, updatedAt = observedAt)
        } else {
            LocalTimingEvidence(
                account = account,
                appId = appId,
                generation = generation(),
                firstSampleAt = observedAt,
                lastSuccessAt = observedAt,
                observedUntil = observedAt,
                windowStart = observedAt,
                updatedAt = observedAt,
            )
        }
        active = transaction.run {
            if (recovery.activeAccount() != account || games.getById(appId)?.source != GameSource.STEAM_OWNED) {
                null
            } else {
                recovery.upsertLocalEvidence(next)
                next
            }
        }
        activeEpoch = epoch
    }

    /** A failed request or service stop invalidates the next adjacency, without ending the live timer. */
    suspend fun breakContinuity(observedAt: Long) = mutex.withLock {
        invalidateContinuity()
        val prior = active
        active = null
        if (prior != null) closeCoverage(prior, observedAt)
    }

    private suspend fun closeCoverage(prior: LocalTimingEvidence, observedAt: Long) {
        transaction.run {
            if (recovery.activeAccount() == prior.account) {
                recovery.upsertLocalEvidence(
                    prior.copy(
                        continuityLossFrom = prior.lastSuccessAt,
                        // The retained trustworthy prefix ends before this loss. No gap is bridged.
                        updatedAt = maxOf(prior.updatedAt, observedAt),
                    ),
                )
            }
        }
    }

    private fun generation(): Long = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
}
