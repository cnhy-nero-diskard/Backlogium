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
     * Participation categories per app id, for the gap-plan multiplayer classification.
     *
     * A game is absent from the map when it has no cache row, and maps to **null** when its row
     * carries no category payload — both mean *unknown*. Only a present, non-null, possibly empty
     * list is an answer, so no consumer can reach "advertises none" by accident
     * (add-gap-plan-suggestions).
     */
    val allCategories: Flow<Map<Long, List<GameCategory>?>> = cacheDao.observeAll().map { rows ->
        rows.associate { it.appId to GameCategoryCodec.decodeOrNull(it.categoriesJson) }
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
                is StoreGenreResult.Details ->
                    write(appId, result.genres, result.appType, result.categories)
                is StoreGenreResult.TransientFailure -> transientFailure = true
            }
            if (transientFailure) break
        }

        val hasMore = cacheDao.eligibleCount(time.nowMillis() - FRESHNESS_WINDOW_MILLIS) > 0
        return GenreEnrichmentBatch(appIds.size, hasMore, transientFailure)
    }

    /**
     * Seed the cache for a game admitted from presence, whose genres the store already answered for
     * during admission. Writing them here rather than leaving the game to background enrichment
     * means a newly admitted game arrives with its genres already resolved, and costs no extra
     * request — the admission lookup returned them anyway.
     *
     * Admission only admits store type `game` (see SteamStoreAppDataSource), so the seeded row
     * records that type alongside the genres.
     */
    suspend fun storeGenres(
        appId: Long,
        genres: List<GameGenre>,
        categories: List<GameCategory>?,
    ) = write(appId, genres, appType = "game", categories = categories)

    /**
     * A refused category lookup carries no new genre, type, or category facts. Preserve the prior
     * cache in that case and change only the category cooldown marker, while an upgraded row with
     * no marker remains eligible immediately for its first category check.
     */
    private suspend fun write(
        appId: Long,
        genres: List<GameGenre>,
        appType: String?,
        categories: List<GameCategory>?,
    ) {
        val now = time.nowMillis()
        val previous = if (categories == null) cacheDao.findByAppId(appId) else null
        val categoryPayload = categories?.let(GameCategoryCodec::encode) ?: previous?.categoriesJson
        cacheDao.upsert(
            GameGenreCache(
                appId = appId,
                genresJson = previous?.genresJson ?: GameGenreCodec.encode(genres),
                checkedAt = previous?.checkedAt ?: now,
                appType = previous?.appType ?: appType,
                categoriesJson = categoryPayload,
                categoriesDeclinedAt = now.takeIf { categories == null },
            ),
        )
    }

    companion object {
        const val FRESHNESS_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MAX_APPS_PER_BATCH = 25
        const val MIN_REQUEST_SPACING_MILLIS = 500L
    }
}
