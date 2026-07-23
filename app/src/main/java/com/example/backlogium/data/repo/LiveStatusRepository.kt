package com.example.backlogium.data.repo

import com.example.backlogium.BuildConfig
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.remote.SteamApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The player's current in-game state — a transient live signal (never persisted). Either the
 * player is [InGame] (with whatever identity Steam exposes) or [NotPlaying].
 */
sealed interface NowPlaying {
    data object NotPlaying : NowPlaying

    /**
     * @param gameId Steam app id of the running game, when parseable.
     * @param name display name — Steam's `gameextrainfo` when present, else a best-effort label.
     * @param iconUrl resolved icon URL, or null when the game isn't in the owned set (name-only).
     */
    data class InGame(
        val gameId: Long?,
        val name: String,
        val iconUrl: String?,
    ) : NowPlaying
}

/**
 * Exposes the player's current in-game state as a cold [Flow] that polls Steam's
 * `GetPlayerSummaries` roughly every 30 seconds. The flow is foreground-scoped by
 * construction: it only ticks while something collects it (the Home screen via
 * `stateIn(WhileSubscribed)`), and stops shortly after collection stops — no Service, no
 * manual lifecycle wiring, no leak. Nothing here touches Room persistence or the periodic
 * background sync.
 */
@Singleton
class LiveStatusRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val gameDao: GameDao,
    private val settings: SettingsDataStore,
) {
    /**
     * Emits an immediate [NowPlaying.NotPlaying] so consumers never block on the first
     * network round-trip, then polls: fetch → emit → wait. A failed fetch retains the last
     * emitted value rather than throwing out of the flow, so a transient error doesn't clear
     * the banner abruptly. Each fetch is awaited before the next delay, so slow requests
     * can't stack.
     */
    val nowPlaying: Flow<NowPlaying> = flow {
        var last: NowPlaying = NowPlaying.NotPlaying
        emit(last)
        while (true) {
            last = runCatching { fetch() }.getOrDefault(last)
            emit(last)
            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun fetch(): NowPlaying {
        val apiKey = BuildConfig.STEAM_API_KEY
        val steamId = settings.steamIdFlow.first()
        // Unconfigured (or private) profiles simply report not-in-game — no error surfaced.
        if (apiKey.isBlank() || steamId.isBlank()) return NowPlaying.NotPlaying

        val player = steamApi.getPlayerSummaries(apiKey, steamId)
            .response.players.firstOrNull()
            ?: return NowPlaying.NotPlaying

        // No gameid → not in a game (or profile too private to expose it).
        if (player.gameId.isNullOrBlank()) return NowPlaying.NotPlaying

        val gameId = player.gameId.toLongOrNull()
        val name = player.gameExtraInfo?.takeIf { it.isNotBlank() }
            ?: gameId?.let { "App $it" }
            ?: "In game"
        // Reuse the already-synced owned-games icon; name-only fallback when absent.
        val iconUrl = gameId
            ?.let { gameDao.getById(it)?.iconUrl }
            ?.takeIf { it.isNotBlank() }

        return NowPlaying.InGame(gameId = gameId, name = name, iconUrl = iconUrl)
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L
    }
}
