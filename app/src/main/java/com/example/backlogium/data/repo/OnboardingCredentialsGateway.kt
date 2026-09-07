package com.example.backlogium.data.repo

/**
 * Narrow view of [CredentialsRepository] for the onboarding flow: the credential operations the
 * flow drives, nothing it merely observes. An interface (mirroring [CredentialsProvider]) so the
 * flow's request-identity guard can be tested on the JVM, since [CredentialsRepository] itself
 * depends on the Android-Keystore-backed `EncryptedCredentialStore` and cannot be constructed
 * off-device.
 */
interface OnboardingCredentialsGateway : CredentialsProvider {
    suspend fun resolveSteamId(input: String, apiKeyOverride: String?): SteamIdResolution

    suspend fun verify(apiKey: String, steamId: String): CredentialVerification

    suspend fun save(apiKey: String, steamId: String): CredentialsSaveResult

    suspend fun refresh(): CredentialsState
}
