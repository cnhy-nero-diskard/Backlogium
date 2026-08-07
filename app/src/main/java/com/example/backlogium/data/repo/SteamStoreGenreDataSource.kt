package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** The Store result boundary: only [Genres] and [Empty] may be committed to the local cache. */
sealed interface StoreGenreResult {
    data class Genres(val values: List<GameGenre>) : StoreGenreResult
    data object Empty : StoreGenreResult
    data class TransientFailure(val cause: Throwable) : StoreGenreResult
}

@Singleton
class SteamStoreGenreDataSource @Inject constructor(
    private val api: SteamStoreApi,
) {
    suspend fun genresFor(appId: Long): StoreGenreResult {
        return try {
            val response = api.appDetails(appId)
            if (!response.isSuccessful) {
                return StoreGenreResult.TransientFailure(HttpException(response))
            }
            val envelope = response.body()?.get(appId.toString())
                ?: return StoreGenreResult.Empty
            if (!envelope.success) return StoreGenreResult.Empty

            val genres = envelope.data?.genres.orEmpty().mapNotNull { dto ->
                val id = dto.id?.trim().orEmpty()
                val label = dto.description?.trim().orEmpty()
                if (id.isEmpty() || label.isEmpty()) null else GameGenre(id, label)
            }
            if (genres.isEmpty()) StoreGenreResult.Empty else StoreGenreResult.Genres(genres)
        } catch (error: IOException) {
            StoreGenreResult.TransientFailure(error)
        } catch (error: HttpException) {
            StoreGenreResult.TransientFailure(error)
        }
    }
}
