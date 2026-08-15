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

    @Test
    fun backfillAndSyncRawBoundariesCannotInterleave() = runTest {
        val coordinator = SteamSyncCoordinator()
        val syncEntered = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val backfillEntered = CompletableDeferred<Unit>()
        val writes = mutableListOf<String>()

        val sync = launch {
            coordinator.withLock {
                writes += "sync-read"
                syncEntered.complete(Unit)
                releaseSync.await()
                writes += "sync-write"
            }
        }
        syncEntered.await()

        val backfill = launch {
            coordinator.withLock {
                writes += "backfill-read"
                backfillEntered.complete(Unit)
                writes += "backfill-write"
            }
        }

        assertEquals(false, backfillEntered.isCompleted)
        releaseSync.complete(Unit)
        sync.join()
        backfill.join()

        assertEquals(
            listOf("sync-read", "sync-write", "backfill-read", "backfill-write"),
            writes,
        )
    }
}
