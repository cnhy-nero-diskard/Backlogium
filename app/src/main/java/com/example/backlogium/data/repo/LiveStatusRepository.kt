package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.diagnostics.PresenceDecisionRecorder
import com.example.backlogium.data.diagnostics.PresenceOutcome
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

private data class PresenceFetch(
    val status: LiveStatus,
    val outcome: PresenceOutcome,
    val appId: Long? = null,
    val steamId: String? = null,
)

/**
 * Exposes the player's live Steam status as an application-scoped [StateFlow], polled roughly
 * every 30 seconds while [startPolling] is active. Ownership of *when* to poll belongs to
 * [com.example.backlogium.work.PresenceService] (started when a game is detected, stopped when it
 * ends) rather than to whoever happens to be observing — merely collecting [liveStatus] never
 * starts or extends polling, so Home and the Library remain plain, side-effect-free observers.
 *
 * Presence and [NowPlaying] are never persisted. The one exception is the current session's
 * (appId, startedAt) pair (see [LiveSessionTracker]), needed so an elapsed-time display survives an
 * app restart — and from which an in-game state is reconstructed at construction, so a cold start
 * mid-session shows the panel immediately rather than after a network round-trip. This repository
 * also opportunistically writes the player's identity when a poll observes a newer persona name or
 * avatar than the last sync stored.
 */
@Singleton
class LiveStatusRepository @Inject constructor(
    private val steamApi: SteamApi,
    private val gameDao: GameDao,
    private val profileDao: PlayerProfileDao,
    private val credentials: CredentialsProvider,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
    private val sessionEnds: PlaySessionEndPublisher,
    private val presenceObserver: PresenceObserver,
    private val diagnostics: PresenceDecisionRecorder? = null,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _liveStatus = MutableStateFlow(LiveStatus())
    val liveStatus: StateFlow<LiveStatus> = _liveStatus.asStateFlow()

    val nowPlaying: Flow<NowPlaying> = liveStatus.map { it.nowPlaying }

    private var pollingJob: Job? = null

    /** True while the recurring 30s poll loop is running. */
    val isPolling: Boolean get() = pollingJob?.isActive == true

    init {
        rehydrateRecordedSession()
    }

    /**
     * Cold start with a session already recorded: present it immediately instead of leaving the
     * panel blank until the first network round-trip lands. The recorded start timestamp already
     * survives process death (that is what it is for) — this is what makes it *visible* again.
     *
     * Only seeds when the recorded `appId` parsed: an unresolved id can't be told apart from a
     * different unresolved game, so rehydrating one would be a guess. Name and icon come from the
     * owned-games table, the same source [fetch] uses, so this adds no data dependency.
     *
     * The first [checkNow] then reconciles — [LiveSessionTracker] already handles same-game
     * (keep the start), different-game (restart), and not-playing (clear).
     */
    private fun rehydrateRecordedSession() {
        scope.launch {
            val session = settings.liveSession.first()
            val appId = session.appId ?: return@launch
            val startedAt = session.startedAt ?: return@launch
            val game = gameDao.getById(appId)
            val seeded = LiveStatus(
                nowPlaying = NowPlaying.InGame(
                    gameId = appId,
                    name = game?.name?.takeIf { it.isNotBlank() } ?: "App $appId",
                    iconUrl = game?.iconUrl?.takeIf { it.isNotBlank() },
                ),
                presence = LivePresence.IN_GAME,
                sessionStartedAt = startedAt,
            )
            // Only if nothing real has landed yet: a checkNow() triggered at startup can easily
            // win this race, and an observation always outranks a recollection.
            _liveStatus.compareAndSet(LiveStatus(), seeded)
        }
    }

    /** Start the poll loop. Idempotent: a call while already polling is a no-op. */
    fun startPolling() {
        if (isPolling) return
        pollingJob = scope.launch {
            while (isActive) {
                checkNow("poll")
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop the poll loop, and *only* that. Safe to call when already stopped.
     *
     * Deliberately leaves both the in-memory and the persisted session alone: the callers are
     * [com.example.backlogium.work.PresenceService.onDestroy], which fires on process death, a
     * low-memory kill, and Android 15's `onTimeout` just as much as on the game ending. Clearing
     * here would erase a two-hour session's start time on a lifecycle event that says nothing about
     * whether the player is still playing. Only [checkNow] can legitimately know a game ended, and
     * it already clears the session on that path.
     *
     * The trade: a killed observer leaves a stale in-game state visible until the next trigger.
     * A re-check is guaranteed on the next app foreground, and showing a session that ended a
     * minute ago beats resetting a live timer to zero.
     */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * One fetch-and-emit cycle, callable both from the poll loop and as a one-off check (the app
     * on foreground, [com.example.backlogium.work.SteamSyncWorker] before its own poll) — those
     * callers use the result to decide whether to start the service, without owning a recurring
     * loop themselves. A failed fetch retains the last emitted presence rather than throwing, so a
     * transient error doesn't clear the Home card or Library dot abruptly.
     */
    suspend fun checkNow(trigger: String = "foreground"): LiveStatus {
        // A failed fetch is not an observation. It says nothing about whether the game ended, so it
        // neither overwrites the last emitted state nor touches the persisted session — treating it
        // as "not playing" would clear a live session on any transient network blip, and on a cold
        // start (where there is no last emitted state to fall back on) it would do exactly that.
        val fetched = runCatching { fetch() }.getOrNull()
        if (fetched == null) {
            diagnostics?.record(trigger, PresenceOutcome.FAILED, retainedPriorState = true)
            return _liveStatus.value
        }

        val now = time.nowMillis()
        val previousSession = settings.liveSession.first()
        val nextSession = LiveSessionTracker.next(previousSession, fetched.status.nowPlaying, now)
        if (nextSession != previousSession) {
            if (nextSession.startedAt == null) {
                settings.clearLiveSession()
            } else {
                settings.setLiveSession(nextSession.appId, nextSession.startedAt)
            }
        }

        val next = fetched.status.copy(sessionStartedAt = nextSession.startedAt)
        _liveStatus.value = next
        publishSessionEndIfAny(previousSession, fetched.status.nowPlaying, now, fetched.steamId)
        diagnostics?.record(trigger, fetched.outcome, fetched.appId)

        // A successful fetch is an observation, and an observation is the only session input a
        // family-shared game has. Deliberately after the emission above and wrapped: the live card
        // is the caller's reason for being here, and it must not be held up — or lost — by a Room
        // write or an admission lookup. A game with no derivable session makes this a no-op.
        runCatching { presenceObserver.onObservation(fetched.appId, time.nowMillis()) }
        return next
    }

    /**
     * Publish the end of a recorded session, for work that acts on it (the post-play playtime
     * fetch). Deliberately driven by the *recorded* session rather than by the last emitted
     * status: only [checkNow] can know a game ended, and only the recorded state names which game
     * it was — by the time the transition is observed, [NowPlaying] no longer does.
     *
     * Three cases must not publish, and none of them reach here as a stopped id:
     * - a presence change (online/away/snooze/offline) while the same game still runs, since the
     *   recorded app id still matches the running one — Steam cycles idle accounts through those
     *   states on its own, and the cloud poller's history shows that churn fragmenting sessions;
     * - a failed observation, which says nothing about whether the game ended and returns before
     *   this is called at all;
     * - [stopPolling] for lifecycle reasons, which retains the recorded session and never observes.
     *
     * A game swapped directly for another (A -> B with no not-playing poll between) *is* an end
     * for A, and is published: A's minutes are as real as if a not-playing poll had landed first.
     */
    private fun publishSessionEndIfAny(
        previousSession: com.example.backlogium.data.local.LiveSessionState,
        observed: NowPlaying,
        now: Long,
        steamId: String?,
    ) {
        val stoppedAppId = previousSession.appId ?: return
        if (previousSession.startedAt == null) return
        val runningAppId = (observed as? NowPlaying.InGame)?.gameId
        if (runningAppId == stoppedAppId) return
        sessionEnds.publish(
            PlaySessionEnd(
                appId = stoppedAppId,
                endedAt = now,
                steamId = steamId.orEmpty(),
            ),
        )
    }

    private suspend fun fetch(): PresenceFetch {
        // Unconfigured (or private) profiles simply report not-in-game — no error surfaced.
        val creds = credentials.currentCredentials()
            ?: return PresenceFetch(LiveStatus(), PresenceOutcome.NO_CREDENTIALS)
        val apiKey = creds.apiKey
        val steamId = creds.steamId

        val player = steamApi.getPlayerSummaries(apiKey, steamId)
            .response.players.firstOrNull()
            ?: return PresenceFetch(
                LiveStatus(),
                PresenceOutcome.NO_PLAYER,
                steamId = steamId,
            )

        refreshStoredIdentity(player)

        // No gameid → not in a game (or profile too private to expose it).
        if (player.gameId.isNullOrBlank()) {
            val presence = if (player.personaState == PERSONA_STATE_OFFLINE) {
                LivePresence.OFFLINE
            } else {
                LivePresence.ONLINE
            }
            return PresenceFetch(
                LiveStatus(NowPlaying.NotPlaying, presence),
                PresenceOutcome.NOT_PLAYING,
                steamId = steamId,
            )
        }

        val gameId = player.gameId.toLongOrNull()
        val name = player.gameExtraInfo?.takeIf { it.isNotBlank() }
            ?: gameId?.let { "App $it" }
            ?: "In game"
        // Reuse the already-synced owned-games icon; name-only fallback when absent.
        val iconUrl = gameId
            ?.let { gameDao.getById(it)?.iconUrl }
            ?.takeIf { it.isNotBlank() }

        return PresenceFetch(
            LiveStatus(
                nowPlaying = NowPlaying.InGame(gameId = gameId, name = name, iconUrl = iconUrl),
                presence = LivePresence.IN_GAME,
            ),
            PresenceOutcome.IN_GAME,
            appId = gameId,
            steamId = steamId,
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
        profileDao.updateHeaderIdentity(merged.personaName, merged.avatarUrl)
    }

    companion object {
        const val POLL_INTERVAL_MS = 30_000L

        /** Steam's `personastate` value for an offline player; every other value is "around". */
        private const val PERSONA_STATE_OFFLINE = 0
    }
}
