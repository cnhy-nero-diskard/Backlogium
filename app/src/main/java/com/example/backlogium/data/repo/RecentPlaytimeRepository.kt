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
 * The targeted playtime read used by the post-play fetch: `GetRecentlyPlayedGames` with a count of
 * one, which is the single most recently played game — the one whose session just ended.
 *
 * Deliberately does not verify that the returned game is the one the caller expected. The caller
 * knows which app id it asked about; discarding a mismatch is its decision to record, not a detail
 * to hide in here (see add-post-play-sync's design, "the response is verified against the expected
 * app id rather than trusted").
 */
@Singleton
class RecentPlaytimeRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val credentials: CredentialsProvider,
) {
    /**
     * @return the single most recent observation, or null when Steam is not configured or the
     *   response carries no games (a private profile, or an account that has played nothing).
     */
    suspend fun mostRecentlyPlayed(scope: SyncRunRecorder.RunScope? = null): PlaytimeObservation? {
        val creds = credentials.currentCredentials() ?: return null
        val game = steamApi
            .getRecentlyPlayedGames(creds.apiKey, creds.steamId, count = 1, scope = scope)
            .response.games.firstOrNull()
            ?: return null
        return PlaytimeObservation(
            appId = game.appid,
            name = game.name,
            playtimeForever = game.playtimeForever,
            playtime2Weeks = game.playtime2Weeks,
        )
    }
}
