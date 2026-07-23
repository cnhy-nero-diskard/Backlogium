package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbMatcher
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

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
    private val json: Json,
    private val time: TimeProvider,
) {
    /** Games flagged for manual match review. */
    val needsReview: Flow<List<HltbData>> = hltbDataDao.observeNeedsReview()

    /** All cached HLTB rows (consumed by the Library for goal progress). */
    val allData: Flow<List<HltbData>> = hltbDataDao.observeAll()

    suspend fun getForGame(appId: Long): HltbData? = hltbDataDao.getByAppId(appId)

    /**
     * Cache-first: returns the existing cached row untouched when present, otherwise queries
     * HowLongToBeat, classifies, and stores the result. Returns null only on lookup failure.
     */
    suspend fun fetchForGame(appId: Long, name: String): HltbData? =
        hltbDataDao.getByAppId(appId) ?: query(appId, name)

    /** Force a network lookup regardless of cache; never clears cache on failure. */
    suspend fun refresh(appId: Long, name: String): HltbData? = query(appId, name)

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
            ),
        )
    }

    /** Deserialize the retained review candidates for a flagged game. */
    fun candidatesOf(data: HltbData): List<HltbCandidate> =
        data.candidatesJson?.let {
            runCatching { json.decodeFromString(CANDIDATE_LIST_SERIALIZER, it) }.getOrNull()
        } ?: emptyList()

    /** App ids whose cache is missing or older than the freshness window. */
    suspend fun staleOrMissingAppIds(): List<Long> =
        hltbDataDao.appIdsStaleOrMissing(time.nowMillis() - FRESHNESS_WINDOW_MILLIS)

    /**
     * Refresh HLTB data across [games] (appId → name). Without [force], only stale/missing
     * games are queried; with it, every game. Requests are spaced by a fixed delay and reuse a
     * single resolved endpoint/token (held in the data source for the run). [onProgress] is
     * invoked with (completed, total) after each query.
     */
    suspend fun refreshBatch(
        games: List<Pair<Long, String>>,
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val targets = if (force) {
            games
        } else {
            val stale = staleOrMissingAppIds().toSet()
            games.filter { it.first in stale }
        }
        targets.forEachIndexed { index, (appId, name) ->
            if (index > 0) delay(INTER_REQUEST_DELAY_MS)
            refresh(appId, name)
            onProgress(index + 1, targets.size)
        }
    }

    private suspend fun query(appId: Long, name: String): HltbData? {
        val candidates = runCatching { dataSource.search(name) }.getOrElse {
            // Lookup failed: surface via null and leave any last-good cached row intact.
            return null
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
            )

            is HltbMatcher.Classification.NeedsReview -> HltbData(
                appId = appId,
                fetchedAt = now,
                matchStatus = HltbMatchStatus.NEEDS_REVIEW,
                candidatesJson = json.encodeToString(CANDIDATE_LIST_SERIALIZER, result.candidates),
            )

            HltbMatcher.Classification.Unmatched -> HltbData(
                appId = appId,
                fetchedAt = now,
                matchStatus = HltbMatchStatus.UNMATCHED,
                candidatesJson = null,
            )
        }
        hltbDataDao.upsert(row)
        return row
    }

    companion object {
        /** Batch sweep skips games fetched more recently than this (~2 months). */
        const val FRESHNESS_WINDOW_MILLIS = 60L * 24 * 60 * 60 * 1000

        /** Fixed inter-request delay during a sweep; conservative to avoid rate limiting. */
        const val INTER_REQUEST_DELAY_MS = 1_500L

        private val CANDIDATE_LIST_SERIALIZER = ListSerializer(HltbCandidate.serializer())
    }
}
