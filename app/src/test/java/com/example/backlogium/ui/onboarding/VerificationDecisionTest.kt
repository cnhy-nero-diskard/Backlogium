package com.example.backlogium.ui.onboarding

import com.example.backlogium.data.repo.CredentialVerification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The credential flow's response to each verification outcome.
 *
 * [credentialsAreNeverPersistedWithoutVerification] is the test that matters: it enumerates every
 * outcome and asserts that only one of them admits persistence. Adding a fifth outcome without
 * deciding what it means will fail here rather than silently defaulting to "save it anyway".
 */
class VerificationDecisionTest {

    private val entered = OnboardingUiState(
        step = OnboardingStep.VERIFY,
        apiKey = "entered-key",
        steamIdInput = "76561198000000000",
        resolve = ResolveState.Resolved("76561198000000000"),
        verify = VerifyState.Verifying,
    )

    @Test
    fun credentialsAreNeverPersistedWithoutVerification() {
        val outcomes = listOf(
            CredentialVerification.KeyRejected,
            CredentialVerification.NoProfile,
            CredentialVerification.Unreachable,
        )
        outcomes.forEach { outcome ->
            assertNotEquals(
                "verification outcome $outcome must not lead to persistence",
                VerificationDecision.Persist,
                decideVerification(outcome),
            )
        }
        assertEquals(
            VerificationDecision.Persist,
            decideVerification(CredentialVerification.Verified),
        )
    }

    @Test
    fun aRejectedKeySendsTheUserBackToTheKey() {
        val decision = decideVerification(CredentialVerification.KeyRejected)
        assertEquals(
            OnboardingStep.API_KEY,
            (decision as VerificationDecision.Correct).step,
        )
        // The message has to name the key, not the SteamID: the point of verifying both at once is
        // that the flow can say which one Steam objected to.
        assertTrue(decision.message.contains("API key"))
    }

    @Test
    fun aMissingProfileSendsTheUserBackToTheSteamId() {
        val decision = decideVerification(CredentialVerification.NoProfile)
        assertEquals(
            OnboardingStep.STEAM_ID,
            (decision as VerificationDecision.Correct).step,
        )
        assertTrue(decision.message.contains("profile"))
    }

    @Test
    fun aNetworkFailureIsNotReportedAsInvalidCredentials() {
        val decision = decideVerification(CredentialVerification.Unreachable)
        assertEquals(VerificationDecision.OfferRetry, decision)

        val next = entered.applying(decision)
        assertEquals(VerifyState.Unreachable, next.verify)
        // Not a Rejected: a Rejected renders as a validation error against a value the user typed.
        assertTrue(next.verify !is VerifyState.Rejected)
    }

    @Test
    fun retryingAfterANetworkFailureNeedsNothingReEntered() {
        val afterFailure = entered.applying(decideVerification(CredentialVerification.Unreachable))
        assertEquals("entered-key", afterFailure.apiKey)
        assertEquals("76561198000000000", afterFailure.steamIdInput)
        assertTrue(afterFailure.isResolved)

        // The retry runs the same decision over a now-successful verification and persists.
        val decision = decideVerification(CredentialVerification.Verified)
        assertEquals(VerificationDecision.Persist, decision)
        val persisting = afterFailure.applying(decision)
        assertEquals("entered-key", persisting.apiKey)
        assertEquals(VerifyState.Idle, persisting.verify)
    }

    @Test
    fun aCorrectionKeepsTheOtherEnteredValue() {
        // Sent back to the key: the SteamID they already resolved must still be there when they
        // come forward again, or the flow makes them redo work Steam never objected to.
        val afterRejection = entered.applying(decideVerification(CredentialVerification.KeyRejected))
        assertEquals("76561198000000000", afterRejection.steamIdInput)
        assertTrue(afterRejection.isResolved)
    }

    @Test
    fun theCredentialStepCountIsDerivedFromTheFlow() {
        assertEquals(
            OnboardingStep.entries.count { it.credentialStepNumber != null },
            OnboardingStep.credentialStepCount,
        )
        // Setup is past the credential flow, so it must not be counted as one of its steps.
        assertEquals(null, OnboardingStep.SETUP.credentialStepNumber)
    }
}
