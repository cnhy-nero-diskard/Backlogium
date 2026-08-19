package com.example.backlogium.data.updates

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateInstallRecoveryTest {
    @Test
    fun unavailableNotificationAbandonsSessionAndMakesInstallRetryable() = runTest {
        val tag = "v1.8.0"
        val state = MutableStateFlow(
            AppUpdateState(installStatus = UpdateInstallStatus.AwaitingUserAction(tag)),
        )
        val installer = RecordingInstaller()

        UpdateInstallRecovery(FakeUpdateStateStore(state), installer)
            .recoverFromUnavailableNotification(tag, 42)

        assertEquals(42, installer.abandonedSessionId)
        assertEquals(
            UpdateInstallStatus.Failed(tag, UPDATE_CONFIRMATION_UNAVAILABLE_MESSAGE),
            state.value.installStatus,
        )
    }

    private class RecordingInstaller : UpdateInstaller {
        var abandonedSessionId: Int? = null

        override fun canRequestPackageInstalls(): Boolean = true
        override fun openInstallPermissionSettings() = Unit
        override fun install(update: AvailableUpdate, artifact: File): UpdateInstallResult =
            UpdateInstallResult.Started

        override fun abandonSession(sessionId: Int) {
            abandonedSessionId = sessionId
        }
    }

    private class FakeUpdateStateStore(
        private val stateFlow: MutableStateFlow<AppUpdateState>,
    ) : UpdateStateStore {
        override val state: Flow<AppUpdateState> = stateFlow

        override suspend fun recordAttempt(atMillis: Long) = Unit
        override suspend fun recordCheck(
            atMillis: Long,
            seenTag: String?,
            available: AvailableUpdate?,
        ) = Unit
        override suspend fun setDeclinedTag(tag: String) = Unit
        override suspend fun clearAvailable() = Unit
        override suspend fun markInstallStarted(tag: String) = Unit
        override suspend fun markInstallPending(tag: String) {
            stateFlow.value = stateFlow.value.copy(installStatus = UpdateInstallStatus.AwaitingUserAction(tag))
        }
        override suspend fun markInstallFailed(tag: String, message: String) {
            stateFlow.value = stateFlow.value.copy(installStatus = UpdateInstallStatus.Failed(tag, message))
        }
        override suspend fun clearInstallStatus() {
            stateFlow.value = stateFlow.value.copy(installStatus = UpdateInstallStatus.Idle)
        }
    }
}
