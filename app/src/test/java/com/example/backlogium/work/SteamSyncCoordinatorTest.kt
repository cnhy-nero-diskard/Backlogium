package com.example.backlogium.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SteamSyncCoordinatorTest {

    @Test
    fun secondOperationWaitsUntilTheFirstReleasesTheProcessLock() = runTest {
        val coordinator = SteamSyncCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = launch {
            assertEquals("first", coordinator.withLock {
                entered.complete(Unit)
                release.await()
                "first"
            })
        }
        entered.await()

        val second = launch {
            assertEquals("second", coordinator.withLock {
                secondEntered.complete(Unit)
                "second"
            })
        }
        assertEquals(false, secondEntered.isCompleted)

        release.complete(Unit)
        first.join()
        second.join()
        assertEquals("third", coordinator.withLock { "third" })
    }
}
