package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PresenceSessionDeriver
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam [LiveStatusRepository] hands each successful presence observation to.
 *
 * An interface so the live-status path stays testable without Room, and so the observation contract
 * — a *successful fetch only*, with the app id Steam reported — is stated in one place rather than
 * implied by a concrete class's constructor.
 */
fun interface PresenceObserver {
    suspend fun onObservation(appId: Long?, observedAt: Long)
}

/**
 * Turns each presence observation into sessions for family-shared games, and admits an
 * unrecognised game the first time the admission rule allows it (add-family-shared-games).
 *
 * This is the second half of the wiring that keeps the two session mechanisms partitioned. The
 * deriver is only ever handed an app id whose stored source is [GameSource.FAMILY_SHARED]; an owned
 * game reaches it as `null`, exactly as "not in a game" does, because an owned game's sessions come
 * from playtime diffing and a second detector on it would produce overlapping records with
 * boundaries no deduplication could reconcile.
 *
 * Called from [LiveStatusRepository] on every *successful* presence fetch. A failed fetch is not an
 * observation and must never reach here: it says nothing about whether play stopped, and treating it
 * as "not playing" would close a live session on any transient network blip.
 */
@Singleton
class PresenceSessionRecorder @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val deriver: PresenceSessionDeriver,
    private val sessionActionWriter: SessionActionWriter,
    private val sharedGames: FamilySharedGameRepository,
    private val gamificationUpdater: GamificationUpdater,
    private val settings: SettingsDataStore,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val time: TimeProvider,
) : PresenceObserver {

    /**
     * @param appId the app id Steam reports the player inside, or null when not in a game (or when
     *   Steam's running-game id did not parse — an unidentifiable game cannot be tracked).
     */
    override suspend fun onObservation(appId: Long?, observedAt: Long) {
        val trackedSharedAppId = resolveSharedGame(appId, observedAt)

        val sharedAppIds = gameDao.sharedGames().mapTo(mutableSetOf()) { it.appId }
        // The common case, for every library with no shared games: nothing to derive and nothing
        // that could be open, so this stops before reading the sessions table on every 30s poll.
        if (trackedSharedAppId == null && sharedAppIds.isEmpty()) return
        // Only sessions belonging to shared games are the deriver's to close. An owned game's open
        // session belongs to the differ, and closing it here would end it at a presence timestamp
        // rather than at its last playtime increase.
        val open = sessionDao.getAllOpenSessions()
            .firstOrNull { it.appId in sharedAppIds }
            ?.let {
                PresenceSessionDeriver.OpenSession(
                    appId = it.appId,
                    startAt = it.startAt,
                    minutes = it.minutes,
                    lastObservedAt = it.endAt ?: it.startAt,
                )
            }

        val result = deriver.derive(
            observation = PresenceSessionDeriver.Observation(trackedSharedAppId, observedAt),
            openSession = open,
        )
        if (result.actions.isEmpty()) return

        val goalAppIds = gameDao.getAll().filter { it.isGoal }.mapTo(mutableSetOf()) { it.appId }
        sessionActionWriter.apply(result.actions, goalAppIds)

        if (shouldRecompute(result.actions)) recompute()
    }

    /**
     * Whether these actions are worth a recompute — a library-scale read of every session, game and
     * HLTB row, and the write-ahead protocol around persisting the result.
     *
     * A closed session always earns one: that is the end of a stretch of play, and the point at
     * which the player looks for what it was worth. An open session that gained minutes earns one
     * only on crossing a [RECOMPUTE_EVERY_MINUTES] boundary. Recomputing on every gained minute
     * would run this fifteen times more often than the periodic sync does for owned games
     * (`SteamSyncWorker` is scheduled every 15 minutes), for a total that is still climbing — a real
     * cost on a phone that is at that moment also streaming a game session.
     *
     * An extend that added no whole minute is still *written* — it keeps the session's end current,
     * which is what the gap tolerance is measured from — but it changes nothing derived.
     */
    private fun shouldRecompute(actions: List<SessionDiffer.SessionAction>): Boolean = actions.any {
        when (it) {
            is SessionDiffer.SessionAction.Close -> true
            is SessionDiffer.SessionAction.Open -> false
            is SessionDiffer.SessionAction.Extend ->
                it.addedMinutes > 0 &&
                    it.minutes / RECOMPUTE_EVERY_MINUTES >
                    (it.minutes - it.addedMinutes) / RECOMPUTE_EVERY_MINUTES
        }
    }

    /**
     * The app id to derive sessions for: a tracked family-shared game, or null. Admission is
     * attempted here — and only here — because the first observation of a borrowed game is the only
     * evidence the app ever gets that it exists.
     */
    private suspend fun resolveSharedGame(appId: Long?, observedAt: Long): Long? {
        if (appId == null) return null
        val existing = gameDao.getById(appId)
        if (existing != null) {
            return when (existing.source) {
                GameSource.FAMILY_SHARED -> appId
                GameSource.STEAM_OWNED -> null
            }
        }
        // Best-effort by design: an unreachable store, a not-yet-completed sync, or a removed game
        // all leave the observation unrecorded rather than guessed at, and are reconsidered next
        // time. A failure here must never cost the caller its presence update.
        runCatching { sharedGames.considerAdmission(appId, observedAt) }
        return gameDao.getById(appId)?.takeIf { it.source == GameSource.FAMILY_SHARED }?.appId
    }

    private suspend fun recompute() {
        // Presence-derived play is progress earned through play, so it recomputes under the same
        // source a sync does — that is what lets it raise levels, satisfy quests, and extend
        // streaks with player-facing progress events, rather than silently redefining a baseline.
        derivedStateWrites.withLock {
            val rules = settings.ruleConfigWithVersionFlow.first()
            gamificationUpdater.recompute(
                today = time.today(),
                source = RecomputeSource.SYNC,
                config = rules.config,
                configVersion = rules.version,
            )
        }
    }

    private companion object {
        /**
         * Minutes of continued play between recomputes while a session is still open. Five keeps a
         * live daily-quest reading close enough to be believed without recomputing the library on
         * every poll.
         */
        const val RECOMPUTE_EVERY_MINUTES = 5
    }
}
