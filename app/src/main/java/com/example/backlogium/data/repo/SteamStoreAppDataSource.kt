package com.example.backlogium.data.repo

import com.example.backlogium.data.remote.SteamStoreApi
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What Steam's store says about an app id, for family-shared admission.
 *
 * [NotAGame] and [Unavailable] are deliberately distinct. "The store answered, and this is a
 * tool" is a permanent answer that should stop the app reconsidering the id; "the store could not
 * be reached" says nothing at all, and the id must be reconsidered on a later observation. Folding
 * the two together would either admit applications on a network blip or permanently refuse a real
 * game because the store was down once.
 */
sealed interface StoreAppInfo {
    data class Game(val name: String, val genres: List<GameGenre>) : StoreAppInfo

    /** The store answered and this app id is not a game (tool, application, video, demo, DLC). */
    data object NotAGame : StoreAppInfo

    /** No usable answer: network failure, HTTP error, or an unsuccessful envelope. */
    data class Unavailable(val cause: Throwable? = null) : StoreAppInfo
}

@Singleton
class SteamStoreAppDataSource @Inject constructor(
    private val api: SteamStoreApi,
) {
    suspend fun appInfoFor(appId: Long): StoreAppInfo {
        return try {
            val response = api.appDetails(appId)
            if (!response.isSuccessful) {
                return StoreAppInfo.Unavailable(HttpException(response))
            }
            val envelope = response.body()?.get(appId.toString())
                // A missing entry for the id we asked about is not an answer about the id.
                ?: return StoreAppInfo.Unavailable()
            // `success = false` is the store's answer for an id it will not describe — delisted,
            // region-locked, or not a store item. Not a game, and not worth re-asking about.
            if (!envelope.success) return StoreAppInfo.NotAGame
            val data = envelope.data ?: return StoreAppInfo.Unavailable()
            if (!data.type.equals(STORE_TYPE_GAME, ignoreCase = true)) return StoreAppInfo.NotAGame

            val name = data.name?.trim().orEmpty()
            if (name.isEmpty()) return StoreAppInfo.Unavailable()
            StoreAppInfo.Game(name = name, genres = data.genres.toGameGenres())
        } catch (error: IOException) {
            StoreAppInfo.Unavailable(error)
        } catch (error: HttpException) {
            StoreAppInfo.Unavailable(error)
        }
    }

    private companion object {
        /** Steam's `type` for a playable game. Everything else is out of scope by design. */
        const val STORE_TYPE_GAME = "game"
    }
}
