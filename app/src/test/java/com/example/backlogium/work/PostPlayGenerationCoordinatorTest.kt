package com.example.backlogium.work

import com.example.backlogium.domain.PostPlayGenerations
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ownership guard on its own: which schedule may write, and what a superseded one is allowed to
 * do (nothing). WorkManager is deliberately absent — cancellation is cleanup, and none of the
 * behaviour below depends on it.
 */
class PostPlayGenerationCoordinatorTest {

    @Test
    fun `starting a schedule advances the generation and enqueues under the new one`() = runTest {
        val generations = FakeGenerations()
        val coordinator = PostPlayGenerationCoordinator(generations)
        val enqueued = mutableListOf<Long>()

        coordinator.startSchedule(APP_ID) { enqueued += it }
        coordinator.startSchedule(APP_ID) { enqueued += it }

        assertEquals(listOf(1L, 2L), enqueued)
        assertEquals(2L, generations.current(APP_ID))
    }

    @Test
    fun `the live generation may write, and a superseded one may not`() = runTest {
        val coordinator = PostPlayGenerationCoordinator(FakeGenerations())
        var generation = 0L
        coordinator.startSchedule(APP_ID) { generation = it }

        assertTrue(coordinator.isActive(APP_ID, generation))
        assertEquals("live", coordinator.ifActive(APP_ID, generation) { "live" })

        // A second session end for the same game takes it over.
        coordinator.startSchedule(APP_ID) { }

        assertFalse(coordinator.isActive(APP_ID, generation))
        assertNull(
            "a superseded attempt must neither commit nor enqueue",
            coordinator.ifActive(APP_ID, generation) { "written" },
        )
    }

    @Test
    fun `two games are guarded independently`() = runTest {
        val coordinator = PostPlayGenerationCoordinator(FakeGenerations())
        var first = 0L
        coordinator.startSchedule(APP_ID) { first = it }
        coordinator.startSchedule(OTHER_APP_ID) { }

        // Starting a schedule for one game says nothing about the other's.
        assertTrue(coordinator.isActive(APP_ID, first))
        coordinator.startSchedule(OTHER_APP_ID) { }
        assertTrue(coordinator.isActive(APP_ID, first))
    }

    @Test
    fun `an app with no schedule has no live generation`() = runTest {
        val coordinator = PostPlayGenerationCoordinator(FakeGenerations())

        assertFalse(coordinator.isActive(APP_ID, 1L))
        assertNull(coordinator.ifActive(APP_ID, 1L) { "written" })
    }

    private class FakeGenerations : PostPlayGenerations {
        private val values = mutableMapOf<Long, Long>()
        override suspend fun advance(appId: Long): Long {
            val next = (values[appId] ?: 0L) + 1
            values[appId] = next
            return next
        }

        override suspend fun current(appId: Long): Long = values[appId] ?: 0L
    }

    private companion object {
        const val APP_ID = 440L
        const val OTHER_APP_ID = 570L
    }
}
