package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbFailureClass
import com.example.backlogium.data.hltb.HltbMatcher
import com.example.backlogium.data.hltb.classifyHltbFailure
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A game awaiting manual match selection, with its retained candidates already deserialized.
 * [HltbCandidate] crosses the boundary as-is: it is a plain serializable class in `data.hltb`,
 * not a Room entity, and is exactly the shape the review surface needs.
 */
data class HltbReviewGame(
    val appId: Long,
    val candidates: List<HltbCandidate>,
)

/**
 * Owns HowLongToBeat lookups, name-match classification, and the local cache. All consumers
 * (goal tagging, batch refresh, review) go through here; none touch [HltbDataSource] directly.
 *
 * A lookup failure never overwrites or clears the affected game's cached row — failures are
 * surfaced by returning null so last-good data survives.
 */
@Singleton
class HltbRepository @Inject constructor(
    private val dataSource: HltbDataSource,
    private val hltbDataDao: HltbDataDao,
    private val datasetLookup: HltbDatasetLookup,
    private val json: Json,
    private val time: TimeProvider,
) {
    /** Games flagged for manual match review, with their candidates. */
    val reviewQueue: Flow<List<HltbReviewGame>> = hltbDataDao.observeNeedsReview()
        .map { rows -> rows.map { HltbReviewGame(it.appId, candidatesOf(it)) } }

    /** How many games await manual review — the Library's review badge. */
    val reviewCount: Flow<Int> = hltbDataDao.observeNeedsReview().map { it.size }

    /** Cached rows overlaid onto the locally applied dataset; no network work occurs here. */
    val allData: Flow<List<HltbData>> = combine(
        datasetLookup.observeAll(),
        hltbDataDao.observeAll(),
    ) { datasetRows, cachedRows ->
        buildMap<Long, HltbData> {
            datasetRows.forEach { put(it.appId, it) }
            cachedRows.forEach { put(it.appId, it) }
        }.values.toList()
    }

    suspend fun getForGame(appId: Long): HltbData? = hltbDataDao.getByAppId(appId)

    /**
     * Cache first, then the applied dataset, and only then the live source. A dataset hit is
     * materialized in the cache without contacting HowLongToBeat. Returns null only on lookup
     * failure.
     */
    suspend fun fetchForGame(appId: Long, name: String): HltbData? {
        hltbDataDao.getByAppId(appId)?.let { return it }
        datasetLookup.find(appId)?.let { datasetRow ->
            hltbDataDao.upsert(datasetRow)
            return datasetRow
        }
        return query(appId, name)
    }

    /** Force a network lookup regardless of cache; never clears cache on failure. */
    suspend fun refresh(appId: Long, name: String): HltbData? = query(appId, name)

    /** Search and score candidates for a fresh pick without changing any stored HLTB row. */
    suspend fun searchCandidates(name: String): List<HltbCandidate> =
        HltbMatcher.scored(name, dataSource.search(name))

    /**
     * Resolve a review-flagged game to the [chosen] candidate: store its id and completion
     * lengths, mark [HltbMatchStatus.RESOLVED], and drop the retained candidates.
     */
    suspend fun resolveMatch(appId: Long, chosen: HltbCandidate) {
        val existing = hltbDataDao.getByAppId(appId)
        hltbDataDao.upsert(
            HltbData(
                appId = appId,
                hltbId = chosen.hltbId,
                mainStoryMinutes = chosen.mainStoryMinutes,
                mainExtraMinutes = chosen.mainExtraMinutes,
                completionistMinutes = chosen.completionistMinutes,
                allStylesMinutes = chosen.allStylesMinutes,
                fetchedAt = existing?.fetchedAt ?: time.nowMillis(),
                matchStatus = HltbMatchStatus.RESOLVED,
                candidatesJson = null,
                origin = HltbDataOrigin.MANUAL,
            ),
        )
    }

    /** Deserialize the retained review candidates for a flagged game. */
    private fun candidatesOf(data: HltbData): List<HltbCandidate> =
        data.candidatesJson?.let {
            runCatching { json.decodeFromString(CANDIDATE_LIST_SERIALIZER, it) }.getOrNull()
        } ?: emptyList()

    /** App ids whose cache is missing or older than the freshness window. */
    suspend fun staleOrMissingAppIds(): List<Long> =
        hltbDataDao.appIdsStaleOrMissing(time.nowMillis() - FRESHNESS_WINDOW_MILLIS)

    /**
     * Refresh HLTB data across [games] (appId → name). Without [force], only stale/missing
     * games are queried; with it, every game. Requests are spaced by a fixed delay and reuse a
     * single resolved endpoint/token (held in the data source for the run).
     *
     * [onProgress] is invoked after each query with the running count, the game just processed,
     * and its outcome. A failed outcome carries its structured failure class rather than null.
     * The explicit outcome distinguishes a stored match, a genuine no-match, and a failed lookup;
     * no consumer above `data/` has to learn about `HltbMatchStatus`.
     *
     * Note it is only called from inside the loop: an empty target set reports nothing at all, so
     * a caller rendering progress must not read "no emissions yet" as a stalled run.
     */
    suspend fun refreshBatch(
        games: List<Pair<Long, String>>,
        force: Boolean,
        onProgress: suspend (done: Int, total: Int, name: String, outcome: HltbRefreshOutcome) -> Unit =
            { _, _, _, _ -> },
    ): HltbBatchResult {
        val targets = if (force) {
            games
        } else {
            val stale = staleOrMissingAppIds().toSet()
            games.filter { it.first in stale }
        }
        var refreshed = 0
        var noMatch = 0
        var failed = 0
        val failureClasses = mutableSetOf<HltbFailureClass>()
        targets.forEachIndexed { index, (appId, name) ->
            if (index > 0) delay(INTER_REQUEST_DELAY_MS)
            val outcome = queryResult(appId, name).outcome
            when (outcome) {
                is HltbRefreshOutcome.Refreshed -> refreshed++
                HltbRefreshOutcome.NoMatch -> noMatch++
                is HltbRefreshOutcome.Failed -> {
                    failed++
                    failureClasses += outcome.failureClass
                }
            }
            onProgress(index + 1, targets.size, name, outcome)
        }
        return HltbBatchResult(
            attempted = targets.size,
            refreshed = refreshed,
            noMatch = noMatch,
            failed = failed,
            failureClasses = failureClasses,
        )
    }

    private suspend fun query(appId: Long, name: String): HltbData? {
        return queryResult(appId, name).row
    }

    private data class QueryResult(
        val row: HltbData?,
        val outcome: HltbRefreshOutcome,
    )

    private suspend fun queryResult(appId: Long, name: String): QueryResult {
        val candidates = try {
            dataSource.search(name)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            return QueryResult(
                row = null,
                outcome = HltbRefreshOutcome.Failed(classifyHltbFailure(failure)),
            )
        }

        val now = time.nowMillis()
        val row = when (val result = HltbMatcher.classify(name, candidates)) {
            is HltbMatcher.Classification.Resolved -> HltbData(
                appId = appId,
                hltbId = result.chosen.hltbId,
                mainStoryMinutes = result.chosen.mainStoryMinutes,
                mainExtraMinutes = result.chosen.mainExtraMinutes,
                completionistMinutes = result.chosen.completionistMinutes,
                allStylesMinutes = result.chosen.allStylesMinutes,
                fetchedAt = now,
                matchStatus = HltbMatchStatus.RESOLVED,
                candidatesJson = null,
                origin = HltbDataOrigin.AUTOMATIC,
            )

            is HltbMatcher.Classification.NeedsReview -> HltbData(
                appId = appId,
                fetchedAt = now,
                matchStatus = HltbMatchStatus.NEEDS_REVIEW,
                candidatesJson = json.encodeToString(CANDIDATE_LIST_SERIALIZER, result.candidates),
                origin = HltbDataOrigin.AUTOMATIC,
            )

            HltbMatcher.Classification.Unmatched -> HltbData(
                appId = appId,
                fetchedAt = now,
                matchStatus = HltbMatchStatus.UNMATCHED,
                candidatesJson = null,
                origin = HltbDataOrigin.AUTOMATIC,
            )
        }
        hltbDataDao.upsert(row)
        return QueryResult(
            row = row,
            outcome = when (row.matchStatus) {
                HltbMatchStatus.UNMATCHED -> HltbRefreshOutcome.NoMatch
                HltbMatchStatus.RESOLVED -> HltbRefreshOutcome.Refreshed(HltbMatchState.RESOLVED)
                HltbMatchStatus.NEEDS_REVIEW ->
                    HltbRefreshOutcome.Refreshed(HltbMatchState.NEEDS_REVIEW)
            },
        )
    }

    companion object {
        /** Batch sweep skips games fetched more recently than this (~2 months). */
        const val FRESHNESS_WINDOW_MILLIS = 60L * 24 * 60 * 60 * 1000

        /** Fixed inter-request delay during a sweep; conservative to avoid rate limiting. */
        const val INTER_REQUEST_DELAY_MS = 1_500L

        private val CANDIDATE_LIST_SERIALIZER = ListSerializer(HltbCandidate.serializer())
    }
}
