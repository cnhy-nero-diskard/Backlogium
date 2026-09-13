package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import com.example.backlogium.data.remote.dto.StoreCategoryDto
import com.example.backlogium.data.remote.dto.StoreGenreDto
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Store result boundary: only [Details] may be committed to the local cache.
 *
 * [Details] is the store's definitive answer about one app, empty genres and an absent type
 * included: "checked, and it has none" is a fact worth caching, while a transient failure must
 * leave last-known data alone.
 */
sealed interface StoreGenreResult {
    /**
     * @param genres ordered broad genres; empty when the store reports none.
     * @param appType the store's own `type` for this app — `game`, `application`, `tool`, `demo`,
     *   … — normalized to lower case, or null when the response did not carry one. Null means
     *   *unknown*: it is never treated as either a game or a non-game (add-hidden-games).
     * @param categories participation categories, or **null when the store refused to describe
     *   the app at all** — a missing entry or a `success = false` envelope, as a delisted or
     *   region-locked game produces. Null is *unknown*; an empty list is the definitive "this app
     *   advertises none". Collapsing the two would classify every delisted multiplayer game as
     *   single-player on the strength of an answer that was never given
     *   (add-gap-plan-suggestions).
     */
    data class Details(
        val genres: List<GameGenre>,
        val appType: String?,
        val categories: List<GameCategory>? = null,
    ) : StoreGenreResult

    data class TransientFailure(val cause: Throwable) : StoreGenreResult
}

@Singleton
class SteamStoreGenreDataSource @Inject constructor(
    private val api: SteamStoreApi,
) {
    /**
     * One `appdetails` request. The app's `type` is read from the same response that already
     * carries the genres — the app used to discard it — so recording it costs no request
     * (add-hidden-games design decision 7).
     */
    suspend fun genresFor(appId: Long): StoreGenreResult {
        return try {
            val response = api.appDetails(appId)
            if (!response.isSuccessful) {
                return StoreGenreResult.TransientFailure(HttpException(response))
            }
            // Both of the next two returns keep `categories` null. The genres and app type stay
            // as they were — a checked negative, thirty days' worth — but a refused envelope has
            // said nothing about participation, and recording "none" here is exactly the mistake
            // that would mark every delisted multiplayer game single-player.
            val envelope = response.body()?.get(appId.toString())
                ?: return StoreGenreResult.Details(emptyList(), appType = null, categories = null)
            if (!envelope.success) {
                return StoreGenreResult.Details(emptyList(), appType = null, categories = null)
            }

            val genres = envelope.data?.genres.orEmpty().toGameGenres()
            StoreGenreResult.Details(
                genres = genres,
                appType = envelope.data?.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
                // A successful envelope with no categories array *is* an answer: this app
                // advertises none, which is what makes it readable as single-player.
                categories = envelope.data?.categories.orEmpty().toGameCategories(),
            )
        } catch (error: IOException) {
            StoreGenreResult.TransientFailure(error)
        } catch (error: HttpException) {
            StoreGenreResult.TransientFailure(error)
        }
    }
}

/**
 * Store genre DTOs to domain genres, dropping any entry missing an id or a label. Shared with
 * [SteamStoreAppDataSource] so a genre resolved during family-shared admission is exactly the
 * genre enrichment would have resolved later.
 */
internal fun List<StoreGenreDto>.toGameGenres(): List<GameGenre> = mapNotNull { dto ->
    val id = dto.id?.trim().orEmpty()
    val label = dto.description?.trim().orEmpty()
    if (id.isEmpty() || label.isEmpty()) null else GameGenre(id, label)
}

/**
 * Store category DTOs to domain categories, dropping any entry missing an id or a label. An entry
 * dropped here is one Steam sent unusably, not one it declined to send — the list as a whole still
 * counts as an answer.
 */
internal fun List<StoreCategoryDto>.toGameCategories(): List<GameCategory> = mapNotNull { dto ->
    val id = dto.id ?: return@mapNotNull null
    val label = dto.description?.trim().orEmpty()
    if (label.isEmpty()) null else GameCategory(id, label)
}
