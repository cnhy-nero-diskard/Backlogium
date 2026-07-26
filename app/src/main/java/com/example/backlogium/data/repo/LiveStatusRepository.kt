package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.domain.PlayerIdentity
import com.example.backlogium.domain.mergePlayerIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
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
 * The player's Steam presence, derived from `personastate` plus the running game. Live only —
 * never persisted, because stale "Online" text after two days offline would be a lie.
 */
enum class LivePresence {
    /** No poll has returned yet, or the profile is too private to say. Render no label. */
    UNKNOWN,
    OFFLINE,
    ONLINE,
    IN_GAME,
}

/** One poll's worth of live signal: what's running, and how the player reads as present. */
data class LiveStatus(
    val nowPlaying: NowPlaying = NowPlaying.NotPlaying,
    val presence: LivePresence = LivePresence.UNKNOWN,
)

/**
 * Exposes the player's live Steam status as a [Flow] that polls `GetPlayerSummaries` roughly
 * every 30 seconds. The flow is foreground-scoped by construction: it only ticks while something
 * collects it (the Home screen and the shell's profile header, via `stateIn`/`WhileSubscribed`),
 * and stops shortly after collection stops — no Service, no manual lifecycle wiring, no leak.
 * `shareIn` means several observers still cost exactly one poll.
 *
 * Presence and [NowPlaying] are never persisted. The one thing this repository does write is the
 * player's identity, opportunistically, when a poll observes a newer persona name or avatar than
 * the sync last stored.
 */
@Singleton
class LiveStatusRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val gameDao: GameDao,
    private val profileDao: PlayerProfileDao,
    private val credentials: CredentialsRepository,
    @ApplicationScope scope: CoroutineScope,
) {
    /**
     * Emits an immediate [LiveStatus] default so consumers never block on the first network
     * round-trip, then polls: fetch → emit → wait. A failed fetch retains the last emitted value
     * rather than throwing out of the flow, so a transient error doesn't clear the banner
     * abruptly. Each fetch is awaited before the next delay, so slow requests can't stack.
     */
    val liveStatus: Flow<LiveStatus> = flow {
        var last = LiveStatus()
        emit(last)
        while (true) {
            last = runCatching { fetch() }.getOrDefault(last)
            emit(last)
            delay(POLL_INTERVAL_MS)
        }
    }.shareIn(scope, SharingStarted.WhileSubscribed(SHARE_TIMEOUT_MS), replay = 1)

    val nowPlaying: Flow<NowPlaying> = liveStatus.map { it.nowPlaying }

    private suspend fun fetch(): LiveStatus {
        // Unconfigured (or private) profiles simply report not-in-game — no error surfaced.
        val creds = credentials.currentCredentials() ?: return LiveStatus()
        val apiKey = creds.apiKey
        val steamId = creds.steamId

        val player = steamApi.getPlayerSummaries(apiKey, steamId)
            .response.players.firstOrNull()
            ?: return LiveStatus()

        refreshStoredIdentity(player)

        // No gameid → not in a game (or profile too private to expose it).
        if (player.gameId.isNullOrBlank()) {
            val presence = if (player.personaState == PERSONA_STATE_OFFLINE) {
                LivePresence.OFFLINE
            } else {
                LivePresence.ONLINE
            }
            return LiveStatus(NowPlaying.NotPlaying, presence)
        }

        val gameId = player.gameId.toLongOrNull()
        val name = player.gameExtraInfo?.takeIf { it.isNotBlank() }
            ?: gameId?.let { "App $it" }
            ?: "In game"
        // Reuse the already-synced owned-games icon; name-only fallback when absent.
        val iconUrl = gameId
            ?.let { gameDao.getById(it)?.iconUrl }
            ?.takeIf { it.isNotBlank() }

        return LiveStatus(
            nowPlaying = NowPlaying.InGame(gameId = gameId, name = name, iconUrl = iconUrl),
            presence = LivePresence.IN_GAME,
        )
    }

    /**
     * Keep the persisted header identity current within a session. The periodic sync owns the
     * initial write; this only closes the gap when a name or avatar changes while the app is
     * open. A no-op when nothing changed, so the write stays idempotent and cheap. Never fails
     * the poll — no profile row yet means the next sync will create one.
     */
    private suspend fun refreshStoredIdentity(
        player: com.example.backlogium.data.remote.dto.PlayerSummaryDto,
    ) {
        val profile = profileDao.get() ?: return
        val stored = PlayerIdentity(profile.personaName, profile.avatarUrl)
        val merged = mergePlayerIdentity(player, stored)
        if (merged == stored) return
        profileDao.upsert(
            profile.copy(personaName = merged.personaName, avatarUrl = merged.avatarUrl),
        )
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L

        /** Keep polling briefly across a recomposition/navigation gap, matching `stateIn` above. */
        private const val SHARE_TIMEOUT_MS = 5_000L

        /** Steam's `personastate` value for an offline player; every other value is "around". */
        private const val PERSONA_STATE_OFFLINE = 0
    }
}
