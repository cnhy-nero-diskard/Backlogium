package com.example.backlogium.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamSyncCoordinatorTest {

    @Test
    fun secondPollIsAbsorbedUntilTheFirstReleasesTheProcessLock() = runTest {
        val coordinator = SteamSyncCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val first = launch {
            assertEquals("first", coordinator.tryRun {
                entered.complete(Unit)
                release.await()
                "first"
            })
        }
        entered.await()

        assertNull(coordinator.tryRun { "second" })

        release.complete(Unit)
        first.join()
        assertEquals("third", coordinator.tryRun { "third" })
    }
}
