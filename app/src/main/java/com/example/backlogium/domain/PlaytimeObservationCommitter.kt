package com.example.backlogium.domain

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.HiddenGameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.repo.CloudPendingEvidencePruner
import com.example.backlogium.data.repo.SessionActionWriter
import com.example.backlogium.domain.CloudPresencePlaytimePlacement.Input
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one path from an observed playtime reading to stored sessions, playtime baselines, and daily
 * progress — whatever observed it.
 *
 * Both the periodic library poll and the play-triggered targeted fetch commit through here, which
 * is what makes exactly-once crediting structural rather than careful: every baseline this reads is
 * read inside the caller's transaction, so whichever observer commits second sees an already
 * advanced baseline, computes no delta, and records no session and no minutes. Neither observer
 * decides anything; the transaction does.
 *
 * It derives nothing beyond sessions from playtime — XP, levels, and streaks stay with the single
 * on-device author the caller invokes after the commit.
 */
@Singleton
class PlaytimeObservationCommitter @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val profileDao: PlayerProfileDao,
    private val hiddenGameDao: HiddenGameDao,
    private val differ: SessionDiffer,
    private val time: TimeProvider,
    private val sessionActionWriter: SessionActionWriter,
    private val pendingEvidencePruner: CloudPendingEvidencePruner? = null,
) {
    /**
     * One game's playtime as an observer saw it.
     *
     * [name] and [iconUrl] may be blank, and a blank one leaves the stored value alone: the
     * targeted fetch asks an endpoint that answers a playtime question and carries no icon, and
     * even the library poll occasionally returns an empty name. Blanking a stored name or icon
     * because *this* observation did not carry one would be a loss, not an update.
     */
    data class ObservedGame(
        val appId: Long,
        val name: String,
        val iconUrl: String,
        val playtimeForever: Int,
        val playtime2Weeks: Int,
    )

    /** What the commit did, for the caller's diagnostics and for tests. */
    data class Commit(
        val actions: List<SessionDiffer.SessionAction>,
        val playedDeltaByAppId: Map<Long, Int>,
        /** Boundaries clamped this commit for a backward clock movement (#115) — the caller's to record. */
        val clockRollbacks: List<SessionDiffer.ClockRollback> = emptyList(),
    ) {
        val recordedPlay: Boolean get() = playedDeltaByAppId.values.any { it > 0 }
    }

    /**
     * Validate placement evidence and hold the reader-promotion fence across the caller's Room
     * transaction. The transaction must start inside this block so the lock order stays reader
     * state -> Room, matching cloud ingestion and promotion.
     */
    suspend fun <T> withValidatedPlacement(
        placement: Input?,
        block: suspend (validatedPlacement: Input?, pruningIdentity: CloudReaderIdentity?) -> T,
    ): T {
        val pruner = pendingEvidencePruner
            ?: return block(placement, placement?.readerIdentity)
        return pruner.withValidatedEvidence(
            evidenceIdentity = placement?.readerIdentity,
            evidenceSupplied = placement != null,
        ) { validatedIdentity, pruningIdentity ->
            val validatedPlacement = placement?.takeIf {
                validatedIdentity != null && it.readerIdentity == validatedIdentity
            }
            block(validatedPlacement, pruningIdentity)
        }
    }

    /**
     * Must be called inside the caller's database transaction — the freshness of the baselines read
     * here is the whole double-count story, and it only holds if the read and the write share one
     * transaction.
     *
     * @param observedPlayAt when the play happened, which the caller supplies rather than this path
     *   reading a clock: the targeted fetch knows the session end it was triggered by, and every
     *   attempt of its schedule must report that same instant no matter which attempt observed the
     *   increase. It becomes each session's end.
     * @param syncedAt when the observation was made — the stored `lastSyncedAt`, and a genuine
     *   clock reading rather than an event time.
     */
    suspend fun commit(
        observed: List<ObservedGame>,
        observedPlayAt: Long,
        syncedAt: Long,
        placement: CloudPresencePlaytimePlacement.Input? = null,
        pruningIdentity: CloudReaderIdentity? = null,
    ): Commit {
        val lastSyncAt = profileDao.get()?.lastSyncAt ?: 0L
        val polls = observed.map { SessionDiffer.PollGame(it.appId, it.playtimeForever) }
        val existingGames = gameDao.ownedGamesForDiffing().associateBy { it.appId }
        val openSessionsByAppId = sessionDao.getAllOpenSessions().associateBy { it.appId }

        val priorOpenSessions = openSessionsByAppId.mapValues { (_, session) ->
            SessionDiffer.OpenSession(
                startAt = session.startAt,
                minutes = session.minutes,
                lastIncreaseAt = session.endAt ?: session.startAt,
            )
        }

        val diff = if (lastSyncAt == 0L) {
            // Nothing has ever been polled: record totals, synthesize no sessions. Only deltas
            // observed after the baseline become play.
            differ.baseline(polls)
        } else {
            differ.diff(
                polls = polls,
                priorStates = existingGames.mapValues { (appId, game) ->
                    val open = openSessionsByAppId[appId]
                    SessionDiffer.GameDiffState(
                        lastPlaytime = game.lastPlaytime,
                        openSession = open?.let {
                            SessionDiffer.OpenSession(
                                startAt = it.startAt,
                                minutes = it.minutes,
                                lastIncreaseAt = it.endAt ?: it.startAt,
                            )
                        },
                    )
                },
                now = observedPlayAt,
                // A session cannot start after it ended. The two agree by construction for the
                // periodic poll, whose observation time is its own clock; a targeted fetch reports
                // an earlier session end, and this keeps a stored session's bounds sane if a poll
                // has landed in between.
                previousPollAt = lastSyncAt.coerceAtMost(observedPlayAt),
            )
        }

        // In production the worker supplies placement only through withValidatedPlacement,
        // which holds the reader-promotion fence until this transaction completes. Keep the
        // identity check here too, so evidence cannot be paired with a different prune target.
        val acceptedPlacement = if (pendingEvidencePruner == null) {
            placement
        } else {
            placement?.takeIf {
                it.readerIdentity != null && it.readerIdentity == pruningIdentity
            }
        }
        val actions = if (acceptedPlacement == null) {
            diff.actions
        } else {
            CloudPresencePlaytimePlacement.replacePositiveActions(
                actions = diff.actions,
                input = acceptedPlacement,
                periodStartAt = lastSyncAt.coerceAtMost(observedPlayAt),
                periodEndAt = observedPlayAt,
                priorOpenSessionsByAppId = priorOpenSessions,
            )
        }

        // Mark only rows whose persisted actions actually differ from Steam's unaided estimate.
        // Merely consulting a reader (or accepting an identical suggestion) is not attribution.
        val timingInformedKeys = if (acceptedPlacement == null || actions == diff.actions) emptySet() else {
            (actions - diff.actions.toSet()).mapTo(mutableSetOf()) { it.appId to it.startAt }
        }

        // Routed through the shared writer, not a local copy, so the single-open-session guard
        // (auditfix-session-ledger-integrity, #116) holds here exactly as it does for the
        // presence path — "regardless of which caller reaches it."
        sessionActionWriter.applySessionActions(actions, timingInformedSessionKeys = timingInformedKeys)

        observed.forEach { game ->
            val existing = existingGames[game.appId]
            val name = game.name.ifBlank { existing?.name.orEmpty() }
            val iconUrl = game.iconUrl.ifBlank { existing?.iconUrl.orEmpty() }
            val lastPlaytime = diff.newLastPlaytime[game.appId] ?: game.playtimeForever
            gameDao.insertSteamGameIfMissing(
                appId = game.appId,
                name = name,
                iconUrl = iconUrl,
                playtimeForever = game.playtimeForever,
                playtime2Weeks = game.playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = syncedAt,
                firstSeenAt = null,
                lastPlayedAt = null,
            )
            gameDao.updateSteamFields(
                appId = game.appId,
                name = name,
                iconUrl = iconUrl,
                playtimeForever = game.playtimeForever,
                playtime2Weeks = game.playtime2Weeks,
                lastPlaytime = lastPlaytime,
                lastSyncedAt = syncedAt,
                // Targeted playtime has no Steam-owned last-played value; preserve the stored
                // recency facts until the periodic owned-games poll supplies one.
                lastPlayedAt = existing?.lastPlayedAt,
                returnedToPlayAt = existing?.returnedToPlayAt,
            )
            // Even a zero-delta successful baseline makes closed evidence ending by this
            // observation ineligible for future windows. The caller's Room transaction ties
            // retirement to the session and Steam baseline commit (including rollback).
            pendingEvidencePruner?.afterBaseline(pruningIdentity, game.appId, syncedAt)
        }

        val goalIds = existingGames.values.filter { it.isGoal }.mapTo(mutableSetOf()) { it.appId }
        val hiddenIds = hiddenGameDao.hiddenAppIds().toSet()
        attributeDailyProgress(actions, goalIds, time.zone(), hiddenIds).forEach { (date, credit) ->
            dailyProgressDao.ensureDate(date)
            dailyProgressDao.addMinutes(date, credit.minutesPlayed, credit.goalMinutesPlayed)
        }

        return Commit(
            actions = actions,
            playedDeltaByAppId = diff.playedDeltaByAppId,
            clockRollbacks = diff.clockRollbacks,
        )
    }
}
