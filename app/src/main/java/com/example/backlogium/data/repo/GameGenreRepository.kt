package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.entity.GameGenreCache
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class GenreEnrichmentBatch(
    val attempted: Int,
    val hasMoreEligible: Boolean,
    val transientFailure: Boolean,
)

/**
 * Owns the local store-metadata cache and the bounded, best-effort Store refresh policy. Genres
 * are what most consumers read; the same response's app type is recorded alongside them for the
 * non-game review (add-hidden-games), which is why one fetch serves both.
 */
@Singleton
class GameGenreRepository @Inject constructor(
    private val cacheDao: GameGenreCacheDao,
    private val store: SteamStoreGenreDataSource,
    private val time: TimeProvider,
) {
    /** Raw cache rows decoded once at the repository boundary for the shared library join. */
    val allGenres: Flow<Map<Long, List<GameGenre>>> = cacheDao.observeAll().map { rows ->
        rows.associate { it.appId to GameGenreCodec.decodeOrEmpty(it.genresJson) }
    }

    /**
     * Refreshes one missing-first, bounded batch. Only definitive results are written; a Store
     * failure keeps last-known data intact and asks WorkManager to retry the chain later.
     */
    suspend fun enrichNextBatch(): GenreEnrichmentBatch {
        val staleBefore = time.nowMillis() - FRESHNESS_WINDOW_MILLIS
        val appIds = cacheDao.eligibleAppIds(staleBefore, MAX_APPS_PER_BATCH)
        var transientFailure = false
        for ((index, appId) in appIds.withIndex()) {
            if (index > 0) delay(MIN_REQUEST_SPACING_MILLIS)
            when (val result = store.genresFor(appId)) {
                is StoreGenreResult.Details -> write(appId, result.genres, result.appType)
                is StoreGenreResult.TransientFailure -> transientFailure = true
            }
            if (transientFailure) break
        }

        val hasMore = cacheDao.eligibleCount(time.nowMillis() - FRESHNESS_WINDOW_MILLIS) > 0
        return GenreEnrichmentBatch(appIds.size, hasMore, transientFailure)
    }

    private suspend fun write(appId: Long, genres: List<GameGenre>, appType: String?) {
        cacheDao.upsert(
            GameGenreCache(
                appId = appId,
                genresJson = GameGenreCodec.encode(genres),
                checkedAt = time.nowMillis(),
                appType = appType,
            ),
        )
    }

    companion object {
        const val FRESHNESS_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_APPS_PER_BATCH = 25
        const val MIN_REQUEST_SPACING_MILLIS = 500L
    }
}
