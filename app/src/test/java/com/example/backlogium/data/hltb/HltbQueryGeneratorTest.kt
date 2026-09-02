package com.example.backlogium.data.hltb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbQueryGeneratorTest {

    @Test
    fun editionHeavyTitle_removesNoise() {
        // Witcher 2 edition-style
        val variants = HltbQueryGenerator.variants("The Witcher 2: Assassins of Kings Enhanced Edition")
        assertTrue(variants.any { it == "the witcher 2 assassins of kings" || it.contains("witcher 2") })
        // Original normalized is "the witcher 2 assassins of kings enhanced edition"
        assertTrue(variants.isNotEmpty())
        assertTrue(variants.size <= 3)
    }

    @Test
    fun subtitledGame_reducesSubtitle() {
        val variants = HltbQueryGenerator.variants("The Witcher 3: Wild Hunt")
        // Should contain core without subtitle
        assertTrue(variants.contains("the witcher 3"))
    }

    @Test
    fun sequelNumeralVariant_arabicToRomanAndRomanToArabic() {
        assertEquals("Witcher 2", HltbQueryGenerator.removeLeadingArticle("The Witcher 2") ?: "")
        val altArabic = HltbQueryGenerator.numeralAlternative("The Witcher 2")
        assertEquals("The Witcher II", altArabic)
        val altRoman = HltbQueryGenerator.numeralAlternative("Final Fantasy VII")
        // Roman -> Arabic alternative should exist and contain 7
        assertTrue(altRoman != null && altRoman.contains("7"))
    }

    @Test
    fun trademarkBracketNoise_removed() {
        // Trademark/bracket-only noise must yield the cleaned base title, not a no-op null
        assertEquals("Game", HltbQueryGenerator.removeEditionNoise("Game™ [Deluxe]"))
        val variants = HltbQueryGenerator.variants("Game™ [Deluxe]")
        // The cleaned variant is actually emitted and differs from the normalized primary
        assertTrue(variants.contains("game"))
        assertTrue(variants.all { !it.contains("™") && !it.contains("[") })
    }

    @Test
    fun duplicateVariants_areOmitted() {
        // Title where edition removal and subtitle reduction could collide
        val variants = HltbQueryGenerator.variants("Doom: Enhanced Edition")
        // Should deduplicate and not exceed 3
        assertTrue(variants.distinct().size == variants.size)
    }

    @Test
    fun threeQueryCeiling_enforced() {
        val variants = HltbQueryGenerator.variants("The Elder Scrolls V: Skyrim Definitive Edition")
        assertTrue(variants.size <= 3)
    }

    @Test
    fun emptyOrUnchangedVariants_discarded() {
        // Title with no noise -> variants should be limited or empty if nothing to relax
        val variants = HltbQueryGenerator.variants("Portal")
        // Portal has no subtitle, no edition, no leading article? Actually no leading article normalizable? but still
        // Should not contain empty or duplicate of normalized primary
        val primary = HltbMatcher.normalize("Portal")
        assertTrue(variants.none { it == primary })
        assertTrue(variants.none { it.isBlank() })
    }

    @Test
    fun conflictingSequelPenalty_scoresLower() {
        val original = "The Witcher 2"
        val candidates = listOf(
            HltbCandidate(hltbId = 1L, name = "The Witcher 3"),
            HltbCandidate(hltbId = 2L, name = "The Witcher 2"),
        )
        val scored = HltbMatcher.scoredBroader(original, candidates)
        // Witcher 2 should rank above Witcher 3 despite shortened query
        assertEquals(2L, scored.first().hltbId)
        assertTrue(scored.first().confidence > scored.last().confidence)
        assertTrue(scored.all { it.source == HltbCandidateSource.BROADER_SEARCH })
    }

    @Test
    fun conflictingRomanSequelPenalty_scoresLower() {
        val original = "Final Fantasy VII"
        val candidates = listOf(
            HltbCandidate(hltbId = 1L, name = "Final Fantasy VIII"),
            HltbCandidate(hltbId = 2L, name = "Final Fantasy VII"),
        )
        val scored = HltbMatcher.scoredBroader(original, candidates)
        // Exact VII should rank above conflicting sequel VIII
        assertEquals(2L, scored.first().hltbId)
        // The Roman conflict must incur the strong penalty, not just lose on similarity
        assertTrue(scored.first().confidence - scored.last().confidence >= 0.4)
    }

    @Test
    fun crossQueryDeduplication_retainsRichest() {
        val c1 = HltbCandidate(hltbId = 10L, name = "Game", mainStoryMinutes = 100, imageUrl = null, confidence = 0.5)
        val c2 = HltbCandidate(hltbId = 10L, name = "Game", mainStoryMinutes = 100, mainExtraMinutes = 200, imageUrl = "https://howlongtobeat.com/games/x.jpg", confidence = 0.6)
        val deduped = HltbMatcher.deduplicateBroader(listOf(c1, c2))
        assertEquals(1, deduped.size)
        assertEquals(10L, deduped.first().hltbId)
        // Richest (c2 has more lengths + image) should win
        assertEquals(200, deduped.first().mainExtraMinutes)
        assertTrue(deduped.first().imageUrl != null)
        assertEquals(HltbCandidateSource.BROADER_SEARCH, deduped.first().source)
    }

    @Test
    fun titleCollision_deduplicationById() {
        val candidates = listOf(
            HltbCandidate(hltbId = 1L, name = "Game A"),
            HltbCandidate(hltbId = 1L, name = "Game A Duplicate"),
            HltbCandidate(hltbId = 2L, name = "Game B"),
        )
        val deduped = HltbMatcher.deduplicateBroader(candidates)
        assertEquals(2, deduped.size)
    }
}
