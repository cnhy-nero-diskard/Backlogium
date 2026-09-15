package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.credentials.EncryptedCredentialStore
import com.example.backlogium.data.credentials.PendingCredentials
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.ProgressTransitionCoordinator
import com.example.backlogium.work.SteamSyncCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies and resumes the cross-store account-change protocol from the OpenSpec design:
 * stage encrypted credentials, write the intent marker, clear account-owned Room state in one
 * transaction, promote credentials, then clear the marker. Every step after the marker is
 * idempotent, so an interrupted change can finish on the next process start.
 */
@Singleton
class AccountChangeCoordinator @Inject constructor(
    private val credentialStore: EncryptedCredentialStore,
    private val markerStore: AccountChangeMarkerStore,
    private val roomReset: AccountRoomReset,
    private val settings: SettingsDataStore,
    private val syncCoordinator: SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val progressTransitions: ProgressTransitionCoordinator,
    private val cloudPresence: CloudPresenceRepository,
    private val credentials: OnboardingCredentialsGateway,
) : AccountChangeGateway {
    /** Start a confirmed account change and finish it, or leave the marker for recovery on error. */
    override suspend fun apply(apiKey: String, steamId: String) {
        val normalizedApiKey = apiKey.trim()
        val normalizedSteamId = steamId.trim()
        require(normalizedApiKey.isNotBlank()) { "API key must not be blank" }
        require(normalizedSteamId.isNotBlank()) { "SteamID must not be blank" }

        // The active credentials remain untouched until the Room reset has committed. Staging
        // first is necessary because the marker intentionally contains no plaintext API key.
        credentialStore.stagePending(normalizedApiKey, normalizedSteamId)
        markerStore.markPending(normalizedSteamId)
        completePendingChange()
    }

    /** Resume an incomplete change before scheduling or allowing any new account-bound work. */
    suspend fun resumeIfPending(): Boolean {
        val markerSteamId = markerStore.pendingSteamId()
        val pending = credentialStore.readPending()
        val activeSteamId = if (markerSteamId != null && pending == null) {
            credentialStore.readSteamId()
        } else {
            null
        }
        return when (accountChangeRecoveryAction(markerSteamId, pending, activeSteamId)) {
            AccountChangeRecoveryAction.ClearOrphanPending -> {
                // A failed attempt before the marker was written leaves only staged credentials.
                credentialStore.clearPending()
                false
            }

            AccountChangeRecoveryAction.ResumePending -> {
                completePendingChange()
                true
            }

            AccountChangeRecoveryAction.ClearCommittedMarker -> {
                // Crash point 3: the active credentials were promoted, but marker cleanup did
                // not complete. The new identity proves the Room reset already committed.
                markerStore.clear()
                true
            }

            AccountChangeRecoveryAction.Invalid ->
                error("Account-change marker and staged credentials are inconsistent")
        }
    }

    private suspend fun completePendingChange() {
        val markerSteamId = markerStore.pendingSteamId() ?: return
        val pending = credentialStore.readPending()
            ?: if (credentialStore.readSteamId() == markerSteamId) {
                markerStore.clear()
                return
            } else {
                error("Account-change marker exists without staged credentials")
            }
        require(pending.steamId == markerSteamId) {
            "Account-change marker does not match staged credentials"
        }

        // All writers that can observe or mutate the raw account ledger use these same process
        // locks. The durable marker remains set while waiting, so workers that arrive later skip.
        syncCoordinator.withLock {
            derivedStateWrites.withLock {
                progressTransitions.withTransition {
                    // Fence the whole identity transition under the cloud mutex: the entry bump
                    // discards captures from before the reset, the exit bump discards captures of
                    // old account A made after the reset but before promotion, and no capture or
                    // persistence can interleave between the durable clears and the promotion.
                    // DataStore and Room cannot share a transaction. The marker is still present
                    // until the promotion below succeeds, so a crash in the preceding window
                    // repeats the idempotent reset.
                    cloudPresence.runAccountChangeTransition {
                        roomReset.resetForAccountChange(markerSteamId)
                        // Rules and UI preferences survive. Progress-event marks and the live
                        // now-playing session belong to the discarded account and do not.
                        settings.clearAccountDerivedState()
                        credentialStore.commitPending()
                        // Make the new identity visible to the cloud reader before the
                        // definitive exit invalidation, so a post-switch capture sees B.
                        credentials.refresh()
                    }
                }
            }
        }

        markerStore.clear()
    }
}

/** Recovery branches for the durable marker protocol, kept pure so every crash window is tested. */
internal enum class AccountChangeRecoveryAction {
    ClearOrphanPending,
    ResumePending,
    ClearCommittedMarker,
    Invalid,
}

internal fun accountChangeRecoveryAction(
    markerSteamId: String?,
    pending: PendingCredentials?,
    activeSteamId: String?,
): AccountChangeRecoveryAction = when {
    markerSteamId == null -> AccountChangeRecoveryAction.ClearOrphanPending
    pending?.steamId == markerSteamId -> AccountChangeRecoveryAction.ResumePending
    pending == null && activeSteamId == markerSteamId ->
        AccountChangeRecoveryAction.ClearCommittedMarker
    else -> AccountChangeRecoveryAction.Invalid
}
