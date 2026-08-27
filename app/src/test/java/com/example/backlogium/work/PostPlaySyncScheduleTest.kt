package com.example.backlogium.work

import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.repo.PlaySessionEnd
import com.example.backlogium.data.repo.PlaySessionEndPublisher
import com.example.backlogium.domain.PostPlayGenerations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: PostPlaySyncScheduler
    private lateinit var generations: FakeGenerations
    private lateinit var sessionEnds: PlaySessionEndPublisher
    private lateinit var schedulerScope: CoroutineScope

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        generations = FakeGenerations()
        sessionEnds = PlaySessionEndPublisher()
        schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scheduler = PostPlaySyncScheduler(
            context = context,
            coordinator = PostPlayGenerationCoordinator(generations),
            sessionEnds = sessionEnds,
            scope = schedulerScope,
        )
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

    private fun workInfosFor(appId: Long): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(PostPlaySyncScheduler.uniqueWorkName(appId)).get()

    private class FakeGenerations : PostPlayGenerations {
        private val values = mutableMapOf<Long, Long>()
        override suspend fun advance(appId: Long): Long {
            val next = (values[appId] ?: 0L) + 1
            values[appId] = next
            return next
        }

        override suspend fun current(appId: Long): Long = values[appId] ?: 0L
    }
}
