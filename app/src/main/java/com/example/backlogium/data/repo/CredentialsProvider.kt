package com.example.backlogium.data.repo

/**
 * Narrow view of [CredentialsRepository] for callers that only need the current, already-resolved
 * credentials — not the full state flow / onboarding surface. An interface (mirroring
 * [SettingsRepository] and [com.example.backlogium.domain.TimeProvider]) so those callers (e.g.
 * [LiveStatusRepository]) can be tested on the JVM, since [CredentialsRepository] itself depends
 * on the Android-Keystore-backed `EncryptedCredentialStore` and cannot be constructed off-device.
 */
interface CredentialsProvider {
    suspend fun currentCredentials(): CredentialsState.Configured?
}
