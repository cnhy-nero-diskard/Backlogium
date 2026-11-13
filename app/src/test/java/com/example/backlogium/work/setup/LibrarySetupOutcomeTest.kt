package com.example.backlogium.work.setup

import androidx.work.Data
import androidx.work.workDataOf
import com.example.backlogium.work.SteamSyncWorker
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySetupOutcomeTest {
    @Test
    fun successfulSnapshotIncludingConfirmedEmptyLibraryHasNoSetupError() {
        assertEquals(SetupOutcome.Succeeded, librarySetupOutcome(Data.EMPTY))
    }

    @Test
    fun privateLibraryAndAdmissionNoOpsCarryTheirOwnAttemptFailure() {
        for (reason in listOf("Library is private", "Account change pending", "Steam not configured")) {
            assertEquals(SetupOutcome.Failed(reason), librarySetupOutcome(
                workDataOf(SteamSyncWorker.KEY_SETUP_FAILURE to reason)))
        }
        // A later result does not consult/reuse a previous attempt's profile error.
        assertEquals(SetupOutcome.Succeeded, librarySetupOutcome(Data.EMPTY))
    }
}
