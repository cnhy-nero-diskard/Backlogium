package com.example.backlogium.work

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The review chain's scheduling contract: network-constrained, idempotent under `KEEP`, continued
 * with a deliberate delay, and — the point of having a second chain at all — completely
 * independent of the Store genre chain.
 *
 * The independence assertions are not decoration. A shared unique work name would make one
 * chain's `APPEND_OR_REPLACE` continuation silently replace the other's pending batch, which is
 * exactly the coupling a combined worker was rejected to avoid.
 */
@RunWith(RobolectricTestRunner::class)
class ReviewEnrichmentSchedulerTest {

    private lateinit var workManager: WorkManager
    private lateinit var scheduler: ReviewEnrichmentScheduler
    private lateinit var genreScheduler: GenreEnrichmentScheduler

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
        scheduler = ReviewEnrichmentScheduler(context)
        genreScheduler = GenreEnrichmentScheduler(context)
    }

    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    @Test
    fun `ensureEnqueued is idempotent and requires a network`() {
        scheduler.ensureEnqueued()
        val firstId = infos().single().id

        scheduler.ensureEnqueued()

        val infos = infos()
        assertEquals("KEEP must not stack a second request", 1, infos.size)
        assertEquals(firstId, infos.single().id)
        assertEquals(WorkInfo.State.ENQUEUED, infos.single().state)
        assertEquals(
            NetworkType.CONNECTED,
            infos.single().constraints.requiredNetworkType,
        )
    }

    /**
     * The continuation appends a further batch rather than replacing the chain, so a long library
     * drains across runs instead of restarting.
     */
    @Test
    fun `a continuation appends a second batch to the same unique chain`() {
        scheduler.ensureEnqueued()
        val firstId = infos().single().id

        scheduler.enqueueContinuation()

        val infos = infos()
        assertEquals(2, infos.size)
        // Appended, not replaced: the in-flight batch survives.
        assertTrue(infos.any { it.id == firstId })
        // The continuation waits behind its prerequisite rather than running alongside it, which
        // is what keeps the chain sequential and its request spacing meaningful.
        assertEquals(
            setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED),
            infos.map { it.state }.toSet(),
        )
    }

    /**
     * Both chains pull from the same Store host but must never be able to cancel, replace, or
     * starve one another. Distinct unique names are what guarantee it.
     */
    @Test
    fun `the review chain is independent of the store genre chain`() {
        assertTrue(
            ReviewEnrichmentWorker.UNIQUE_WORK_NAME != GenreEnrichmentWorker.UNIQUE_WORK_NAME,
        )

        genreScheduler.ensureEnqueued()
        scheduler.ensureEnqueued()
        // A review continuation must leave the genre chain's pending batch exactly as it was.
        val genreIdBefore = genreInfos().single().id
        scheduler.enqueueContinuation()

        assertEquals(1, genreInfos().size)
        assertEquals(genreIdBefore, genreInfos().single().id)
        assertEquals(2, infos().size)
    }

    private fun infos() = workManager
        .getWorkInfosForUniqueWork(ReviewEnrichmentWorker.UNIQUE_WORK_NAME)
        .get()

    private fun genreInfos() = workManager
        .getWorkInfosForUniqueWork(GenreEnrichmentWorker.UNIQUE_WORK_NAME)
        .get()
}
