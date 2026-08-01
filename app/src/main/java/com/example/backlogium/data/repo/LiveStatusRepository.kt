package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.domain.PlayerIdentity
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.mergePlayerIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

/**
 * One poll's worth of live signal: what's running, how the player reads as present, and — the one
 * piece of this that is persisted — when the current session was first observed.
 */
data class LiveStatus(
    val nowPlaying: NowPlaying = NowPlaying.NotPlaying,
    val presence: LivePresence = LivePresence.UNKNOWN,
    /** When [nowPlaying]'s session began, for the elapsed-time display. Null while not in a game. */
    val sessionStartedAt: Long? = null,
)

/**
 * Exposes the player's live Steam status as an application-scoped [StateFlow], polled roughly
 * every 30 seconds while [startPolling] is active. Ownership of *when* to poll belongs to
 * [com.example.backlogium.work.PresenceService] (started when a game is detected, stopped when it
 * ends) rather than to whoever happens to be observing — merely collecting [liveStatus] never
 * starts or extends polling, so Home and the Library remain plain, side-effect-free observers.
 *
 * Presence and [NowPlaying] are never persisted. The one exception is the current session's start
 * timestamp (see [LiveSessionTracker]), needed so an elapsed-time display survives an app restart.
 * This repository also opportunistically writes the player's identity when a poll observes a
 * newer persona name or avatar than the last sync stored.
 */
@Singleton
class LiveStatusRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val gameDao: GameDao,
    private val profileDao: PlayerProfileDao,
    private val credentials: CredentialsProvider,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _liveStatus = MutableStateFlow(LiveStatus())
    val liveStatus: StateFlow<LiveStatus> = _liveStatus.asStateFlow()

    val nowPlaying: Flow<NowPlaying> = liveStatus.map { it.nowPlaying }

    private var pollingJob: Job? = null

    /** True while the recurring 30s poll loop is running. */
    val isPolling: Boolean get() = pollingJob?.isActive == true

    /** Start the poll loop. Idempotent: a call while already polling is a no-op. */
    fun startPolling() {
        if (isPolling) return
        pollingJob = scope.launch {
            while (isActive) {
                checkNow()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the poll loop and clear both the in-memory and persisted session, so nothing (e.g. the
     * Library's live dot) keeps showing a game as running once presence is no longer observed.
     * Safe to call when already stopped.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        _liveStatus.value = LiveStatus()
        scope.launch { settings.clearLiveSession() }
    }

    /**
     * One fetch-and-emit cycle, callable both from the poll loop and as a one-off check (Home on
     * open, [com.example.backlogium.work.SteamSyncWorker] after its own poll) — those callers use
     * the result to decide whether to start the service, without owning a recurring loop
     * themselves. A failed fetch retains the last emitted presence rather than throwing, so a
     * transient error doesn't clear the Home card or Library dot abruptly.
     */
    suspend fun checkNow(): LiveStatus {
        val last = _liveStatus.value
        val fetched = runCatching { fetch() }
            .getOrDefault(LiveStatus(last.nowPlaying, last.presence))

        val previousSession = settings.liveSession.first()
        val nextSession = LiveSessionTracker.next(previousSession, fetched.nowPlaying, time.nowMillis())
        if (nextSession != previousSession) {
            if (nextSession.startedAt == null) {
                settings.clearLiveSession()
            } else {
                settings.setLiveSession(nextSession.appId, nextSession.startedAt)
            }
        }

        val next = fetched.copy(sessionStartedAt = nextSession.startedAt)
        _liveStatus.value = next
        return next
    }

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

        /** Steam's `personastate` value for an offline player; every other value is "around". */
        private const val PERSONA_STATE_OFFLINE = 0
    }
}
