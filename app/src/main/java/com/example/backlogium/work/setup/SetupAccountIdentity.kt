package com.example.backlogium.work.setup

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.repo.CredentialsRepository
import java.security.MessageDigest
import javax.inject.Inject

/**
 * The account scope a setup attempt is recorded under, as a tri-state decision.
 *
 * Attempt records persist their owner as the opaque marker inside [SetupAccountState.Configured] so
 * a completion from an old account or a callback that outlived an account change can never be
 * attributed to a replacement account. [SetupAccountState.Refused] means no poll or write may run
 * for setup right now (no configured account, or an account change is mid-flight).
 */
sealed interface SetupAccountState {
    /** A usable account's opaque marker. The marker is unreadable: never a raw account identifier. */
    data class Configured(val marker: String) : SetupAccountState

    /** No account may be polled or written for now (unconfigured, or a reset is pending). */
    data object Refused : SetupAccountState
}

/** The account-scope decision behind every setup admission and observation fence. */
fun interface SetupAccountIdentity {
    suspend fun state(): SetupAccountState
}

/**
 * Explicit test-only seam: a pretend configured account, never the production unknown-allowing null.
 * Only the non-Hilt test/surface constructor binds this; production always uses
 * [CredentialSetupAccountIdentity].
 */
object NoSetupAccountIdentity : SetupAccountIdentity {
    override suspend fun state(): SetupAccountState =
        SetupAccountState.Configured("test-account")
}

/**
 * Production account scope. Refuses setup admission/observation whenever the active account cannot
 * be identified — no credentials, or an account-change reset marker is still pending (a reset is
 * mid-flight, so nobody may poll or write).
 */
class CredentialSetupAccountIdentity @Inject constructor(
    private val credentials: CredentialsRepository,
    private val accountChangeMarker: AccountChangeMarkerStore,
) : SetupAccountIdentity {
    override suspend fun state(): SetupAccountState {
        if (accountChangeMarker.pendingSteamId() != null) return SetupAccountState.Refused
        val steamId = credentials.currentCredentials()?.steamId
            ?.takeIf { it.isNotBlank() }
            ?: return SetupAccountState.Refused
        return SetupAccountState.Configured(opaqueAccountMarkerOf(steamId))
    }
}

/**
 * A stable, non-reversible token from a Steam account id. Stable so the same account fences the
 * same attempts across restarts; non-reversible so the marker is never a raw account identifier.
 */
internal fun opaqueAccountMarkerOf(steamId: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(steamId.trim().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
