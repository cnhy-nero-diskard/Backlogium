package com.example.backlogium.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The four verification outcomes, mapped from one request's raw shape.
 *
 * The load-bearing case is the last group: a request that never reached Steam must not be reported
 * as invalid credentials. Before verification existed the flow had no way to say "we couldn't tell",
 * and conflating the two would send someone with a perfectly good key back to re-type it because
 * their connection dropped.
 */
class CredentialVerificationMappingTest {

    @Test
    fun aPlayerInTheResponseVerifies() {
        assertEquals(
            CredentialVerification.Verified,
            CredentialsRepository.mapVerification(VerificationProbe.Response(players = 1)),
        )
    }

    @Test
    fun anEmptyPlayerListMeansNoSuchProfile() {
        assertEquals(
            CredentialVerification.NoProfile,
            CredentialsRepository.mapVerification(VerificationProbe.Response(players = 0)),
        )
    }

    @Test
    fun forbiddenMeansTheKeyWasRejected() {
        assertEquals(
            CredentialVerification.KeyRejected,
            CredentialsRepository.mapVerification(VerificationProbe.HttpError(403)),
        )
    }

    @Test
    fun unauthorizedAlsoMeansTheKeyWasRejected() {
        assertEquals(
            CredentialVerification.KeyRejected,
            CredentialsRepository.mapVerification(VerificationProbe.HttpError(401)),
        )
    }

    @Test
    fun transportFailureIsNotAVerdictOnTheCredentials() {
        assertEquals(
            CredentialVerification.Unreachable,
            CredentialsRepository.mapVerification(VerificationProbe.TransportFailure),
        )
    }

    @Test
    fun aSteamSideErrorIsNotAVerdictOnTheCredentials() {
        // A 500 says Steam is having a bad day, not that the key is wrong. Reporting it as a
        // rejected key would send the user off to regenerate a key that was never the problem.
        assertEquals(
            CredentialVerification.Unreachable,
            CredentialsRepository.mapVerification(VerificationProbe.HttpError(500)),
        )
    }
}
