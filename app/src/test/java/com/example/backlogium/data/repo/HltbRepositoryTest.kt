package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun reportsEachGameWithItsOutcome() = runTest {
        val repository = repository(
            results = mapOf(
                "Portal" to listOf(candidate("Portal")), // exact name → resolves
                "Hades" to listOf(candidate("Hades II"), candidate("Hades III")), // ambiguous
                "Obscure Indie" to emptyList(), // searched fine, nothing there
            ),
        )

        val reported = mutableListOf<Triple<String, HltbMatchState?, Pair<Int, Int>>>()
        repository.refreshBatch(
            games = listOf(1L to "Portal", 2L to "Hades", 3L to "Obscure Indie"),
            force = true,
        ) { done, total, name, outcome ->
            reported += Triple(name, outcome, done to total)
        }

        assertEquals(
            listOf(
                Triple("Portal", HltbMatchState.RESOLVED, 1 to 3),
                Triple("Hades", HltbMatchState.NEEDS_REVIEW, 2 to 3),
                Triple("Obscure Indie", HltbMatchState.UNMATCHED, 3 to 3),
            ),
            reported,
        )
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

        val outcomes = mutableMapOf<String, HltbMatchState?>()
        repository.refreshBatch(
            games = listOf(1L to "Transport Broken", 2L to "Nothing Found"),
            force = true,
        ) { _, _, name, outcome -> outcomes[name] = outcome }

        // Null (lookup failed) is not UNMATCHED (searched, found nothing).
        assertNull(outcomes["Transport Broken"])
        assertEquals(HltbMatchState.UNMATCHED, outcomes["Nothing Found"])
        // The failure wrote nothing: the game's last-good completion length survives.
        assertEquals(3_000, dao.getByAppId(1L)?.completionistMinutes)
        assertEquals(HltbMatchStatus.RESOLVED, dao.getByAppId(1L)?.matchStatus)
    }

    @Test
    fun anEmptyTargetSetReportsNothingAtAll() = runTest {
        // The freshness gate can filter every game out. `onProgress` is only called from inside the
        // loop, so nothing is reported — a caller rendering progress must not read the absence of
        // emissions as a stalled run.
        val repository = repository(results = mapOf("Portal" to listOf(candidate("Portal"))))

        var calls = 0
        repository.refreshBatch(games = listOf(1L to "Portal"), force = false) { _, _, _, _ ->
            calls++
        }

        assertEquals(0, calls)
    }

    private fun repository(
        dao: FakeHltbDataDao = FakeHltbDataDao(),
        results: Map<String, List<HltbCandidate>> = emptyMap(),
        failing: Set<String> = emptySet(),
    ) = HltbRepository(
        dataSource = FakeHltbDataSource(results, failing),
        hltbDataDao = dao,
        json = Json,
        time = FixedTime,
    )

    private fun candidate(name: String) = HltbCandidate(
        hltbId = name.hashCode().toLong(),
        name = name,
        completionistMinutes = 3_600,
    )

    /** Returns configured candidates by query; throws for [failing] names (transport failure). */
    private class FakeHltbDataSource(
        private val results: Map<String, List<HltbCandidate>>,
        private val failing: Set<String>,
    ) : HltbDataSource {
        override suspend fun search(name: String): List<HltbCandidate> {
            if (name in failing) throw IOException("transport failed")
            return results[name].orEmpty()
        }
    }

    /**
     * In-memory cache. [appIdsStaleOrMissing] returns nothing, so an unforced sweep has no
     * targets — the freshness gate's "everything is fresh" case.
     */
    private class FakeHltbDataDao(initial: List<HltbData> = emptyList()) : HltbDataDao {
        private val store = initial.associateBy { it.appId }.toMutableMap()

        override suspend fun upsert(data: HltbData) {
            store[data.appId] = data
        }

        override suspend fun getByAppId(appId: Long): HltbData? = store[appId]
        override fun observeAll(): Flow<List<HltbData>> = flowOf(store.values.toList())
        override suspend fun getAll(): List<HltbData> = store.values.toList()
        override fun observeNeedsReview(): Flow<List<HltbData>> = flowOf(
            store.values.filter { it.matchStatus == HltbMatchStatus.NEEDS_REVIEW },
        )

        override suspend fun appIdsStaleOrMissing(cutoff: Long): List<Long> = emptyList()
    }

    private object FixedTime : TimeProvider {
        override fun nowMillis(): Long = 2_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-07-27")
    }
}
