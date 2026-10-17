package com.example.backlogium.data.repo

import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.isConfirmedRecentEnvelope
import com.example.backlogium.data.remote.dto.validatedPlaytime2Weeks
import com.example.backlogium.data.remote.dto.validatedPlaytimeForever
import com.example.backlogium.data.remote.dto.validatedRecentGames
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One game's playtime as Steam currently reports it, as consumers above `data/` see it — the
 * observation a poll commits, with no storage type in sight.
 *
 * [name] can be blank: this fetch answers a playtime question, and the commit path keeps the
 * stored name when an observation does not carry one.
 *
 * [playtimeForever] is always a usable (present, nonnegative) cumulative reading: the repository
 * drops rows Steam omitted or malformed before this point. [playtime2Weeks] is recent-window
 * metadata; null denotes a malformed negative value and must preserve the stored last-good value.
 */
data class PlaytimeObservation(
    val appId: Long,
    val name: String,
    val playtimeForever: Int,
    val playtime2Weeks: Int?,
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
     * @return bounded recent observations, or an empty list when Steam is not configured, the
     *   envelope is unconfirmed (a private profile, a malformed/absent `total_count`, or a
     *   positive total with no games returned), or no game reported a usable cumulative reading.
     *
     * An unconfirmed envelope must never author an observation: the targeted fetch would otherwise
     * compare an invented value against its baseline, and the caller (PostPlaySyncWorker) keeps
     * last-good data and lets the schedule continue.
     */
    suspend fun recentlyPlayed(scope: SyncRunRecorder.RunScope? = null): List<PlaytimeObservation> {
        val creds = credentials.currentCredentials() ?: return emptyList()
        val result = steamApi
            .getRecentlyPlayedGames(
                creds.apiKey,
                creds.steamId,
                count = RECENT_GAME_COUNT,
                scope = scope,
            )
            .response
        if (!isConfirmedRecentEnvelope(result.totalCount, result.games.isEmpty())) return emptyList()
        return result.games
            .validatedRecentGames()
            .mapNotNull { game ->
                val playtimeForever = game.validatedPlaytimeForever ?: return@mapNotNull null
                PlaytimeObservation(
                    appId = game.appid,
                    name = game.name,
                    playtimeForever = playtimeForever,
                    playtime2Weeks = game.validatedPlaytime2Weeks,
                )
            }
    }
}
