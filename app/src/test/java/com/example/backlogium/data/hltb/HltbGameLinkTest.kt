package com.example.backlogium.data.hltb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbGameLinkTest {

    @Test
    fun parse_validCanonicalNonWww() {
        val result = HltbGameLink.parse("https://howlongtobeat.com/game/12345")
        assertTrue(result is HltbGameLink.ParseResult.Valid)
        assertEquals(12345L, (result as HltbGameLink.ParseResult.Valid).hltbId)
        assertEquals("https://howlongtobeat.com/game/12345", result.canonicalUrl)
    }

    @Test
    fun parse_validWwwWithTrailingSlash() {
        val result = HltbGameLink.parse("https://www.howlongtobeat.com/game/99/")
        assertTrue(result is HltbGameLink.ParseResult.Valid)
        assertEquals(99L, (result as HltbGameLink.ParseResult.Valid).hltbId)
        assertEquals("https://howlongtobeat.com/game/99", result.canonicalUrl)
    }

    @Test
    fun parse_rejectsHttpScheme() {
        val result = HltbGameLink.parse("http://howlongtobeat.com/game/1")
        assertTrue(result is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun parse_rejectsCredentials() {
        val result = HltbGameLink.parse("https://user:pass@howlongtobeat.com/game/1")
        assertTrue(result is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun parse_rejectsCustomPort() {
        val result = HltbGameLink.parse("https://howlongtobeat.com:8080/game/1")
        assertTrue(result is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun parse_rejectsQueryAndFragment() {
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/game/1?foo=bar") is HltbGameLink.ParseResult.Invalid)
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/game/1#frag") is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun parse_rejectsWrongHost() {
        assertTrue(HltbGameLink.parse("https://evil.com/game/1") is HltbGameLink.ParseResult.Invalid)
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com.evil.com/game/1") is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun parse_rejectsUnsupportedPath() {
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/games/1") is HltbGameLink.ParseResult.Invalid)
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/game/") is HltbGameLink.ParseResult.Invalid)
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/game/abc") is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun parse_rejectsNonPositiveId() {
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/game/0") is HltbGameLink.ParseResult.Invalid)
        assertTrue(HltbGameLink.parse("https://howlongtobeat.com/game/-5") is HltbGameLink.ParseResult.Invalid)
    }

    @Test
    fun routes_rejectsNonPositiveId() {
        try {
            HltbRoutes.canonicalGameUrl(0)
            assertTrue(false)
        } catch (_: IllegalArgumentException) { }
        try {
            SteamRoutes.storeUrl(-1)
            assertTrue(false)
        } catch (_: IllegalArgumentException) { }
    }
}
