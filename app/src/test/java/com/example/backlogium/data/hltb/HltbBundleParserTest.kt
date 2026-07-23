package com.example.backlogium.data.hltb

import com.example.backlogium.data.hltb.dto.HltbSearchGame
import com.example.backlogium.data.hltb.dto.HltbSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-based tests for the fragile scrape parsing: the JS chunk enumeration, the POST
 * search endpoint extraction, and the seconds → minutes candidate mapping. Fixtures mirror
 * the shape of HowLongToBeat's current homepage/bundle (turbopack-hashed chunk names and the
 * `fetch("/api/bleed",{method:"POST"})` call).
 */
class HltbBundleParserTest {

    @Test
    fun extractChunkPaths_findsHashedChunks() {
        val html = """
            <html><head>
            <script src="/_next/static/chunks/19cd32hawdh7y.js"></script>
            <script src="/_next/static/chunks/turbopack-41gx43om5hpju.js"></script>
            <script src="https://cdn.example.com/other.js"></script>
            </head></html>
        """.trimIndent()

        val chunks = HltbBundleParser.extractChunkPaths(html)

        assertEquals(
            listOf(
                "/_next/static/chunks/19cd32hawdh7y.js",
                "/_next/static/chunks/turbopack-41gx43om5hpju.js",
            ),
            chunks,
        )
    }

    @Test
    fun extractChunkPaths_returnsEmptyWhenNone() {
        assertTrue(HltbBundleParser.extractChunkPaths("<html><body>nope</body></html>").isEmpty())
    }

    @Test
    fun extractSearchEndpoint_readsPostFetchPath() {
        val js = """let i=await fetch("/api/bleed",{method:"POST",headers:{"Content-Type":""}})"""
        assertEquals("/api/bleed", HltbBundleParser.extractSearchEndpoint(js))
    }

    @Test
    fun extractSearchEndpoint_ignoresGetFetches() {
        // The init GET must not be mistaken for the search endpoint.
        val js = """let e=await fetch(`/api/bleed/init?t=${'$'}{Date.now()}`);"""
        assertNull(HltbBundleParser.extractSearchEndpoint(js))
    }

    @Test
    fun extractSearchEndpoint_returnsNullWhenNoPostFetch() {
        assertNull(HltbBundleParser.extractSearchEndpoint("const x = 1; // nothing"))
    }

    @Test
    fun mapCandidates_convertsSecondsToMinutesAndNullsMissing() {
        val response = HltbSearchResponse(
            data = listOf(
                HltbSearchGame(
                    gameId = 7231L,
                    gameName = "Portal 2",
                    compMainSeconds = 30730, // ~512m
                    compPlusSeconds = 49433, // ~824m
                    comp100Seconds = 81179, // ~1353m
                    compAllSeconds = 0, // absent -> null
                ),
            ),
        )

        val candidates = HltbBundleParser.mapCandidates(response)

        assertEquals(1, candidates.size)
        val c = candidates.first()
        assertEquals(7231L, c.hltbId)
        assertEquals("Portal 2", c.name)
        assertEquals(512, c.mainStoryMinutes)
        assertEquals(824, c.mainExtraMinutes)
        assertEquals(1353, c.completionistMinutes)
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
