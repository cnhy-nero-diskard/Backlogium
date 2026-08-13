package com.example.backlogium.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ProgressMarksStore.update] is the mechanism `persist()`'s finalize and `acknowledge()` both
 * rely on to avoid a stale-snapshot read-modify-write race. This hammers it directly: many
 * concurrent increments, each derived solely from the transform's live argument, must all land —
 * a lost update would show up as a final count lower than the number of increments issued.
 */
class ProgressMarksStoreTest {
    @Test
    fun updateLosesNoConcurrentWrites() = runBlocking {
        val store = InMemoryProgressMarksStore()
        val increments = 500

        coroutineScope {
            val jobs = (1..increments).map {
                async(Dispatchers.Default) {
                    store.update { it.copy(lastCelebratedLevel = it.lastCelebratedLevel + 1) }
                }
            }
            jobs.forEach { it.await() }
        }

        assertEquals(increments, store.read().lastCelebratedLevel)
    }
}
