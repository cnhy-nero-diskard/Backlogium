package com.example.backlogium.data.repo

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.remote.SteamApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One game's playtime as Steam currently reports it, as consumers above `data/` see it — the
 * observation a poll commits, with no storage type in sight.
 *
 * [name] can be blank: this fetch answers a playtime question, and the commit path keeps the
 * stored name when an observation does not carry one.
 */
data class PlaytimeObservation(
    val appId: Long,
    val name: String,
    val playtimeForever: Int,
    val playtime2Weeks: Int,
)

/**
 * The bounded recent-playtime read used by the post-play fetch. Steam does not reliably put the
 * game whose session just ended first, so the repository returns the small recent window and lets
 * the worker select the requested app before it reaches the commit path.
 */
@Singleton
class RecentPlaytimeRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val credentials: CredentialsProvider,
) {
    companion object {
        /** Keeps the lookup bounded without assuming the first recent game is the stopped game. */
        const val RECENT_GAME_COUNT = 20
    }

    /**
     * @return bounded recent observations, or an empty list when Steam is not configured or the
     *   response carries no games (a private profile, or an account that has played nothing).
     */
    suspend fun recentlyPlayed(scope: SyncRunRecorder.RunScope? = null): List<PlaytimeObservation> {
        val creds = credentials.currentCredentials() ?: return emptyList()
        return steamApi
            .getRecentlyPlayedGames(
                creds.apiKey,
                creds.steamId,
                count = RECENT_GAME_COUNT,
                scope = scope,
            )
            .response.games
            .map { game ->
                PlaytimeObservation(
                    appId = game.appid,
                    name = game.name,
                    playtimeForever = game.playtimeForever,
                    playtime2Weeks = game.playtime2Weeks,
                )
            }
    }
}
