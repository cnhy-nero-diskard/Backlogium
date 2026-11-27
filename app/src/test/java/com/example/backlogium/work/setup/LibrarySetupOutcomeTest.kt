package com.example.backlogium.work.setup

import androidx.work.Data
import androidx.work.workDataOf
import com.example.backlogium.work.SteamSyncWorker
import com.example.backlogium.work.OP_PARTIAL
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySetupOutcomeTest {
    private val committed = workDataOf(
        SteamSyncWorker.KEY_POLL_RESULT to SteamSyncWorker.POLL_COMMITTED,
        SteamSyncWorker.KEY_POLL_OPERATION to "poll",
    )

    @Test
    fun schedulerSuccessAloneDoesNotConfirmTheLibrary() {
        assertEquals(
            SetupOutcome.Failed("Library sync did not confirm a snapshot. Retry this step."),
            librarySetupOutcome(Data.EMPTY),
        )
    }

    @Test
    fun successfulSnapshotIncludingConfirmedEmptyLibraryHasNoSetupError() {
        assertEquals(SetupOutcome.Succeeded, librarySetupOutcome(committed))
    }

    @Test
    fun committedPartialRefreshIsDistinctFromFailureAndFullSuccess() {
        assertEquals(
            SetupOutcome.Partial,
            librarySetupOutcome(Data.Builder().putAll(committed).putBoolean(OP_PARTIAL, true).build()),
        )
    }

    @Test
    fun privateLibraryAndAdmissionNoOpsCarryTheirOwnAttemptFailure() {
        for (reason in listOf("Library is private", "Account change pending", "Steam not configured")) {
            assertEquals(SetupOutcome.Failed(reason), librarySetupOutcome(
                workDataOf(SteamSyncWorker.KEY_SETUP_FAILURE to reason)))
        }
        // A later result does not consult/reuse a previous attempt's profile error.
        assertEquals(SetupOutcome.Succeeded, librarySetupOutcome(committed))
    }
}
