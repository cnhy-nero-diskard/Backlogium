package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbFailureClass
import com.example.backlogium.data.hltb.HltbMatcher
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * What the batch sweep reports as it proceeds, which is what the Library's live log renders.
 *
 * The distinction the log depends on is the one asserted hardest: a lookup that *failed* (nothing
 * was learned, cached data survives) versus a lookup that succeeded and found no candidates (a
 * real answer). Collapsing the two would tell a user their game has no HowLongToBeat entry when
 * the request never got through.
 */
class HltbRepositoryTest {
    @Test
    fun fetchForGame_usesCacheBeforeDatasetAndNetwork() = runTest {
        val cached = HltbData(
            appId = 1L,
            hltbId = 11L,
            completionistMinutes = 100,
            fetchedAt = 1_000L,
            matchStatus = HltbMatchStatus.RESOLVED,
            origin = HltbDataOrigin.AUTOMATIC,
        )
        val dataset = cached.copy(
            hltbId = 22L,
            completionistMinutes = 200,
            origin = HltbDataOrigin.DATASET,
        )
        val repository = repository(
            dao = FakeHltbDataDao(listOf(cached)),
            datasetRows = mapOf(1L to dataset),
            failing = setOf("Portal"),
        )

        assertEquals(cached, repository.fetchForGame(1L, "Portal"))
    }

    @Test
    fun fetchForGame_materializesDatasetBeforeTryingNetwork() = runTest {
        val dao = FakeHltbDataDao()
        val dataset = HltbData(
            appId = 1L,
            hltbId = 22L,
            mainStoryMinutes = null,
            fetchedAt = 1_234L,
            matchStatus = HltbMatchStatus.RESOLVED,
            origin = HltbDataOrigin.DATASET,
        )
        val repository = repository(
            dao = dao,
            datasetRows = mapOf(1L to dataset),
            failing = setOf("Portal"),
        )

        assertEquals(dataset, repository.fetchForGame(1L, "Portal"))
        assertEquals(dataset, dao.getByAppId(1L))
    }

    @Test
    fun fetchForGame_queriesNetworkOnlyWhenCacheAndDatasetMiss() = runTest {
        val repository = repository(results = mapOf("Portal" to listOf(candidate("Portal"))))

        val result = repository.fetchForGame(1L, "Portal")

        assertEquals(HltbDataOrigin.AUTOMATIC, result?.origin)
        assertEquals(HltbMatchStatus.RESOLVED, result?.matchStatus)
    }

    @Test
    fun allDataIncludesGameAddedAfterDatasetApplicationWithoutNetworkLookup() = runTest {
        // allData reads hltbDataDao.observeAllWithDataset() directly (one Room query joining the
        // cache table with the normalized dataset tables), so this exercises the DAO-level
        // cache-over-dataset precedence rather than HltbRepository's own dataset lookup seam.
        val cached = HltbData(
            appId = 1L,
            hltbId = 11L,
            completionistMinutes = 111,
            fetchedAt = 2_000L,
            matchStatus = HltbMatchStatus.RESOLVED,
            origin = HltbDataOrigin.MANUAL,
        )
        val datasetOnly = HltbData(
            appId = 99L,
            hltbId = 990L,
            completionistMinutes = 900,
            fetchedAt = 1_234L,
            matchStatus = HltbMatchStatus.RESOLVED,
            origin = HltbDataOrigin.DATASET,
        )
        val dao = FakeHltbDataDao(
            initial = listOf(cached),
            datasetOnlyRows = mapOf(
                1L to cached.copy(
                    hltbId = 12L,
                    completionistMinutes = 222,
                    origin = HltbDataOrigin.DATASET,
                ),
                99L to datasetOnly,
            ),
        )
        val repository = repository(dao = dao)

        val allData = repository.allData.first().associateBy(HltbData::appId)
        assertEquals(cached, allData[1L])
        assertEquals(datasetOnly, allData[99L])
    }

    @Test
    fun lookupWritesAutomaticOriginAndReviewResolutionWritesManualOrigin() = runTest {
        val dao = FakeHltbDataDao()
        val repository = repository(
            dao = dao,
            results = mapOf(
                "Portal" to listOf(candidate("Portal")),
                "Ambiguous" to listOf(candidate("First"), candidate("Second", id = 2L)),
                "Missing" to emptyList(),
            ),
        )

        repository.refresh(1L, "Portal")
        repository.refresh(2L, "Ambiguous")
        repository.refresh(3L, "Missing")

        assertEquals(HltbDataOrigin.AUTOMATIC, dao.getByAppId(1L)?.origin)
        assertEquals(HltbDataOrigin.AUTOMATIC, dao.getByAppId(2L)?.origin)
        assertEquals(HltbDataOrigin.AUTOMATIC, dao.getByAppId(3L)?.origin)

        repository.resolveMatch(2L, candidate("Chosen", id = 22L))

        assertEquals(HltbDataOrigin.MANUAL, dao.getByAppId(2L)?.origin)
    }

    @Test
    fun searchCandidates_returnsAllScoredCandidatesWithoutClassifying() = runTest {
        val repository = repository(
            results = mapOf(
                "Portal" to listOf(candidate("Portal"), candidate("Portal 2", id = 2L)),
            ),
        )

        val candidates = repository.searchCandidates("Portal")

        assertEquals(2, candidates.size)
        assertEquals("Portal", candidates.first().name)
        assertTrue(candidates.first().confidence >= HltbMatcher.CONFIDENT_THRESHOLD)
    }

    @Test
    fun searchCandidates_leavesExistingResolvedRowUntouched() = runTest {
        val existing = HltbData(
            appId = 1L,
            hltbId = 99L,
            completionistMinutes = 3_000,
            fetchedAt = 1_000L,
            matchStatus = HltbMatchStatus.RESOLVED,
        )
        val dao = FakeHltbDataDao(initial = listOf(existing))
        val repository = repository(
            dao = dao,
            results = mapOf("Portal" to listOf(candidate("Portal"))),
        )

        repository.searchCandidates("Portal")

        assertEquals(existing, dao.getByAppId(1L))
    }

    @Test
    fun failedSearchCandidates_leavesExistingRowUntouched() = runTest {
        val existing = HltbData(
            appId = 1L,
            hltbId = 99L,
            completionistMinutes = 3_000,
            fetchedAt = 1_000L,
            matchStatus = HltbMatchStatus.RESOLVED,
        )
        val dao = FakeHltbDataDao(initial = listOf(existing))
        val repository = repository(dao = dao, failing = setOf("Transport Broken"))

        val failure = runCatching { repository.searchCandidates("Transport Broken") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(existing, dao.getByAppId(1L))
    }

    @Test
    fun oldCandidatePayload_deserializesWithoutImage() = runTest {
        val dao = FakeHltbDataDao(
            initial = listOf(
                HltbData(
                    appId = 1L,
                    fetchedAt = 1_000L,
                    matchStatus = HltbMatchStatus.NEEDS_REVIEW,
                    candidatesJson = """[{"hltbId":7,"name":"Portal","completionistMinutes":600,"confidence":0.9}]""",
                ),
            ),
        )
        val repository = repository(dao = dao)

        val candidate = repository.reviewQueue.first().single().candidates.single()

        assertNull(candidate.imageUrl)
    }

    @Test
    fun reportsEachGameWithItsOutcome() = runTest {
        val repository = repository(
            results = mapOf(
                "Portal" to listOf(candidate("Portal")), // exact name → resolves
                "Hades" to listOf(candidate("Hades II"), candidate("Hades III")), // ambiguous
                "Obscure Indie" to emptyList(), // searched fine, nothing there
            ),
        )

        val reported = mutableListOf<Triple<String, HltbRefreshOutcome, Pair<Int, Int>>>()
        val result = repository.refreshSelection(
            games = listOf(1L to "Portal", 2L to "Hades", 3L to "Obscure Indie"),
        ) { done, total, name, outcome ->
            reported += Triple(name, outcome, done to total)
        }

        assertEquals(
            listOf(
                Triple("Portal", HltbRefreshOutcome.Refreshed(HltbMatchState.RESOLVED), 1 to 3),
                Triple("Hades", HltbRefreshOutcome.Refreshed(HltbMatchState.NEEDS_REVIEW), 2 to 3),
                Triple("Obscure Indie", HltbRefreshOutcome.NoMatch, 3 to 3),
            ),
            reported,
        )
        assertEquals(3, result.attempted)
        assertEquals(2, result.refreshed)
        assertEquals(1, result.noMatch)
        assertEquals(0, result.failed)
    }

    @Test
    fun failedLookupIsDistinctFromNoMatch_andLeavesCachedDataIntact() = runTest {
        val dao = FakeHltbDataDao(
            initial = listOf(
                HltbData(
                    appId = 1L,
                    completionistMinutes = 3_000,
                    fetchedAt = 1_000L,
                    matchStatus = HltbMatchStatus.RESOLVED,
                ),
            ),
        )
        val repository = repository(
            dao = dao,
            results = mapOf("Nothing Found" to emptyList()),
            failing = setOf("Transport Broken"),
        )

        val outcomes = mutableMapOf<String, HltbRefreshOutcome>()
        val result = repository.refreshSelection(
            games = listOf(1L to "Transport Broken", 2L to "Nothing Found"),
        ) { _, _, name, outcome -> outcomes[name] = outcome }

        val failure = outcomes["Transport Broken"] as HltbRefreshOutcome.Failed
        assertEquals(HltbFailureClass.TRANSPORT, failure.failureClass)
        assertEquals(HltbRefreshOutcome.NoMatch, outcomes["Nothing Found"])
        assertEquals(2, result.attempted)
        assertEquals(0, result.refreshed)
        assertEquals(1, result.noMatch)
        assertEquals(1, result.failed)
        assertTrue(result.shouldRetry)
        // The failure wrote nothing: the game's last-good completion length survives.
        assertEquals(3_000, dao.getByAppId(1L)?.completionistMinutes)
        assertEquals(HltbMatchStatus.RESOLVED, dao.getByAppId(1L)?.matchStatus)
    }

    @Test
    fun cancellationPropagatesFromBatchLookup() = runTest {
        val repository = repository(cancellation = setOf("Cancelled"))

        val failure = runCatching {
            repository.refreshSelection(games = listOf(1L to "Cancelled"))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    @Test
    fun anEmptySelectionReportsNothingAtAll() = runTest {
        // `onProgress` is only called from inside the loop, so an empty selection reports
        // nothing — a caller rendering progress must not read the absence of emissions as a
        // stalled run.
        val repository = repository(results = mapOf("Portal" to listOf(candidate("Portal"))))

        var calls = 0
        repository.refreshSelection(games = emptyList()) { _, _, _, _ ->
            calls++
        }

        assertEquals(0, calls)
    }

    private fun repository(
        dao: FakeHltbDataDao = FakeHltbDataDao(),
        results: Map<String, List<HltbCandidate>> = emptyMap(),
        failing: Set<String> = emptySet(),
        cancellation: Set<String> = emptySet(),
        datasetRows: Map<Long, HltbData> = emptyMap(),
        searches: MutableList<String>? = null,
    ) = HltbRepository(
        dataSource = FakeHltbDataSource(results, failing, cancellation, searches),
        hltbDataDao = dao,
        datasetLookup = FakeHltbDatasetLookup(datasetRows),
        json = Json,
        time = FixedTime,
    )

    private fun candidate(name: String, id: Long = name.hashCode().toLong()) = HltbCandidate(
        hltbId = id,
        name = name,
        completionistMinutes = 3_600,
    )

    /** Returns configured candidates by query; throws for [failing] names (transport failure). */
    private class FakeHltbDataSource(
        private val results: Map<String, List<HltbCandidate>>,
        private val failing: Set<String>,
        private val cancellation: Set<String>,
        private val searches: MutableList<String>?,
        private val lookupResults: Map<Long, HltbCandidate> = emptyMap(),
        private val lookupFailures: Set<Long> = emptySet(),
        private val lookupNotFound: Set<Long> = emptySet(),
    ) : HltbDataSource {
        override suspend fun search(name: String): List<HltbCandidate> {
            searches?.add(name)
            if (name in cancellation) throw CancellationException("cancelled")
            if (name in failing) throw IOException("transport failed")
            return results[name].orEmpty()
        }
        override suspend fun lookupById(hltbId: Long): com.example.backlogium.data.hltb.HltbDirectLookupResult {
            if (hltbId in lookupNotFound) return com.example.backlogium.data.hltb.HltbDirectLookupResult.NotFound
            if (hltbId in lookupFailures) return com.example.backlogium.data.hltb.HltbDirectLookupResult.Failure(com.example.backlogium.data.hltb.HltbFailureClass.TRANSPORT)
            val cand = lookupResults[hltbId] ?: return com.example.backlogium.data.hltb.HltbDirectLookupResult.NotFound
            return com.example.backlogium.data.hltb.HltbDirectLookupResult.Success(cand)
        }
    }

    private class FakeHltbDatasetLookup(
        private val rows: Map<Long, HltbData>,
    ) : HltbDatasetLookup {
        override suspend fun find(appId: Long): HltbData? = rows[appId]
        override suspend fun getAll(): List<HltbData> = rows.values.toList()
        override fun observeAll(): Flow<List<HltbData>> = flowOf(rows.values.toList())
    }

    /** In-memory cache. */
    private class FakeHltbDataDao(
        initial: List<HltbData> = emptyList(),
        /** Rows visible only through the cache-over-dataset read, mirroring the normalized dataset tables. */
        private val datasetOnlyRows: Map<Long, HltbData> = emptyMap(),
    ) : HltbDataDao {
        private val store = initial.associateBy { it.appId }.toMutableMap()

        override suspend fun upsert(data: HltbData) {
            store[data.appId] = data
        }

        override suspend fun upsertAll(data: List<HltbData>) {
            data.forEach { upsert(it) }
        }

        override suspend fun deleteDatasetRows() {
            store.values.removeAll { it.origin == HltbDataOrigin.DATASET }
        }

        override suspend fun getByAppId(appId: Long): HltbData? = store[appId]
        override fun observeAll(): Flow<List<HltbData>> = flowOf(store.values.toList())
        override suspend fun getAll(): List<HltbData> = store.values.toList()

        override fun observeAllWithDataset(): Flow<List<HltbData>> = flowOf(withDatasetRows())
        override suspend fun getAllWithDataset(): List<HltbData> = withDatasetRows()

        override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(
            store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW },
        )
        override fun observeMatchCenter(): Flow<List<HltbData>> = flowOf(
            store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW || it.matchStatus == HltbMatchStatus.UNMATCHED },
        )
        override suspend fun getMatchCenter(): List<HltbData> =
            store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW || it.matchStatus == HltbMatchStatus.UNMATCHED }

        private fun withDatasetRows(): List<HltbData> =
            store.values.toList() + datasetOnlyRows.filterKeys { it !in store }.values
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 2_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-27")
    }
}
