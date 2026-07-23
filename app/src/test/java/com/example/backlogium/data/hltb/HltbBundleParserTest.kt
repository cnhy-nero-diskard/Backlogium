package com.example.backlogium.data.hltb

import com.example.backlogium.data.hltb.dto.HltbSearchGame
import com.example.backlogium.data.hltb.dto.HltbSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixture-based tests for the fragile scrape parsing: the `_app-*.js` bundle path, the POST
 * search endpoint, and the seconds → minutes candidate mapping.
 */
class HltbBundleParserTest {

    @Test
    fun extractAppBundlePath_findsHashedAppChunk() {
        val html = """
            <html><head>
            <script src="/_next/static/chunks/webpack-abc.js"></script>
            <script src="/_next/static/chunks/pages/_app-1a2b3c4d5e6f7890.js"></script>
            </head></html>
        """.trimIndent()

        assertEquals(
            "/_next/static/chunks/pages/_app-1a2b3c4d5e6f7890.js",
            HltbBundleParser.extractAppBundlePath(html),
        )
    }

    @Test
    fun extractAppBundlePath_returnsNullWhenAbsent() {
        assertNull(HltbBundleParser.extractAppBundlePath("<html><body>no bundle here</body></html>"))
    }

    @Test
    fun extractEndpoint_readsPostFetchPath() {
        val js = """function s(e){return fetch("/api/seek/xyz",{method:"POST",body:e})}"""
        assertEquals("/api/seek/xyz", HltbBundleParser.extractEndpoint(js))
    }

    @Test
    fun extractEndpoint_returnsNullWhenNoFetchPresent() {
        assertNull(HltbBundleParser.extractEndpoint("const x = 1; // nothing fetch-like"))
    }

    @Test
    fun mapCandidates_convertsSecondsToMinutesAndNullsMissing() {
        val response = HltbSearchResponse(
            data = listOf(
                HltbSearchGame(
                    gameId = 42L,
                    gameName = "Portal 2",
                    compMainSeconds = 3600, // 60m
                    compPlusSeconds = 5400, // 90m
                    comp100Seconds = 7200, // 120m
                    compAllSeconds = 0, // absent -> null
                ),
            ),
        )

        val candidates = HltbBundleParser.mapCandidates(response)

        assertEquals(1, candidates.size)
        val c = candidates.first()
        assertEquals(42L, c.hltbId)
        assertEquals("Portal 2", c.name)
        assertEquals(60, c.mainStoryMinutes)
        assertEquals(90, c.mainExtraMinutes)
        assertEquals(120, c.completionistMinutes)
        assertNull(c.allStylesMinutes)
    }

    @Test
    fun mapCandidates_roundsToNearestMinute() {
        val response = HltbSearchResponse(
            data = listOf(HltbSearchGame(gameId = 1L, gameName = "x", compMainSeconds = 90)),
        )
        // 90s -> 2m (rounded), not 1m.
        assertEquals(2, HltbBundleParser.mapCandidates(response).first().mainStoryMinutes)
    }
}
