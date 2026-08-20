package com.example.backlogium.ui.onboarding

import com.example.backlogium.data.repo.CredentialVerification

/**
 * What the credential flow does with a verification result.
 *
 * Extracted from [OnboardingViewModel] so that the one rule this change exists to enforce —
 * [Persist] is reachable from exactly one verification outcome — is a fact about a pure function
 * rather than a claim about a coroutine.
 */
internal sealed interface VerificationDecision {
    /** The only decision that leads to credentials being written. */
    data object Persist : VerificationDecision

    /** Send the user back to [step] to fix the value [message] names. */
    data class Correct(val step: OnboardingStep, val message: String) : VerificationDecision

    /** Nothing is wrong with what was entered; offer another attempt. */
    data object OfferRetry : VerificationDecision
}

internal fun decideVerification(verification: CredentialVerification): VerificationDecision =
    when (verification) {
        CredentialVerification.Verified -> VerificationDecision.Persist

        CredentialVerification.KeyRejected -> VerificationDecision.Correct(
            step = OnboardingStep.API_KEY,
            message = "Steam did not accept this API key.",
        )

        CredentialVerification.NoProfile -> VerificationDecision.Correct(
            step = OnboardingStep.STEAM_ID,
            message = "No Steam profile found for that ID.",
        )

        // Deliberately not a `Correct`: a request that never reached Steam is not a verdict on
        // what was typed, and sending someone back to re-enter a correct key because their
        // connection dropped is the worst answer this flow could give.
        CredentialVerification.Unreachable -> VerificationDecision.OfferRetry
    }

/**
 * Apply [decision] to the flow's state.
 *
 * Neither branch clears [OnboardingUiState.apiKey], [OnboardingUiState.steamIdInput], or the
 * resolution: a retry after a network failure — and a correction to one field — must not cost the
 * user the other one.
 */
internal fun OnboardingUiState.applying(decision: VerificationDecision): OnboardingUiState =
    when (decision) {
        VerificationDecision.Persist -> copy(verify = VerifyState.Idle)

        is VerificationDecision.Correct -> copy(
            step = decision.step,
            verify = VerifyState.Rejected(decision.step, decision.message),
        )

        VerificationDecision.OfferRetry ->
            copy(step = OnboardingStep.STEAM_ID, verify = VerifyState.Unreachable)
    }
