package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.SteamReviewCacheDao
import com.example.backlogium.data.local.entity.SteamReviewCache
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A game's cached Steam review summary as consumers above `data/` see it.
 *
 * There are three states, and only two of them have a value here. **Missing is represented by
 * absence from the map**, not by a third constant — a game nobody has asked about yet is a gap in
 * the cache, and making it a value invites a consumer to render it. [Unavailable] is the separate
 * fact that Steam *was* asked and this game has no reviews, which is why it is cacheable and
 * refresh-suppressing while missing is not.
 *
 * Neither state is ever a zero rating. A game with no reviews and a game reviewed badly are
 * different things, and the ranking engine substitutes an explicit neutral for the first rather
 * than letting an absence read as a judgement (add-gap-plan-suggestions).
 */
sealed interface GameReviewSummary {
    /**
     * @param description Steam's own phrase — "Very Positive", "Mixed" — retained verbatim so the
     *   explanation shows the player the wording they already recognise rather than a number the
     *   app invented.
     * @param total Steam's own total, which is not always `positive + negative`.
     */
    data class Available(
        val description: String,
        val positive: Int,
        val negative: Int,
        val total: Int,
    ) : GameReviewSummary

    /** Checked, and Steam reports no usable review data for this game. */
    data object Unavailable : GameReviewSummary
}

/** One bounded review-enrichment pass, as the worker needs to read it. */
data class ReviewEnrichmentBatch(
    val attempted: Int,
    val hasMoreEligible: Boolean,
    val transientFailure: Boolean,
)

/**
 * Owns the local Steam review cache and exposes it as domain values.
 *
 * Deliberately a second repository rather than more surface on [GameGenreRepository]: reviews and
 * `appdetails` come from different endpoints that succeed, fail, and go stale independently, and
 * folding them together would make one endpoint's outage stop the other's durable progress.
 */
@Singleton
class SteamReviewRepository @Inject constructor(
    private val cacheDao: SteamReviewCacheDao,
    private val store: SteamStoreReviewDataSource,
    private val time: TimeProvider,
) {
    /**
     * Cached review summaries per app id, decoded once at the repository boundary. A game with no
     * cache row is absent from the map, so no consumer can mistake "never checked" for "no
     * reviews" — the distinction is carried by the map's shape rather than by a flag a caller
     * could forget to read. A declined-attempt row is also absent: it exists only to advance the
     * enrichment queue and is not a review fact.
     */
    val allReviews: Flow<Map<Long, GameReviewSummary>> = cacheDao.observeAll().map { rows ->
        rows.mapNotNull { row -> row.toDomain()?.let { row.appId to it } }.toMap()
    }

    /**
     * Refreshes one missing-first, bounded batch, mirroring the Store genre chain's policy so the
     * two place a predictable combined load on one host.
     *
     * Only the two definitive outcomes become review facts. A [StoreReviewResult.Declined] app id
     * gets a durable cooldown marker and the batch *continues* — one app id the Store will not
     * describe is not a reason to abandon progress on the other twenty-four — while a transient
     * failure stops the batch so WorkManager backs off instead of hammering a Store that is already
     * throttling.
     */
    suspend fun enrichNextBatch(): ReviewEnrichmentBatch {
        val staleBefore = time.nowMillis() - FRESHNESS_WINDOW_MILLIS
        val appIds = cacheDao.eligibleAppIds(staleBefore, MAX_APPS_PER_BATCH)
        var transientFailure = false
        for ((index, appId) in appIds.withIndex()) {
            if (index > 0) delay(MIN_REQUEST_SPACING_MILLIS)
            when (val result = store.reviewsFor(appId)) {
                is StoreReviewResult.Summary -> write(appId, result)
                StoreReviewResult.Unavailable -> writeUnavailable(appId)
                // Says nothing about the game: retain only a cooldown marker, not an unavailable
                // review fact, so the rest of the missing queue can make progress.
                StoreReviewResult.Declined -> writeDeclined(appId)
                is StoreReviewResult.TransientFailure -> transientFailure = true
            }
            if (transientFailure) break
        }

        val hasMore = cacheDao.eligibleCount(time.nowMillis() - FRESHNESS_WINDOW_MILLIS) > 0
        return ReviewEnrichmentBatch(appIds.size, hasMore, transientFailure)
    }

    private suspend fun write(appId: Long, summary: StoreReviewResult.Summary) = cacheDao.upsert(
        SteamReviewCache(
            appId = appId,
            description = summary.description,
            positive = summary.positive,
            negative = summary.negative,
            total = summary.total,
            available = true,
            checkedAt = time.nowMillis(),
            declinedAt = null,
        ),
    )

    /** Counts stay null: an unavailable row must never be able to present itself as zero reviews. */
    private suspend fun writeUnavailable(appId: Long) = cacheDao.upsert(
        SteamReviewCache(
            appId = appId,
            available = false,
            checkedAt = time.nowMillis(),
            declinedAt = null,
        ),
    )

    /** Retains queue progress without turning a refused Store envelope into a reviewless fact. */
    private suspend fun writeDeclined(appId: Long) {
        val now = time.nowMillis()
        cacheDao.upsert(
            SteamReviewCache(
                appId = appId,
                available = false,
                checkedAt = now,
                declinedAt = now,
            ),
        )
    }

    companion object {
        /** Matches the Store genre chain, for one predictable freshness rule across both caches. */
        const val FRESHNESS_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_APPS_PER_BATCH = 25
        const val MIN_REQUEST_SPACING_MILLIS = 500L
    }
}

/**
 * A stored row to its domain state. A row with [SteamReviewCache.declinedAt] is queue bookkeeping,
 * not a review fact, so it is omitted. Otherwise [SteamReviewCache.available] is authoritative: a
 * row written as available always carries its counts, and a row written as unavailable never does.
 * A row that somehow claims availability without counts is read as [GameReviewSummary.Unavailable]
 * rather than being padded with zeros, because a fabricated zero is exactly the rating this cache
 * must never produce.
 */
private fun SteamReviewCache.toDomain(): GameReviewSummary? {
    if (declinedAt != null) return null
    if (!available) return GameReviewSummary.Unavailable
    val description = description?.trim().orEmpty()
    val positive = positive
    val negative = negative
    val total = total
    if (description.isEmpty() || positive == null || negative == null || total == null) {
        return GameReviewSummary.Unavailable
    }
    return GameReviewSummary.Available(
        description = description,
        positive = positive,
        negative = negative,
        total = total,
    )
}
