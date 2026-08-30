package com.example.backlogium.domain

import com.example.backlogium.data.remote.dto.PlayerSummaryDto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [mergePlayerIdentity] — the pure rule both the periodic sync and the live poll
 * use to fold a `GetPlayerSummaries` response onto the stored profile identity.
 */
class PlayerIdentityTest {

    private val stored = PlayerIdentity(personaName = "OldName", avatarUrl = "https://old/a.jpg")
    private val empty = PlayerIdentity(personaName = null, avatarUrl = null)

    @Test
    fun summaryWithIdentity_isPersisted() {
        val summary = PlayerSummaryDto(personaName = "Nero", avatarFull = "https://cdn/full.jpg")

        assertEquals(
            PlayerIdentity("Nero", "https://cdn/full.jpg"),
            mergePlayerIdentity(summary, empty),
        )
    }

    @Test
    fun changedIdentity_replacesStoredValues() {
        val summary = PlayerSummaryDto(personaName = "NewName", avatarFull = "https://new/a.jpg")

        assertEquals(
            PlayerIdentity("NewName", "https://new/a.jpg"),
            mergePlayerIdentity(summary, stored),
        )
    }

    @Test
    fun failedRequest_preservesStoredIdentity() {
        assertEquals(stored, mergePlayerIdentity(summary = null, stored = stored))
    }

    @Test
    fun blankFields_preserveStoredIdentity() {
        val summary = PlayerSummaryDto(personaName = "", avatarFull = null)

        assertEquals(stored, mergePlayerIdentity(summary, stored))
    }

    @Test
    fun noIdentityEverSeen_staysNull() {
        assertEquals(empty, mergePlayerIdentity(summary = null, stored = empty))
    }

    @Test
    fun profileLocation_neverSetsStoreRegion() {
        val summary = PlayerSummaryDto(personaName = "Nero", locCountryCode = "ph")

        // `loccountrycode` describes the public profile location, not the payment-derived Store
        // Country. It must not select the region used for store prices.
        assertEquals(null, mergePlayerIdentity(summary, empty).storeRegion)
    }

    @Test
    fun profileLocation_doesNotReplaceAnExplicitStoreRegion() {
        val known = stored.copy(storeRegion = "PH")

        assertEquals("PH", mergePlayerIdentity(PlayerSummaryDto(), known).storeRegion)
        assertEquals("PH", mergePlayerIdentity(PlayerSummaryDto(locCountryCode = " "), known).storeRegion)
        assertEquals("PH", mergePlayerIdentity(PlayerSummaryDto(locCountryCode = "US"), known).storeRegion)
        assertEquals("PH", mergePlayerIdentity(summary = null, stored = known).storeRegion)
    }

    @Test
    fun noCountryEverSeen_staysNull() {
        assertEquals(null, mergePlayerIdentity(PlayerSummaryDto(), empty).storeRegion)
    }
}
