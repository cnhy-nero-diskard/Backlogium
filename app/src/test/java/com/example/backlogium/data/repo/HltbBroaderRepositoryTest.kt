package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbCandidateSource
import com.example.backlogium.data.hltb.HltbDirectLookupResult
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbFailureClass
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

class HltbBroaderRepositoryTest {

    @Test
    fun broaderSearch_notEligible_whenNoRow() = runTest {
        val repo = repoWith(dao = FakeDao())
        val result = repo.searchBroaderCandidates(1L, "Missing")
        assertTrue(result is BroaderResult.NotEligible)
    }

    @Test
    fun broaderSearch_notEligible_whenResolved() = runTest {
        val dao = FakeDao(initial = listOf(HltbData(appId = 1L, hltbId = 10L, fetchedAt = 1000L, matchStatus = HltbMatchStatus.RESOLVED)))
        val repo = repoWith(dao = dao)
        val result = repo.searchBroaderCandidates(1L, "Portal")
        assertTrue(result is BroaderResult.NotEligible)
    }

    @Test
    fun broaderSearch_successPreservesTimestampAndStoresNeedsReview() = runTest {
        val dao = FakeDao(initial = listOf(HltbData(appId = 1L, fetchedAt = 5000L, matchStatus = HltbMatchStatus.UNMATCHED)))
        val repo = repoWith(
            dao = dao,
            dataSource = object : HltbDataSource {
                override suspend fun search(name: String): List<HltbCandidate> {
                    return if (name.contains("witcher")) listOf(HltbCandidate(hltbId = 55L, name = "The Witcher 2", mainStoryMinutes = 600, imageUrl = "https://howlongtobeat.com/games/55.jpg")) else emptyList()
                }
                override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult = HltbDirectLookupResult.NotFound
            },
        )
        val result = repo.searchBroaderCandidates(1L, "The Witcher 2: Enhanced Edition")
        // Depending on variant generation, should succeed
        assertTrue(result is BroaderResult.Success)
        val row = dao.getByAppId(1L)!!
        assertEquals(HltbMatchStatus.NEEDS_REVIEW, row.matchStatus)
        assertEquals(5000L, row.fetchedAt)
        // candidates should be BROADER_SEARCH
        val candidates = Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(HltbCandidate.serializer()), row.candidatesJson!!)
        assertTrue(candidates.all { it.source == HltbCandidateSource.BROADER_SEARCH })
    }

    @Test
    fun broaderSearch_exhausted_whenNoCandidates() = runTest {
        val dao = FakeDao(initial = listOf(HltbData(appId = 1L, fetchedAt = 1000L, matchStatus = HltbMatchStatus.UNMATCHED)))
        val repo = repoWith(
            dao = dao,
            dataSource = object : HltbDataSource {
                override suspend fun search(name: String): List<HltbCandidate> = emptyList()
                override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult = HltbDirectLookupResult.NotFound
            },
        )
        val result = repo.searchBroaderCandidates(1L, "Obscure Title: Subtitle Enhanced Edition")
        assertTrue(result is BroaderResult.Exhausted)
        assertEquals(HltbMatchStatus.UNMATCHED, dao.getByAppId(1L)!!.matchStatus)
    }

    @Test
    fun broaderSearch_failed_whenAllTransportFail() = runTest {
        val dao = FakeDao(initial = listOf(HltbData(appId = 1L, fetchedAt = 1000L, matchStatus = HltbMatchStatus.UNMATCHED)))
        val repo = repoWith(
            dao = dao,
            dataSource = object : HltbDataSource {
                override suspend fun search(name: String): List<HltbCandidate> { throw IOException("fail") }
                override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult = HltbDirectLookupResult.Failure(HltbFailureClass.TRANSPORT)
            },
        )
        val result = repo.searchBroaderCandidates(1L, "Title With Edition: Subtitle")
        assertTrue(result is BroaderResult.Failed)
        assertEquals(HltbMatchStatus.UNMATCHED, dao.getByAppId(1L)!!.matchStatus)
    }

    @Test
    fun manualLinkPreview_validReturnsManualLinkCandidate() = runTest {
        val repo = repoWith(
            dataSource = object : HltbDataSource {
                override suspend fun search(name: String): List<HltbCandidate> = emptyList()
                override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult =
                    HltbDirectLookupResult.Success(HltbCandidate(hltbId = hltbId, name = "Game", mainStoryMinutes = 600, imageUrl = "https://howlongtobeat.com/games/game.jpg"))
            },
        )
        val result = repo.previewLinkedCandidate("https://howlongtobeat.com/game/123")
        assertTrue(result is ManualLinkPreviewResult.Preview)
        assertEquals(HltbCandidateSource.MANUAL_LINK, (result as ManualLinkPreviewResult.Preview).candidate.source)
    }

    @Test
    fun manualLinkPreview_invalidDoesNotTouchStorage() = runTest {
        val dao = FakeDao(initial = listOf(HltbData(appId = 1L, hltbId = 99L, fetchedAt = 1000L, matchStatus = HltbMatchStatus.RESOLVED, origin = HltbDataOrigin.MANUAL)))
        val repo = repoWith(dao = dao, dataSource = object : HltbDataSource {
            override suspend fun search(name: String): List<HltbCandidate> = emptyList()
            override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult = HltbDirectLookupResult.NotFound
        })
        val result = repo.previewLinkedCandidate("https://evil.com/game/1")
        assertTrue(result is ManualLinkPreviewResult.Invalid)
        assertEquals(99L, dao.getByAppId(1L)!!.hltbId)
    }

    @Test
    fun manualLinkConfirmWritesResolvedAndPreservesTimestampForExistingUnmatched() = runTest {
        val dao = FakeDao(initial = listOf(HltbData(appId = 1L, fetchedAt = 7777L, matchStatus = HltbMatchStatus.UNMATCHED)))
        val repo = repoWith(
            dao = dao,
            dataSource = object : HltbDataSource {
                override suspend fun search(name: String): List<HltbCandidate> = emptyList()
                override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult =
                    HltbDirectLookupResult.Success(HltbCandidate(hltbId = 555L, name = "Linked Game", mainStoryMinutes = 500, completionistMinutes = 1000, imageUrl = null))
            },
        )
        val preview = repo.previewLinkedCandidate("https://howlongtobeat.com/game/555")
        assertTrue(preview is ManualLinkPreviewResult.Preview)
        repo.resolveMatch(1L, (preview as ManualLinkPreviewResult.Preview).candidate)
        val row = dao.getByAppId(1L)!!
        assertEquals(HltbMatchStatus.RESOLVED, row.matchStatus)
        assertEquals(555L, row.hltbId)
        assertEquals(7777L, row.fetchedAt)
        assertEquals(HltbDataOrigin.MANUAL, row.origin)
    }

    private fun repoWith(
        dao: FakeDao = FakeDao(),
        dataSource: HltbDataSource = object : HltbDataSource {
            override suspend fun search(name: String): List<HltbCandidate> = emptyList()
            override suspend fun lookupById(hltbId: Long): HltbDirectLookupResult = HltbDirectLookupResult.NotFound
        },
    ) = HltbRepository(dataSource, dao, FakeLookup(), Json, FixedTime)

    private class FakeLookup : HltbDatasetLookup {
        override suspend fun find(appId: Long): HltbData? = null
        override suspend fun getAll(): List<HltbData> = emptyList()
        override fun observeAll(): Flow<List<HltbData>> = flowOf(emptyList())
    }

    private class FakeDao(initial: List<HltbData> = emptyList()) : HltbDataDao {
        private val store = initial.associateBy { it.appId }.toMutableMap()
        override suspend fun upsert(data: HltbData) { store[data.appId] = data }
        override suspend fun upsertAll(data: List<HltbData>) { data.forEach { upsert(it) } }
        override suspend fun deleteDatasetRows() { store.values.removeAll { it.origin == HltbDataOrigin.DATASET } }
        override suspend fun getByAppId(appId: Long): HltbData? = store[appId]
        override fun observeAll(): Flow<List<HltbData>> = flowOf(store.values.toList())
        override suspend fun getAll(): List<HltbData> = store.values.toList()
        override fun observeAllWithDataset(): Flow<List<HltbData>> = flowOf(store.values.toList())
        override suspend fun getAllWithDataset(): List<HltbData> = store.values.toList()
        override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW })
        override fun observeMatchCenter(): Flow<List<HltbData>> = flowOf(store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW || it.matchStatus == HltbMatchStatus.UNMATCHED })
        override suspend fun getMatchCenter(): List<HltbData> = store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW || it.matchStatus == HltbMatchStatus.UNMATCHED }
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 2000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-27")
    }
}
