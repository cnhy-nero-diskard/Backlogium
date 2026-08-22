package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
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
     */
    data class Details(val genres: List<GameGenre>, val appType: String?) : StoreGenreResult

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
            val envelope = response.body()?.get(appId.toString())
                ?: return StoreGenreResult.Details(emptyList(), appType = null)
            if (!envelope.success) return StoreGenreResult.Details(emptyList(), appType = null)

            val genres = envelope.data?.genres.orEmpty().mapNotNull { dto ->
                val id = dto.id?.trim().orEmpty()
                val label = dto.description?.trim().orEmpty()
                if (id.isEmpty() || label.isEmpty()) null else GameGenre(id, label)
            }
            StoreGenreResult.Details(
                genres = genres,
                appType = envelope.data?.type?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            )
        } catch (error: IOException) {
            StoreGenreResult.TransientFailure(error)
        } catch (error: HttpException) {
            StoreGenreResult.TransientFailure(error)
        }
    }
}
