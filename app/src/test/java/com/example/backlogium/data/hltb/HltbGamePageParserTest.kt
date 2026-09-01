package com.example.backlogium.data.hltb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbGamePageParserTest {

    private fun sampleHtml(): String = HltbGamePageParserTest::class.java.getResource(
        "/com/example/backlogium/data/hltb/page/hltb-game-page-sample.html"
    )!!.readText()

    private fun notFoundHtml(): String = HltbGamePageParserTest::class.java.getResource(
        "/com/example/backlogium/data/hltb/page/hltb-game-page-not-found.html"
    )!!.readText()

    private fun partialHtml(): String = HltbGamePageParserTest::class.java.getResource(
        "/com/example/backlogium/data/hltb/page/hltb-game-page-partial.html"
    )!!.readText()

    private fun legacyHtml(): String = HltbGamePageParserTest::class.java.getResource(
        "/com/example/backlogium/data/hltb/page/hltb-game-page-legacy.html"
    )!!.readText()

    @Test
    fun parse_successFullPayload() {
        val result = HltbGamePageParser.parse(sampleHtml(), 7231L)
        assertTrue(result is HltbGamePageParser.ParseResult.Success)
        val candidate = (result as HltbGamePageParser.ParseResult.Success).candidate
        assertEquals(7231L, candidate.hltbId)
        assertEquals("Portal 2", candidate.name)
        assertEquals(512, candidate.mainStoryMinutes)
        assertEquals(824, candidate.mainExtraMinutes)
        assertEquals(1353, candidate.completionistMinutes)
        assertEquals(1083, candidate.allStylesMinutes)
        assertEquals("https://howlongtobeat.com/games/7231_Portal_2.jpg", candidate.imageUrl)
    }

    @Test
    fun parse_notFoundPage() {
        val result = HltbGamePageParser.parse(notFoundHtml(), 999999L)
        assertTrue(result is HltbGamePageParser.ParseResult.NotFound)
    }

    @Test
    fun parse_partialLengths() {
        val result = HltbGamePageParser.parse(partialHtml(), 9999L)
        assertTrue(result is HltbGamePageParser.ParseResult.Success)
        val c = (result as HltbGamePageParser.ParseResult.Success).candidate
        assertEquals(9999L, c.hltbId)
        assertEquals(120, c.mainStoryMinutes) // 7200s -> 120m
        assertTrue(c.mainExtraMinutes == null)
        assertTrue(c.completionistMinutes == null)
        assertTrue(c.imageUrl == null)
    }

    @Test
    fun parse_missingCover() {
        // partial has game_image null
        val result = HltbGamePageParser.parse(partialHtml(), 9999L)
        assertTrue((result as HltbGamePageParser.ParseResult.Success).candidate.imageUrl == null)
    }

    @Test
    fun parse_legacyFallbackRegex() {
        val result = HltbGamePageParser.parse(legacyHtml(), 12345L)
        assertTrue(result is HltbGamePageParser.ParseResult.Success)
        assertEquals(12345L, (result as HltbGamePageParser.ParseResult.Success).candidate.hltbId)
    }

    @Test
    fun parse_emptyResponse_isParseFailure() {
        val result = HltbGamePageParser.parse("", 1L)
        assertTrue(result is HltbGamePageParser.ParseResult.ParseFailure)
    }

    @Test
    fun parse_rotatedPayloadFailure() {
        // HTML with no structured game data and no not-found hint -> parse failure
        val html = "<html><body><div class=\"someCss\">Portal 2</div><p>8 hours</p></body></html>"
        val result = HltbGamePageParser.parse(html, 1L)
        assertTrue(result is HltbGamePageParser.ParseResult.ParseFailure)
    }

    @Test
    fun parse_redirectLikeHtmlWithoutJson_isParseFailure() {
        val html = "<html><head><meta http-equiv=\"refresh\" content=\"0;url=/\"></head><body>Redirect</body></html>"
        val result = HltbGamePageParser.parse(html, 1L)
        assertTrue(result is HltbGamePageParser.ParseResult.ParseFailure)
    }
}
