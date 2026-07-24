package com.example.backlogium.data.repo

import com.example.backlogium.BuildConfig
import com.example.backlogium.data.credentials.EncryptedCredentialStore
import com.example.backlogium.data.credentials.SteamIdInput
import com.example.backlogium.data.remote.SteamApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** The two possible credential states derived from the encrypted store. */
sealed interface CredentialsState {
    /** No usable credentials: the app should present onboarding. */
    data object Unconfigured : CredentialsState

    /** Both credentials present; safe to make Steam requests. */
    data class Configured(val apiKey: String, val steamId: String) : CredentialsState
}

/** Typed outcome of resolving raw SteamID input (raw ID, profile URL, or vanity URL). */
sealed interface SteamIdResolution {
    /** A validated 17-digit SteamID64 ready to store. */
    data class Resolved(val steamId64: String) : SteamIdResolution

    /** Vanity resolution found no matching profile (`success = 42`). */
    data object NoMatch : SteamIdResolution

    /** The input isn't a SteamID64 or a recognizable profile URL, or the resolved value is invalid. */
    data object InvalidInput : SteamIdResolution

    /** A network or API-key failure prevented resolution. */
    data object NetworkError : SteamIdResolution
}

/**
 * Single source of truth for Steam credentials. Reads them from the [EncryptedCredentialStore]
 * (seeding once from [BuildConfig] on first access for existing dev/CI builds), exposes them as
 * flows, and resolves raw SteamID input into a validated SteamID64.
 *
 * The store's suspend reads are surfaced as a [MutableStateFlow] that [save] refreshes, so
 * observers (`configured` checks, sync callers) react to onboarding without polling.
 */
@Singleton
class CredentialsRepository @Inject constructor(
    private val store: EncryptedCredentialStore,
    private val steamApi: SteamApi,
) {
    private val state = MutableStateFlow<CredentialsState>(CredentialsState.Unconfigured)
    private val seedMutex = Mutex()
    @Volatile private var seeded = false

    /**
     * The current credential state. Loading (and the one-time [BuildConfig] seed) is triggered on
     * first collection via [onStart], so observers get the real state without an explicit call.
     */
    val credentialsStateFlow: Flow<CredentialsState> = state.onStart { refresh() }

    val apiKeyFlow: Flow<String?> = credentialsStateFlow.map {
        (it as? CredentialsState.Configured)?.apiKey
    }

    val steamIdFlow: Flow<String?> = credentialsStateFlow.map {
        (it as? CredentialsState.Configured)?.steamId
    }

    /**
     * Load credentials into [state], seeding once from [BuildConfig] when the store is empty. Safe
     * to call repeatedly: the [BuildConfig] seed runs at most once, after which the encrypted store
     * is authoritative (even if the user later clears it). Suspends, so callers await the result
     * before reading credentials.
     */
    suspend fun refresh(): CredentialsState {
        seedIfNeeded()
        val apiKey = store.readApiKey()
        val steamId = store.readSteamId()
        val next = if (!apiKey.isNullOrBlank() && !steamId.isNullOrBlank()) {
            CredentialsState.Configured(apiKey, steamId)
        } else {
            CredentialsState.Unconfigured
        }
        state.value = next
        return next
    }

    /** Persist new credentials and refresh the observed state. */
    suspend fun save(apiKey: String, steamId: String) {
        store.write(apiKey.trim(), steamId.trim())
        // The store now holds credentials, so no further BuildConfig seed should ever run.
        seeded = true
        refresh()
    }

    /** Read the current API key + SteamID, loading/seeding first if not yet configured. */
    suspend fun currentCredentials(): CredentialsState.Configured? {
        val current = state.value
        if (current is CredentialsState.Configured) return current
        return refresh() as? CredentialsState.Configured
    }

    /**
     * Parse and resolve raw SteamID input into a validated SteamID64. Bare IDs and `/profiles/`
     * URLs resolve locally; `/id/<vanity>` URLs call [SteamApi.resolveVanityUrl]. The vanity call
     * uses [apiKeyOverride] when supplied (the key the user just typed during onboarding, not yet
     * saved), falling back to the stored key. Maps every failure mode to a typed [SteamIdResolution].
     */
    suspend fun resolveSteamId(input: String, apiKeyOverride: String? = null): SteamIdResolution {
        return when (val parsed = SteamIdInput.parse(input)) {
            is SteamIdInput.Parsed.SteamId64 ->
                if (SteamIdInput.isValidSteamId64(parsed.value)) {
                    SteamIdResolution.Resolved(parsed.value.trim())
                } else {
                    SteamIdResolution.InvalidInput
                }

            is SteamIdInput.Parsed.Vanity -> resolveVanity(parsed.token, apiKeyOverride)

            SteamIdInput.Parsed.Unrecognized -> SteamIdResolution.InvalidInput
        }
    }

    private suspend fun resolveVanity(token: String, apiKeyOverride: String?): SteamIdResolution {
        val apiKey = apiKeyOverride?.trim()?.takeIf { it.isNotBlank() }
            ?: store.readApiKey()?.takeIf { it.isNotBlank() }
            ?: return SteamIdResolution.NetworkError
        val result = runCatching { steamApi.resolveVanityUrl(apiKey, token) }
            .getOrElse { return SteamIdResolution.NetworkError }
            .response
        return mapVanityResult(result.success, result.steamId)
    }

    private suspend fun seedIfNeeded() {
        if (seeded) return
        seedMutex.withLock {
            if (seeded) return
            if (!store.hasCredentials()) {
                val seedKey = BuildConfig.STEAM_API_KEY
                val seedId = BuildConfig.STEAM_ID
                if (seedKey.isNotBlank() && seedId.isNotBlank()) {
                    store.write(seedKey, seedId)
                }
            }
            // Whether or not a seed was written, BuildConfig is never consulted again.
            seeded = true
        }
    }

    companion object {
        /**
         * Pure mapping from a `ResolveVanityURL` result to a typed [SteamIdResolution]:
         * `success = 1` with a valid SteamID64 → [Resolved][SteamIdResolution.Resolved];
         * `success = 1` with a malformed id → [InvalidInput][SteamIdResolution.InvalidInput];
         * any other `success` (e.g. `42`) → [NoMatch][SteamIdResolution.NoMatch].
         */
        fun mapVanityResult(success: Int, steamId: String?): SteamIdResolution = when {
            success == 1 && steamId != null && SteamIdInput.isValidSteamId64(steamId) ->
                SteamIdResolution.Resolved(steamId)
            success == 1 -> SteamIdResolution.InvalidInput
            else -> SteamIdResolution.NoMatch
        }
    }
}
