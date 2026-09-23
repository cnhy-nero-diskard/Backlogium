package com.example.backlogium.ui.settings

/** Attributes a sync failure to the manual Settings action that initiated that sync. */
internal class ManualSyncFeedbackTracker {
    private var manualSyncAttempt = false
    private var manualSyncInFlight = false

    fun onManualSyncStarted() {
        manualSyncAttempt = true
        manualSyncInFlight = false
    }

    /** Returns true once when an attributed sync completes with an error. */
    fun onSyncStateChanged(isSyncing: Boolean, lastSyncError: String?): Boolean {
        if (isSyncing && manualSyncAttempt) {
            manualSyncInFlight = true
        } else if (!isSyncing && manualSyncAttempt && manualSyncInFlight) {
            manualSyncAttempt = false
            manualSyncInFlight = false
            return lastSyncError != null
        }
        return false
    }
}
