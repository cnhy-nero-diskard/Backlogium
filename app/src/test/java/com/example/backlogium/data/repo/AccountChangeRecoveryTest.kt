package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.PendingCredentials
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountChangeRecoveryTest {

    @Test
    fun recoveryCoversTheThreeDurableCrashWindows() {
        assertEquals(
            AccountChangeRecoveryAction.ClearOrphanPending,
            accountChangeRecoveryAction(
                markerSteamId = null,
                pending = PendingCredentials("key", "new-account"),
                activeSteamId = "old-account",
            ),
        )
        assertEquals(
            AccountChangeRecoveryAction.ResumePending,
            accountChangeRecoveryAction(
                markerSteamId = "new-account",
                pending = PendingCredentials("key", "new-account"),
                activeSteamId = "old-account",
            ),
        )
        assertEquals(
            AccountChangeRecoveryAction.ClearCommittedMarker,
            accountChangeRecoveryAction(
                markerSteamId = "new-account",
                pending = null,
                activeSteamId = "new-account",
            ),
        )
    }

    @Test
    fun inconsistentMarkerAndCredentialsAreRejected() {
        assertEquals(
            AccountChangeRecoveryAction.Invalid,
            accountChangeRecoveryAction(
                markerSteamId = "new-account",
                pending = PendingCredentials("key", "other-account"),
                activeSteamId = "old-account",
            ),
        )
        assertEquals(
            AccountChangeRecoveryAction.Invalid,
            accountChangeRecoveryAction(
                markerSteamId = "new-account",
                pending = null,
                activeSteamId = "old-account",
            ),
        )
    }
}
