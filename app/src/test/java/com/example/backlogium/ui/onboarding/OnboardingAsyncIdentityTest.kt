package com.example.backlogium.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic coverage for the onboarding request-identity gate. The real network interleaving
 * is deliberately not timed here: these assertions model the state at the publish point, which is
 * the only state that can decide whether a result may reach CredentialsRepository.save().
 */
class OnboardingAsyncIdentityTest {

    @Test
    fun aResolutionForEditedInputCannotOfferSave() {
        assertFalse(
            shouldPublishSteamIdResolution(
                displayedInput = "B",
                submittedInput = "A",
                displayedApiKey = "key",
                submittedApiKey = "key",
            ),
        )
        assertFalse(
            shouldPersistOnboardingVerification(
                state = OnboardingUiState(
                    steamIdInput = "B",
                    apiKey = "key",
                    resolve = ResolveState.Idle,
                ),
                submittedSteamIdInput = "A",
                submittedApiKey = "key",
                decision = VerificationDecision.Persist,
            ),
        )
    }

    @Test
    fun aFirstConfigurationCannotPersistAnEditedResolution() {
        val firstRunState = OnboardingUiState(
            hasExistingKey = false,
            steamIdInput = "B",
            apiKey = "key",
            resolve = ResolveState.Idle,
        )

        assertFalse(
            shouldPersistOnboardingVerification(
                state = firstRunState,
                submittedSteamIdInput = "A",
                submittedApiKey = "key",
                decision = VerificationDecision.Persist,
            ),
        )
    }

    @Test
    fun editingOrLeavingTheStepInvalidatesVerification() {
        val edited = OnboardingUiState(
            step = OnboardingStep.VERIFY,
            steamIdInput = "B",
            apiKey = "key",
            resolve = ResolveState.Resolved("76561198000000000"),
            verify = VerifyState.Verifying,
        )
        val backed = edited.copy(step = OnboardingStep.API_KEY)

        assertFalse(isCurrentOnboardingVerification(edited, "A", "key"))
        assertFalse(isCurrentOnboardingVerification(backed, "B", "key"))
    }

    @Test
    fun theDisplayedInputStillPublishesAndCanPersist() {
        val current = OnboardingUiState(
            step = OnboardingStep.VERIFY,
            steamIdInput = "A",
            apiKey = "key",
            resolve = ResolveState.Resolved("76561198000000000"),
            verify = VerifyState.Verifying,
        )

        assertTrue(isCurrentOnboardingVerification(current, "A", "key"))
        assertTrue(
            shouldPersistOnboardingVerification(
                state = current,
                submittedSteamIdInput = "A",
                submittedApiKey = "key",
                decision = VerificationDecision.Persist,
            ),
        )
        assertFalse(
            shouldPersistOnboardingVerification(
                state = current,
                submittedSteamIdInput = "A",
                submittedApiKey = "key",
                decision = VerificationDecision.OfferRetry,
            ),
        )
    }

    @Test
    fun retryAfterFailureKeepsTheSameIdentityEligible() {
        val state = OnboardingUiState(
            step = OnboardingStep.VERIFY,
            steamIdInput = "A",
            apiKey = "key",
            resolve = ResolveState.Resolved("76561198000000000"),
            verify = VerifyState.Unreachable,
        )

        assertTrue(isCurrentOnboardingVerification(state, "A", "key"))
        assertTrue(
            shouldPersistOnboardingVerification(
                state = state,
                submittedSteamIdInput = "A",
                submittedApiKey = "key",
                decision = VerificationDecision.Persist,
            ),
        )
    }
}