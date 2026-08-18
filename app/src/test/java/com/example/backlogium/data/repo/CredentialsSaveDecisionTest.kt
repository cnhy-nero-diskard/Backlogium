package com.example.backlogium.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsSaveDecisionTest {

    @Test
    fun apiKeyOnlyChangeDoesNotRequireIdentityConfirmation() {
        assertFalse(
            CredentialsRepository.requiresIdentityConfirmation(
                storedSteamId = "76561198000000000",
                incomingSteamId = "76561198000000000",
            ),
        )
    }

    @Test
    fun changedSteamIdRequiresIdentityConfirmation() {
        assertTrue(
            CredentialsRepository.requiresIdentityConfirmation(
                storedSteamId = "76561198000000000",
                incomingSteamId = "76561198000000001",
            ),
        )
    }

    @Test
    fun firstConfigurationDoesNotRequireIdentityConfirmation() {
        assertFalse(
            CredentialsRepository.requiresIdentityConfirmation(
                storedSteamId = null,
                incomingSteamId = "76561198000000000",
            ),
        )
    }
}
