package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.credentials.EncryptedCredentialStore
import com.example.backlogium.data.local.BacklogiumDatabase
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
    private val database: BacklogiumDatabase,
    private val settings: SettingsDataStore,
    private val syncCoordinator: SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val progressTransitions: ProgressTransitionCoordinator,
) {
    /** Start a confirmed account change and finish it, or leave the marker for recovery on error. */
    suspend fun apply(apiKey: String, steamId: String) {
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
        val markerSteamId = markerStore.pendingSteamId() ?: run {
            // A failed attempt before the marker was written leaves only staged credentials.
            credentialStore.clearPending()
            return false
        }

        val pending = credentialStore.readPending()
        if (pending == null) {
            // Crash point 3: the active credentials were promoted, but marker cleanup did not
            // complete. The new identity proves the Room reset has already been completed.
            if (credentialStore.readSteamId() == markerSteamId) {
                markerStore.clear()
                return true
            }
            error("Account-change marker exists without staged credentials")
        }
        require(pending.steamId == markerSteamId) {
            "Account-change marker does not match staged credentials"
        }
        completePendingChange()
        return true
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
                    database.withTransaction {
                        database.sessionDao().deleteAll()
                        database.achievementDao().deleteAll()
                        database.gameGenreCacheDao().deleteAll()
                        database.gameAchievementSyncDao().deleteAll()
                        database.collectionDao().deleteAllMembers()
                        database.collectionDao().deleteAll()
                        database.dailyProgressDao().deleteAll()
                        database.diagnosticsDao().deleteAll()
                        database.gameDao().deleteAll()
                        database.playerProfileDao().insertIfMissing()
                        database.playerProfileDao().resetForAccountChange(markerSteamId)
                    }
                    // Rules and UI preferences survive. Progress-event marks and the live
                    // now-playing session belong to the discarded account and do not.
                    settings.clearAccountDerivedState()
                }
            }
        }

        // DataStore and Room cannot share a transaction. The marker is still present until this
        // promotion succeeds, so a crash in the preceding window repeats the idempotent reset.
        credentialStore.commitPending()
        markerStore.clear()
    }
}
