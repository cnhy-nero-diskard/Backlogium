package com.example.backlogium.data.updates

import javax.inject.Inject
import javax.inject.Singleton

internal const val UPDATE_CONFIRMATION_UNAVAILABLE_MESSAGE =
    "Update confirmation could not be shown. Enable notifications or choose Update again."

@Singleton
class UpdateInstallRecovery @Inject constructor(
    private val updateStateStore: UpdateStateStore,
    private val installer: UpdateInstaller,
) {
    suspend fun recoverFromUnavailableNotification(tag: String, sessionId: Int) {
        if (sessionId >= 0) {
            runCatching { installer.abandonSession(sessionId) }
        }
        if (tag.isBlank()) {
            updateStateStore.clearInstallStatus()
        } else {
            updateStateStore.markInstallFailed(tag, UPDATE_CONFIRMATION_UNAVAILABLE_MESSAGE)
        }
    }
}
