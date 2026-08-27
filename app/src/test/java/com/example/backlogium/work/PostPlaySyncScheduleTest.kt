package com.example.backlogium.work

import android.content.Context
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.repo.PlaySessionEnd
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.data.repo.SessionEndOutbox
import com.example.backlogium.domain.PostPlayGenerations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

/**
 * Covers the post-play schedule's shape: the attempt timings, the two enqueue policies, and what a
 * second session end does to a schedule already pending.
 *
 * Uses a real [WorkManager] backed by [SynchronousExecutor] rather than asserting on calls into a
 * mock. No attempt ever runs: every request carries a network constraint that
 * [WorkManagerTestInitHelper]'s default trackers do not satisfy, so each one stays `ENQUEUED` (or
 * `BLOCKED` behind its prerequisite) — which is exactly the state these assertions need, since the
 * difference between the two policies is visible in whether a *predecessor* survives.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PostPlaySyncScheduleTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: PostPlaySyncScheduler
    private lateinit var generations: FakeGenerations
    private lateinit var sessionEnds: PlaySessionEndPublisher
    private lateinit var sessionEndOutbox: FakeSessionEndOutbox
    private lateinit var schedulerScope: CoroutineScope

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        generations = FakeGenerations()
        sessionEnds = PlaySessionEndPublisher()
        sessionEndOutbox = FakeSessionEndOutbox()
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = newScheduler(WorkManagerPostPlayWorkEnqueuer(context))
    }

    @After
    fun tearDown() {
        schedulerScope.cancel()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    /**
     * The regression this guards is chaining the offsets as if they were delays: `1m, 3m, 8m` as
     * delays would land attempts at T+0, T+1m, T+4m, T+12m instead of T+0, T+1m, T+3m, T+8m.
     */
    @Test
    fun `attempt delays are the gaps between offsets, not the offsets themselves`() {
        assertEquals(
            listOf(0L, TimeUnit.MINUTES.toMillis(1), TimeUnit.MINUTES.toMillis(3), TimeUnit.MINUTES.toMillis(8)),
            PostPlaySyncScheduler.ATTEMPT_OFFSETS_MILLIS,
        )
        assertEquals(TimeUnit.MINUTES.toMillis(1), PostPlaySyncScheduler.delayBefore(1))
        assertEquals(TimeUnit.MINUTES.toMillis(2), PostPlaySyncScheduler.delayBefore(2))
        assertEquals(TimeUnit.MINUTES.toMillis(5), PostPlaySyncScheduler.delayBefore(3))

        // The delays must accumulate back into the offsets, or the schedule drifts.
        var elapsed = 0L
        PostPlaySyncScheduler.ATTEMPT_OFFSETS_MILLIS.indices.drop(1).forEach { attempt ->
            elapsed += PostPlaySyncScheduler.delayBefore(attempt)
            assertEquals(PostPlaySyncScheduler.ATTEMPT_OFFSETS_MILLIS[attempt], elapsed)
        }
    }

    @Test
    fun `only the last attempt ends the schedule`() {
        assertEquals(4, PostPlaySyncScheduler.ATTEMPT_COUNT)
        assertTrue(!PostPlaySyncScheduler.isLastAttempt(0))
        assertTrue(!PostPlaySyncScheduler.isLastAttempt(2))
        assertTrue(PostPlaySyncScheduler.isLastAttempt(3))
    }

    @Test
    fun `a session end enqueues exactly one attempt under the game's own name`() = runTest {
        scheduler.schedule(PlaySessionEnd(appId = 440L, endedAt = 1_000L))

        val infos = workInfosFor(440L)
        assertEquals("one attempt at a time — never all four up front", 1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(1L, generations.current(440L))
    }

    /**
     * `REPLACE` cancels all unfinished work under a name, and a running worker is unfinished — so a
     * successor enqueued with it would cancel the very attempt that enqueued it. This asserts the
     * predecessor survives, which fails if the successor's policy is `REPLACE`.
     */
    @Test
    fun `a successor is appended without cancelling its predecessor`() = runTest {
        scheduler.schedule(PlaySessionEnd(appId = 440L, endedAt = 1_000L))
        val predecessor = workInfosFor(440L).single()

        scheduler.enqueueSuccessor(
            appId = 440L,
            attempt = 0,
            sessionEndAt = 1_000L,
            generation = 1L,
            steamId = "account-a",
        )

        val infos = workInfosFor(440L)
        assertEquals("the successor joins the chain rather than replacing it", 2, infos.size)
        val stillThere = infos.single { it.id == predecessor.id }
        assertNotEquals(
            "a successor must never cancel the attempt that enqueued it",
            WorkInfo.State.CANCELLED,
            stillThere.state,
        )
    }

    @Test
    fun `a second session end for the same game replaces the pending schedule rather than doubling it`() = runTest {
        scheduler.schedule(PlaySessionEnd(appId = 440L, endedAt = 1_000L))
        val first = workInfosFor(440L).single()

        scheduler.schedule(PlaySessionEnd(appId = 440L, endedAt = 500_000L))

        val infos = workInfosFor(440L)
        assertTrue(
            "the earlier schedule is no longer live alongside the replacement",
            infos.none {
                it.id == first.id &&
                    it.state in setOf(
                        WorkInfo.State.ENQUEUED,
                        WorkInfo.State.RUNNING,
                        WorkInfo.State.BLOCKED,
                    )
            },
        )
        assertEquals(
            "exactly one live attempt remains",
            1,
            infos.count { it.state == WorkInfo.State.ENQUEUED },
        )
        assertEquals("the new schedule owns a newer generation", 2L, generations.current(440L))
    }

    @Test
    fun `two games stopped in the same window keep independent schedules`() = runTest {
        scheduler.schedule(PlaySessionEnd(appId = 440L, endedAt = 1_000L))
        scheduler.schedule(PlaySessionEnd(appId = 570L, endedAt = 2_000L))

        assertEquals(1, workInfosFor(440L).count { it.state == WorkInfo.State.ENQUEUED })
        assertEquals(1, workInfosFor(570L).count { it.state == WorkInfo.State.ENQUEUED })
        assertNotEquals(
            PostPlaySyncScheduler.uniqueWorkName(440L),
            PostPlaySyncScheduler.uniqueWorkName(570L),
        )
        assertEquals(1L, generations.current(440L))
        assertEquals(1L, generations.current(570L))
    }

    @Test
    fun `a published session end is acted on by the subscription`() = runTest {
        scheduler.observeSessionEnds()

        sessionEnds.publish(PlaySessionEnd(appId = 440L, endedAt = 1_000L))

        assertEquals(1, workInfosFor(440L).size)
        assertEquals(1L, generations.current(440L))
    }

    @Test
    fun persistedSessionEndIsDrainedWithoutAHotEvent() = runTest {
        val event = PlaySessionEnd(appId = 440L, endedAt = 1_000L, steamId = "account-a")
        sessionEndOutbox.pending.value = listOf(event)

        scheduler.observeSessionEnds()
        runCurrent()

        assertEquals(1, workInfosFor(440L).size)
        assertEquals(1L, generations.current(440L))
        assertTrue(sessionEndOutbox.pending.value.isEmpty())
    }

    @Test
    fun `outbox stays pending until the WorkManager enqueue operation completes`() = runTest {
        val delayed = DelayedWorkEnqueuer(WorkManagerPostPlayWorkEnqueuer(context))
        scheduler = newScheduler(delayed)
        val event = PlaySessionEnd(appId = 440L, endedAt = 1_000L, steamId = "account-a")
        sessionEndOutbox.pending.value = listOf(event)

        scheduler.observeSessionEnds()
        runCurrent()

        assertTrue(delayed.enqueueStarted)
        assertEquals(listOf(event), sessionEndOutbox.pending.value)
        assertEquals(1, workInfosFor(440L).size)

        delayed.complete()
        runCurrent()

        assertTrue(sessionEndOutbox.pending.value.isEmpty())
    }

    private fun workInfosFor(appId: Long): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(PostPlaySyncScheduler.uniqueWorkName(appId)).get()

    private fun newScheduler(workEnqueuer: PostPlayWorkEnqueuer): PostPlaySyncScheduler =
        PostPlaySyncScheduler(
            coordinator = PostPlayGenerationCoordinator(generations),
            sessionEnds = sessionEnds,
            sessionEndOutbox = sessionEndOutbox,
            workEnqueuer = workEnqueuer,
            scope = schedulerScope,
        )

    private class FakeGenerations : PostPlayGenerations {
        private val values = mutableMapOf<Long, Long>()
        override suspend fun advance(appId: Long): Long {
            val next = (values[appId] ?: 0L) + 1
            values[appId] = next
            return next
        }

        override suspend fun current(appId: Long): Long = values[appId] ?: 0L
    }

    private class FakeSessionEndOutbox : SessionEndOutbox {
        val pending = MutableStateFlow<List<PlaySessionEnd>>(emptyList())
        override val pendingSessionEnds = pending

        override suspend fun recordSessionEnd(
            sessionEnd: PlaySessionEnd,
            nextLiveSession: LiveSessionState,
        ) {
            pending.value = (pending.value + sessionEnd).distinct()
        }

        override suspend fun acknowledgeSessionEnd(sessionEnd: PlaySessionEnd) {
            pending.value = pending.value.filterNot { it == sessionEnd }
        }
    }

    private class DelayedWorkEnqueuer(
        private val delegate: PostPlayWorkEnqueuer,
    ) : PostPlayWorkEnqueuer {
        private val completion = SettableFuture.create<Operation.State.SUCCESS>()
        private var delegateOperation: Operation? = null
        var enqueueStarted = false
            private set

        override fun enqueue(
            uniqueWorkName: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ): Operation {
            enqueueStarted = true
            val operation = delegate.enqueue(uniqueWorkName, policy, request)
            delegateOperation = operation
            return object : Operation {
                override fun getState() = operation.getState()

                override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = completion
            }
        }

        fun complete() {
            completion.set(checkNotNull(delegateOperation).getResult().get())
        }
    }
}
