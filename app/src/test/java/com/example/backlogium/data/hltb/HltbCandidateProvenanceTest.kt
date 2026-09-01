package com.example.backlogium.data.hltb

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HltbCandidateProvenanceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun oldJson_withoutSourceDefaultsToPrimary() {
        val oldJson = """[{"hltbId":7,"name":"Portal","completionistMinutes":600,"confidence":0.9}]"""
        val list = json.decodeFromString(ListSerializer(HltbCandidate.serializer()), oldJson)
        assertEquals(1, list.size)
        assertEquals(HltbCandidateSource.PRIMARY, list.first().source)
        assertNull(list.first().imageUrl)
    }

    @Test
    fun newProvenance_roundTrips() {
        val candidates = listOf(
            HltbCandidate(hltbId = 1L, name = "A", source = HltbCandidateSource.PRIMARY),
            HltbCandidate(hltbId = 2L, name = "B", source = HltbCandidateSource.BROADER_SEARCH),
            HltbCandidate(hltbId = 3L, name = "C", source = HltbCandidateSource.MANUAL_LINK),
        )
        val encoded = json.encodeToString(ListSerializer(HltbCandidate.serializer()), candidates)
        val decoded = json.decodeFromString(ListSerializer(HltbCandidate.serializer()), encoded)
        assertEquals(candidates.map { it.source }, decoded.map { it.source })
    }

    @Test
    fun linksRejectNonPositiveIds() {
        try {
            HltbRoutes.canonicalGameUrl(0)
            assertTrue(false)
        } catch (_: IllegalArgumentException) { assertTrue(true) }
        try {
            SteamRoutes.storeUrl(0)
            assertTrue(false)
        } catch (_: IllegalArgumentException) { assertTrue(true) }
    }
}
