package com.example.backlogium.data.repo

import com.example.backlogium.data.hltb.HltbCandidate
import com.example.backlogium.data.hltb.HltbCandidateSource
import com.example.backlogium.data.hltb.HltbDataSource
import com.example.backlogium.data.hltb.HltbDirectLookupResult
import com.example.backlogium.data.hltb.HltbFailureClass
import com.example.backlogium.data.hltb.HltbGameLink
import com.example.backlogium.data.hltb.HltbMatcher
import com.example.backlogium.data.hltb.HltbQueryGenerator
import com.example.backlogium.data.hltb.classifyHltbFailure
import com.example.backlogium.data.local.dao.HltbDataDao
import com.example.backlogium.data.local.entity.HltbData
import com.example.backlogium.data.local.entity.HltbDataOrigin
import com.example.backlogium.data.local.entity.HltbMatchStatus
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
    val matchStatus: HltbMatchStatus = if (candidates.isEmpty()) HltbMatchStatus.UNMATCHED else HltbMatchStatus.NEEDS_REVIEW,
)

/** Result of a user-triggered broader HLTB search (user-triggered rescue). */
sealed interface BroaderResult {
    data class Success(val candidates: List<HltbCandidate>) : BroaderResult
    data object Exhausted : BroaderResult
    data class Failed(val failureClass: HltbFailureClass) : BroaderResult
    data object NotEligible : BroaderResult
}

/** Result of a manual HLTB link preview (non-persisting). */
sealed interface ManualLinkPreviewResult {
    data class Preview(val candidate: HltbCandidate) : ManualLinkPreviewResult
    data class Invalid(val reason: String) : ManualLinkPreviewResult
    data object NotFound : ManualLinkPreviewResult
    data class Failed(val failureClass: HltbFailureClass) : ManualLinkPreviewResult
}

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
        .map { rows -> rows.map { HltbReviewGame(it.appId, candidatesOf(it), it.matchStatus) } }

    /** How many games await manual review — the Library's review badge (still review-only). */
    val reviewCount: Flow<Int> = hltbDataDao.observeNeedsReview().map { it.size }

    /** Match-center actionable set: both NEEDS_REVIEW and UNMATCHED, for rescue. */
    val matchCenterQueue: Flow<List<HltbReviewGame>> = hltbDataDao.observeMatchCenter()
        .map { rows ->
            rows.map { row ->
                HltbReviewGame(row.appId, candidatesOf(row), row.matchStatus)
            }
        }

    /** Cache-over-dataset rows from one SQLite query and one transaction snapshot. */
    val allData: Flow<List<HltbData>> = hltbDataDao.observeAllWithDataset()

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
     * User-triggered broader search: only for an existing UNMATCHED game.
     * Runs variants sequentially with the fixed inter-request delay, reuses the HLTB
     * data-source session, merges and scores against the original title, and persists
     * successful results as NEEDS_REVIEW without changing the original fetch timestamp.
     * On exhausted search or failure the UNMATCHED row is preserved.
     */
    suspend fun searchBroaderCandidates(appId: Long, originalName: String): BroaderResult {
        val existing = hltbDataDao.getByAppId(appId) ?: return BroaderResult.NotEligible
        if (existing.matchStatus != HltbMatchStatus.UNMATCHED) return BroaderResult.NotEligible

        val variants = HltbQueryGenerator.variants(originalName)
        if (variants.isEmpty()) return BroaderResult.Exhausted

        val collected = mutableListOf<HltbCandidate>()
        var lastFailure: HltbFailureClass? = null

        variants.forEachIndexed { index, query ->
            if (index > 0) delay(INTER_REQUEST_DELAY_MS)
            try {
                val raw = dataSource.search(query)
                if (raw.isNotEmpty()) {
                    // Score against original title, mark as BROADER_SEARCH
                    val scored = HltbMatcher.scoredBroader(originalName, raw)
                    collected += scored
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                lastFailure = classifyHltbFailure(failure)
                // continue to next variant; failure doesn't abort entire rescue unless no candidates found
            }
        }

        if (collected.isNotEmpty()) {
            val deduped = HltbMatcher.deduplicateBroader(collected)
            val scoredFinal = deduped.sortedByDescending { it.confidence }
            // Persist as NEEDS_REVIEW preserving fetchedAt
            val updated = HltbData(
                appId = appId,
                fetchedAt = existing.fetchedAt,
                matchStatus = HltbMatchStatus.NEEDS_REVIEW,
                candidatesJson = json.encodeToString(CANDIDATE_LIST_SERIALIZER, scoredFinal),
                origin = existing.origin,
            )
            hltbDataDao.upsert(updated)
            return BroaderResult.Success(scoredFinal)
        }

        // No candidates found: exhausted only when every broader query completed
        // successfully without candidates; any failure means the search did not
        // actually cover every variant, so it surfaces as Failed instead.
        val failure = lastFailure
        return if (failure == null) {
            BroaderResult.Exhausted
        } else {
            BroaderResult.Failed(failure)
        }
    }

    /**
     * Non-persisting manual-link preview: validates locally, performs direct-id lookup
     * through the data-source seam, and returns a MANUAL_LINK candidate for preview.
     */
    suspend fun previewLinkedCandidate(rawUrl: String): ManualLinkPreviewResult {
        val parsed = HltbGameLink.parse(rawUrl)
        if (parsed is HltbGameLink.ParseResult.Invalid) {
            return ManualLinkPreviewResult.Invalid(parsed.reason)
        }
        val valid = parsed as HltbGameLink.ParseResult.Valid
        return when (val result = dataSource.lookupById(valid.hltbId)) {
            is HltbDirectLookupResult.Success -> ManualLinkPreviewResult.Preview(
                result.candidate.copy(source = HltbCandidateSource.MANUAL_LINK),
            )
            is HltbDirectLookupResult.NotFound -> ManualLinkPreviewResult.NotFound
            is HltbDirectLookupResult.Failure -> ManualLinkPreviewResult.Failed(result.failureClass)
        }
    }

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

    /**
     * Look up every game in an explicit [games] selection (appId → name), unconditionally — an
     * explicit selection expresses intent, so there is no age or dataset-coverage threshold that
     * could exempt a game from it. Requests are spaced by a fixed delay and reuse a single
     * resolved endpoint/token (held in the data source for the run).
     *
     * [onProgress] is invoked after each query with the running count, the game just processed,
     * and its outcome. A failed outcome carries its structured failure class rather than null.
     * The explicit outcome distinguishes a stored match, a genuine no-match, and a failed lookup;
     * no consumer above `data/` has to learn about `HltbMatchStatus`.
     *
     * Note it is only called from inside the loop: an empty selection reports nothing at all, so
     * a caller rendering progress must not read "no emissions yet" as a stalled run.
     */
    suspend fun refreshSelection(
        games: List<Pair<Long, String>>,
        onProgress: suspend (done: Int, total: Int, name: String, outcome: HltbRefreshOutcome) -> Unit =
            { _, _, _, _ -> },
    ): HltbBatchResult {
        var refreshed = 0
        var noMatch = 0
        var failed = 0
        val failureClasses = mutableSetOf<HltbFailureClass>()
        games.forEachIndexed { index, (appId, name) ->
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
            onProgress(index + 1, games.size, name, outcome)
        }
        return HltbBatchResult(
            attempted = games.size,
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
        /** Fixed inter-request delay across a selection; conservative to avoid rate limiting. */
        const val INTER_REQUEST_DELAY_MS = 1_500L

        private val CANDIDATE_LIST_SERIALIZER = ListSerializer(HltbCandidate.serializer())
    }
}
