package com.example.backlogium.data.hltb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbMatcherTest {

    private fun candidate(name: String, id: Long = 1L) =
        HltbCandidate(hltbId = id, name = name, mainStoryMinutes = 600)

    @Test
    fun classify_noCandidates_isUnmatched() {
        assertEquals(
            HltbMatcher.Classification.Unmatched,
            HltbMatcher.classify("Portal 2", emptyList()),
        )
    }

    @Test
    fun classify_singleExactMatch_resolves() {
        val result = HltbMatcher.classify("Portal 2", listOf(candidate("Portal 2", id = 7L)))
        assertTrue(result is HltbMatcher.Classification.Resolved)
        assertEquals(7L, (result as HltbMatcher.Classification.Resolved).chosen.hltbId)
    }

    @Test
    fun classify_dominantConfidentMatch_resolvesEvenWithWeakerOthers() {
        val result = HltbMatcher.classify(
            "Portal 2",
            listOf(candidate("Portal 2", id = 7L), candidate("Bridge Constructor Portal", id = 8L)),
        )
        assertTrue(result is HltbMatcher.Classification.Resolved)
        assertEquals(7L, (result as HltbMatcher.Classification.Resolved).chosen.hltbId)
    }

    @Test
    fun classify_twoCloseStrongMatches_needsReview() {
        // Exact match plus a near-identical sibling (one extra 'i'): confident but not dominant,
        // so defer to the user rather than risk the wrong entry.
        val result = HltbMatcher.classify(
            "Final Fantasy VII",
            listOf(candidate("Final Fantasy VII", id = 1L), candidate("Final Fantasy VIII", id = 2L)),
        )
        assertTrue(result is HltbMatcher.Classification.NeedsReview)
    }

    @Test
    fun classify_lowConfidenceSingleMatch_needsReview() {
        val result = HltbMatcher.classify(
            "Some Obscure Indie Game",
            listOf(candidate("Totally Different Title", id = 3L)),
        )
        assertTrue(result is HltbMatcher.Classification.NeedsReview)
    }

    @Test
    fun classify_needsReview_retainsScoredCandidatesOrdered() {
        val result = HltbMatcher.classify(
            "Final Fantasy VII",
            listOf(candidate("Final Fantasy VIII", id = 1L), candidate("Final Fantasy VII", id = 2L)),
        ) as HltbMatcher.Classification.NeedsReview

        // Best (exact "Final Fantasy VII") ranked first; confidences populated and descending.
        assertEquals(2L, result.candidates.first().hltbId)
        assertTrue(result.candidates.first().confidence >= result.candidates.last().confidence)
    }

    @Test
    fun similarity_isCaseAndPunctuationInsensitive() {
        assertEquals(1.0, HltbMatcher.similarity("Portal 2", "portal 2!"), 0.0001)
        assertEquals(1.0, HltbMatcher.similarity("NieR: Automata™", "nier automata"), 0.0001)
    }

    @Test
    fun freshness_selectsMissingAndStaleOnly() {
        val now = 1_000_000_000L
        val window = 1000L
        val selected = HltbFreshness.selectStaleOrMissing(
            now = now,
            window = window,
            appIds = listOf(1L, 2L, 3L),
            fetchedAtByAppId = mapOf(
                1L to now - 500, // fresh -> skip
                2L to now - 2000, // stale -> refresh
                // 3L missing -> refresh
            ),
        )
        assertEquals(listOf(2L, 3L), selected)
    }

    @Test
    fun freshness_boundaryAtWindowIsStale() {
        val now = 100L
        val selected = HltbFreshness.selectStaleOrMissing(
            now = now,
            window = 50L,
            appIds = listOf(1L),
            fetchedAtByAppId = mapOf(1L to 50L), // exactly window-old -> refresh
        )
        assertEquals(listOf(1L), selected)
    }
}
