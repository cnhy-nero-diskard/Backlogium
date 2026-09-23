package com.example.backlogium.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualSyncFeedbackTrackerTest {
    @Test
    fun manualSyncFailureIsAttributedOnce() {
        val tracker = ManualSyncFeedbackTracker()

        tracker.onManualSyncStarted()

        assertFalse(tracker.onSyncStateChanged(isSyncing = true, lastSyncError = null))
        assertTrue(tracker.onSyncStateChanged(isSyncing = false, lastSyncError = "offline"))
        assertFalse(tracker.onSyncStateChanged(isSyncing = false, lastSyncError = "offline"))
    }

    @Test
    fun backgroundFailureAndSuccessfulManualSyncDoNotReject() {
        val tracker = ManualSyncFeedbackTracker()

        assertFalse(tracker.onSyncStateChanged(isSyncing = true, lastSyncError = null))
        assertFalse(tracker.onSyncStateChanged(isSyncing = false, lastSyncError = "offline"))

        tracker.onManualSyncStarted()
        assertFalse(tracker.onSyncStateChanged(isSyncing = true, lastSyncError = null))
        assertFalse(tracker.onSyncStateChanged(isSyncing = false, lastSyncError = null))
    }
}
