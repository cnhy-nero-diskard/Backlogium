package com.example.backlogium.work

import com.example.backlogium.domain.PostPlayGenerations
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which post-play schedule owns an app, and serializes every operation that depends on
 * that answer: advancing to a new generation, committing an observation, and appending the next
 * attempt.
 *
 * WorkManager cancellation cannot be the guard. `ExistingWorkPolicy.REPLACE` cancels *unfinished*
 * work, and cancellation is cooperative: a running worker can keep executing while its coroutine
 * unwinds. An attempt of a superseded schedule that checked only "was I cancelled" could therefore
 * still commit playtime under an old session-end time, or append itself into the schedule that
 * replaced it. Cancellation here is cleanup; the generation is correctness.
 *
 * The critical sections are deliberately narrow — network requests happen outside them — but each
 * one holds the lock across *both* the generation read and the mutation it guards. A replacement
 * therefore linearizes either before an old attempt's mutation (making that attempt a no-op) or
 * after it (in which case the mutation belonged to the then-active schedule, and the replacement
 * cancels what remains). There is no window in which a stale attempt passes its check and then
 * writes.
 *
 * The lock is per app id, so two games' schedules never wait on each other. It serializes only
 * within this process; the generation is persisted, which is what makes the guard hold across
 * process death.
 */
@Singleton
class PostPlayGenerationCoordinator @Inject constructor(
    private val generations: PostPlayGenerations,
) {
    private val locks = ConcurrentHashMap<Long, Mutex>()

    /**
     * Start a new schedule for [appId]: advance its generation and enqueue the schedule's first
     * attempt under the new one, both while holding the app's lock.
     *
     * [enqueue] runs inside the critical section on purpose. Advancing without enqueueing under the
     * same lock would let a concurrent attempt of the *previous* generation read the new value,
     * find itself stale, and stop — while the new schedule's first attempt has not been enqueued
     * yet, so nothing is left to collect the session.
     */
    suspend fun startSchedule(appId: Long, enqueue: suspend (generation: Long) -> Unit) {
        lockFor(appId).withLock {
            enqueue(generations.advance(appId))
        }
    }

    /**
     * Run [block] only while [generation] is still the live schedule for [appId].
     *
     * @return [block]'s value, or null when the attempt has been superseded — the caller's signal
     *   to end as a successful no-op, committing nothing and enqueueing nothing.
     */
    suspend fun <T> ifActive(appId: Long, generation: Long, block: suspend () -> T): T? =
        lockFor(appId).withLock {
            if (generations.current(appId) != generation) null else block()
        }

    /**
     * A cheap read with no lock held, for the early exit before an attempt spends a request. Never
     * a substitute for [ifActive]: a generation that is live here can be superseded while the
     * fetch is in flight, which is precisely the race the guarded sections exist for.
     */
    suspend fun isActive(appId: Long, generation: Long): Boolean =
        generations.current(appId) == generation

    private fun lockFor(appId: Long): Mutex = locks.computeIfAbsent(appId) { Mutex() }
}
