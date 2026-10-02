package com.example.backlogium.data.history

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DataStoreHistoryImportRequestStoreTest {

    @Before
    fun clearSharedStore() {
        // Robolectric reuses the process, so the DataStore file persists across tests.
        runBlocking { store().clearAll() }
    }

    private fun store() = DataStoreHistoryImportRequestStore(RuntimeEnvironment.getApplication())

    @Test
    fun startsWithNoRequest() = runTest {
        assertNull(store().request())
    }

    @Test
    fun recordsExplicitRequestAndReadsItBack() = runTest {
        val store = store()
        store.recordExplicitRequest(steamId = "76561198000000001", requestedAt = 42L, requestId = "req-1")

        val request = store.request()
        assertEquals("76561198000000001", request?.steamId)
        assertEquals(42L, request?.requestedAt)
        assertEquals("req-1", request?.requestId)
    }

    @Test
    fun reRecordingReplacesThePriorRequest() = runTest {
        val store = store()
        store.recordExplicitRequest("76561198000000001", 10L, "old")
        store.recordExplicitRequest("76561198000000002", 20L, "new")

        val request = store.request()
        assertEquals("76561198000000002", request?.steamId)
        assertEquals("new", request?.requestId)
    }

    @Test
    fun clearDropsTheRecordedConsent() = runTest {
        val store = store()
        store.recordExplicitRequest("76561198000000001", 10L, "req-1")
        store.clear()
        assertNull(store.request())
    }

    @Test
    fun exposedFlowEmitsTheRecordedRequest() = runTest {
        val store = store()
        val emittedBefore = store.requestFlow.first()
        assertNull(emittedBefore)
        store.recordExplicitRequest("76561198000000001", 7L, "req-7")
        val emitted = store.requestFlow.first()
        assertEquals("req-7", emitted?.requestId)
    }

    // ---- durable settle/void/reset-intent bookkeeping (reset invalidation) -----------------

    @Test
    fun settledImportIsRecordedAndReplacedSingleSlot() = runTest {
        val store = store()
        assertNull(store.lastSettledImport())
        store.recordSettledImport("76561198000000001", "req-1")
        store.recordSettledImport("76561198000000001", "req-2")
        val settled = store.lastSettledImport()
        assertEquals("76561198000000001", settled?.steamId)
        assertEquals("req-2", settled?.requestId)
    }

    @Test
    fun resetIntentRoundTripsAndClearsWithoutVoiding() = runTest {
        val store = store()
        assertNull(store.resetIntent())
        store.recordResetIntent("76561198000000001", "req-settled", 99L)
        val intent = store.resetIntent()
        assertEquals("76561198000000001", intent?.steamId)
        assertEquals("req-settled", intent?.voidedRequestId)
        store.clearResetIntent()
        assertNull(store.resetIntent())
        assertFalse(store.isRequestVoided("76561198000000001", "req-settled"))
    }

    @Test
    fun voidedImportIsSingleSlotAndOnlyMatchesAccountAndId() = runTest {
        val store = store()
        assertFalse(store.isRequestVoided("76561198000000001", "req-1"))
        store.recordVoidedImport("76561198000000001", "req-1")
        assertTrue(store.isRequestVoided("76561198000000001", "req-1"))
        assertFalse("a different id is never voided", store.isRequestVoided("76561198000000001", "req-2"))
        assertFalse("a different account is never voided", store.isRequestVoided("76561198000000002", "req-1"))
        // Single slot: a newer void replaces the older one.
        store.recordVoidedImport("76561198000000001", "req-2")
        assertFalse(store.isRequestVoided("76561198000000001", "req-1"))
        assertTrue(store.isRequestVoided("76561198000000001", "req-2"))
    }
}