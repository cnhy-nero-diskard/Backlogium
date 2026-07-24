package com.example.backlogium.data.credentials

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure SteamID [parser][SteamIdInput.parse] and [validator][SteamIdInput.isValidSteamId64]. */
class SteamIdInputTest {

    private val validId = "76561197960287930"

    // --- Validation ---

    @Test
    fun validSteamId64_isAccepted() {
        assertTrue(SteamIdInput.isValidSteamId64(validId))
        assertTrue(SteamIdInput.isValidSteamId64("  $validId  ")) // surrounding whitespace tolerated
    }

    @Test
    fun invalidSteamId64_isRejected() {
        assertFalse(SteamIdInput.isValidSteamId64("12345")) // too short
        assertFalse(SteamIdInput.isValidSteamId64("12345678901234567")) // 17 digits, wrong prefix
        assertFalse(SteamIdInput.isValidSteamId64("7656119796028793")) // 16 digits
        assertFalse(SteamIdInput.isValidSteamId64("765611979602879300")) // 18 digits
        assertFalse(SteamIdInput.isValidSteamId64("7656119796028793X")) // non-digit
        assertFalse(SteamIdInput.isValidSteamId64(""))
    }

    // --- Shape 1: bare SteamID64 ---

    @Test
    fun bareSteamId64_parsesToSteamId64() {
        val parsed = SteamIdInput.parse(validId)
        assertEquals(SteamIdInput.Parsed.SteamId64(validId), parsed)
    }

    // --- Shape 2: /profiles/<id64> URL ---

    @Test
    fun profilesUrl_extractsIdLocally() {
        val parsed = SteamIdInput.parse("https://steamcommunity.com/profiles/$validId")
        assertEquals(SteamIdInput.Parsed.SteamId64(validId), parsed)
    }

    @Test
    fun profilesUrl_withTrailingSlashAndNoScheme() {
        val parsed = SteamIdInput.parse("steamcommunity.com/profiles/$validId/")
        assertEquals(SteamIdInput.Parsed.SteamId64(validId), parsed)
    }

    // --- Shape 3: /id/<vanity> URL ---

    @Test
    fun vanityUrl_returnsVanityToken() {
        val parsed = SteamIdInput.parse("https://steamcommunity.com/id/gabelogannewell")
        assertEquals(SteamIdInput.Parsed.Vanity("gabelogannewell"), parsed)
    }

    @Test
    fun vanityUrl_withTrailingSlash() {
        val parsed = SteamIdInput.parse("steamcommunity.com/id/gabelogannewell/")
        assertEquals(SteamIdInput.Parsed.Vanity("gabelogannewell"), parsed)
    }

    // --- Unrecognized ---

    @Test
    fun unrecognizedInput_parsesToUnrecognized() {
        assertEquals(SteamIdInput.Parsed.Unrecognized, SteamIdInput.parse(""))
        assertEquals(SteamIdInput.Parsed.Unrecognized, SteamIdInput.parse("   "))
        assertEquals(SteamIdInput.Parsed.Unrecognized, SteamIdInput.parse("not a steam url"))
        assertEquals(SteamIdInput.Parsed.Unrecognized, SteamIdInput.parse("https://example.com/id/foo"))
    }
}
