package com.example.backlogium.data.repo

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the pure vanity-result mapping in [CredentialsRepository.mapVanityResult]. */
class VanityResolutionTest {

    private val validId = "76561197960287930"

    @Test
    fun success1_withValidId_resolves() {
        assertEquals(
            SteamIdResolution.Resolved(validId),
            CredentialsRepository.mapVanityResult(success = 1, steamId = validId),
        )
    }

    @Test
    fun success42_noMatch() {
        assertEquals(
            SteamIdResolution.NoMatch,
            CredentialsRepository.mapVanityResult(success = 42, steamId = null),
        )
    }

    @Test
    fun otherSuccessCode_noMatch() {
        assertEquals(
            SteamIdResolution.NoMatch,
            CredentialsRepository.mapVanityResult(success = 15, steamId = null),
        )
    }

    @Test
    fun success1_withMalformedId_invalidInput() {
        assertEquals(
            SteamIdResolution.InvalidInput,
            CredentialsRepository.mapVanityResult(success = 1, steamId = "12345"),
        )
    }

    @Test
    fun success1_withNullId_invalidInput() {
        assertEquals(
            SteamIdResolution.InvalidInput,
            CredentialsRepository.mapVanityResult(success = 1, steamId = null),
        )
    }
}
