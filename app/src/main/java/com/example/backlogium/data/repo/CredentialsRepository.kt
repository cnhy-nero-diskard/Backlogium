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
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/** The two possible credential states derived from the encrypted store. */
sealed interface CredentialsState {
    /** No usable credentials: the app should present onboarding. */
    data object Unconfigured : CredentialsState

    /** Both credentials present; safe to make Steam requests. */
    data class Configured(val apiKey: String, val steamId: String) : CredentialsState
}

/** Result of attempting to save credentials at the account boundary. */
sealed interface CredentialsSaveResult {
    /** The credentials were written, either for first configuration or the same account. */
    data object Saved : CredentialsSaveResult

    /** The caller must confirm the account change before anything is written. */
    data class IdentityChanged(
        val storedSteamId: String,
        val incomingSteamId: String,
    ) : CredentialsSaveResult
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
 * Outcome of testing entered credentials against Steam before they are persisted.
 *
 * The three failure modes are kept apart because they need three different responses: a rejected
 * key sends the user back to the key, a missing profile back to the SteamID, and an unreachable
 * Steam is not the user's mistake at all — it must offer a retry rather than a correction.
 */
sealed interface CredentialVerification {
    /** The key was accepted and the SteamID names an existing profile. */
    data object Verified : CredentialVerification

    /** Steam refused the request on the key's account. */
    data object KeyRejected : CredentialVerification

    /** The key worked, but Steam knows no profile with that SteamID. */
    data object NoProfile : CredentialVerification

    /** Steam could not be reached. Says nothing about whether the credentials are good. */
    data object Unreachable : CredentialVerification
}

/**
 * The raw shape of one verification request, so [CredentialsRepository.mapVerification] can be a
 * pure function over it. Deliberately carries no credential: nothing modelled here may end up in
 * a log line or an error message that could echo the API key back.
 */
sealed interface VerificationProbe {
    /** A 2xx response, with the number of players Steam returned for the requested SteamID. */
    data class Response(val players: Int) : VerificationProbe

    /** A non-2xx HTTP response. */
    data class HttpError(val code: Int) : VerificationProbe

    /** The request never produced a response (no connectivity, DNS, TLS, timeout). */
    data object TransportFailure : VerificationProbe
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
) : CredentialsProvider {
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

    /**
     * Persist new credentials and refresh the observed state, unless the Steam identity changes.
     * The repository reports that condition without writing anything: the destructive response
     * belongs to the confirmed account-change flow, not to a storage primitive.
     */
    suspend fun save(apiKey: String, steamId: String): CredentialsSaveResult {
        val normalizedSteamId = steamId.trim()
        val current = refresh()
        if (current is CredentialsState.Configured &&
            requiresIdentityConfirmation(current.steamId, normalizedSteamId)
        ) {
            return CredentialsSaveResult.IdentityChanged(
                storedSteamId = current.steamId,
                incomingSteamId = normalizedSteamId,
            )
        }

        store.write(apiKey.trim(), normalizedSteamId)
        // The store now holds credentials, so no further BuildConfig seed should ever run.
        seeded = true
        refresh()
        return CredentialsSaveResult.Saved
    }

    /**
     * Test [apiKey] and [steamId] against Steam with a single `GetPlayerSummaries` request — the
     * cheapest call that exercises both values at once — and report which of them, if either, is
     * wrong.
     *
     * Called only from the credential-entry flow, immediately before [save]. Credentials that are
     * already stored have already been through here, so nothing that merely *reads* them verifies
     * again: a request per read would spend Steam quota to re-answer a question already answered,
     * and would make the app depend on connectivity to use data it already has.
     *
     * A profile whose library is private verifies successfully. `GetPlayerSummaries` returns the
     * player regardless of privacy; it is `GetOwnedGames` that comes back empty, and the sync that
     * calls it reports that with a better message than a second probe here could.
     *
     * The key is never logged and never reaches the returned value — see [VerificationProbe].
     */
    suspend fun verify(apiKey: String, steamId: String): CredentialVerification {
        val key = apiKey.trim()
        val id = steamId.trim()
        if (key.isBlank()) return CredentialVerification.KeyRejected
        val probe = try {
            VerificationProbe.Response(
                steamApi.getPlayerSummaries(key, id).response.players.size,
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (http: HttpException) {
            VerificationProbe.HttpError(http.code())
        } catch (_: Exception) {
            // Intentionally swallowed rather than logged: the failing request carries the key in
            // its URL, and an exception message can quote it.
            VerificationProbe.TransportFailure
        }
        return mapVerification(probe)
    }

    /** Read the current API key + SteamID, loading/seeding first if not yet configured. */
    override suspend fun currentCredentials(): CredentialsState.Configured? {
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
         * Pure mapping from one verification request's raw shape to a typed outcome.
         *
         * `401`/`403` are the two ways Steam refuses a key: the documented answer is `403`, but a
         * malformed key can also come back `401`, and both mean the same thing to the user. Any
         * other HTTP status is a Steam-side problem, not a credential problem, so it maps to
         * [Unreachable][CredentialVerification.Unreachable] — which offers a retry — rather than
         * telling a user with a perfectly good key that it was rejected.
         */
        fun mapVerification(probe: VerificationProbe): CredentialVerification = when (probe) {
            is VerificationProbe.Response ->
                if (probe.players > 0) {
                    CredentialVerification.Verified
                } else {
                    CredentialVerification.NoProfile
                }

            is VerificationProbe.HttpError ->
                if (probe.code == 401 || probe.code == 403) {
                    CredentialVerification.KeyRejected
                } else {
                    CredentialVerification.Unreachable
                }

            VerificationProbe.TransportFailure -> CredentialVerification.Unreachable
        }

        /** Pure account-boundary decision used by save and its regression tests. */
        fun requiresIdentityConfirmation(storedSteamId: String?, incomingSteamId: String): Boolean =
            !storedSteamId.isNullOrBlank() && storedSteamId != incomingSteamId

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
