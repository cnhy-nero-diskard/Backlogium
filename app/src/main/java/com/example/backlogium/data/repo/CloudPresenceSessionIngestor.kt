package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.CloudPresenceSessionIngest
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.PresenceSessionDeriver
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.SessionDiffer
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** The result of consuming one cloud snapshot. */
data class CloudPresenceIngestResult(
    val processed: Boolean,
    val wrote: Boolean,
    val actionCount: Int,
    val creditedMinutes: Int,
)

/**
 * Feeds acquired cloud intervals into the existing presence session mechanism.
 *
 * This class owns the decision to ingest, but not a second session ledger: actions still pass
 * through [SessionActionWriter], and the watermark advances only after the derived write and its
 * silent recompute complete. The process-local mutex also makes two UI reads share one fold.
 */
@Singleton
class CloudPresenceSessionIngestor @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val sessionActionWriter: SessionActionWriter,
    private val gamificationUpdater: GamificationUpdater,
    private val settings: SettingsRepository,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val time: TimeProvider,
) {
    private val mutex = Mutex()

    /** Consume a snapshot once, retaining the prior cursor when any write fails. */
    suspend fun ingest(snapshot: CloudPresenceSnapshot): CloudPresenceIngestResult = mutex.withLock {
        val position = snapshot.ingestPosition()
        val storedAt = settings.cloudIngestPosition.first()?.let(::positionAt)
        if (storedAt != null && position.at <= storedAt) {
            return@withLock CloudPresenceIngestResult(
                processed = false,
                wrote = false,
                actionCount = 0,
                creditedMinutes = 0,
            )
        }

        val games = gameDao.getAll()
        val sources = games.associate { it.appId to it.source }
        val sharedIds = games.asSequence()
            .filter { it.source == GameSource.FAMILY_SHARED }
            .map { it.appId }
            .toSet()
        val sessions = sessionDao.getAll()
        val storedSessions = sessions.asSequence()
            .filter { it.appId in sharedIds }
            .groupBy { it.appId }
            .mapValues { (_, rows) ->
                rows.map { row ->
                    CloudPresenceSessionIngest.StoredSessionSpan(
                        startAt = row.startAt,
                        endAt = row.endAt ?: row.startAt,
                    )
                }
            }
        val storedOpen = sessions.firstOrNull { it.open && it.appId in sharedIds }
            ?.toOpenSession()
        val observations = CloudPresenceSessionIngest.observations(
            intervals = snapshot.intervals,
            gameSources = sources,
            storedSessions = storedSessions,
            seededAppId = storedOpen?.appId,
        )
        // An older cloud-only gap must not fold through a newer live open: deriving it from that
        // open would out-of-order close the live session and merge the gap into it. Seed from the
        // open only when the first cloud observation is not older than what it already observed.
        val firstGameAt = observations.firstOrNull { it.appId != null }?.at
        val openSession = if (storedOpen != null && firstGameAt != null &&
            firstGameAt < storedOpen.lastObservedAt
        ) {
            null
        } else {
            storedOpen ?: closedOverlapSeed(observations, sessions, sharedIds)
        }
        // A fully stored historical interval emits only a synthetic null boundary, so a snapshot
        // with no new game observation still seeds the newer live open. Folding that earlier
        // boundary from the open out-of-order closes the live session it was meant to leave
        // alone. Drop observations the seed already observed; newer boundaries that prove a
        // switch are kept.
        val foldObservations = if (openSession != null) {
            observations.filter { it.at >= openSession.lastObservedAt }
        } else {
            observations
        }
        val actions = fold(foldObservations, openSession)
        val goalIds = games.asSequence()
            .filter { it.isGoal }
            .map { it.appId }
            .toSet()
        val allActions = actions + reconcileCurrentState(
            snapshot = snapshot,
            sources = sources,
            sessions = sessions,
            sharedIds = sharedIds,
            actions = actions,
        )
        val effective = sessionActionWriter.apply(allActions, goalIds)
        val wrote = effective.isNotEmpty()
        if (wrote) recompute()
        settings.setCloudIngestPosition(position.raw)
        CloudPresenceIngestResult(
            processed = true,
            wrote = wrote,
            actionCount = allActions.size,
            creditedMinutes = effective.sumOf { it.addedMinutes },
        )
    }

    private suspend fun recompute() {
        derivedStateWrites.withLock {
            val rules = settings.ruleConfigWithVersion.first()
            gamificationUpdater.recompute(
                today = time.today(),
                source = RecomputeSource.RETROACTIVE_PLAY,
                config = rules.config,
                configVersion = rules.version,
            )
        }
    }

    private fun fold(
        observations: List<PresenceSessionDeriver.Observation>,
        initialOpenSession: PresenceSessionDeriver.OpenSession?,
    ): List<SessionDiffer.SessionAction> {
        var openSession = initialOpenSession
        val actions = mutableListOf<SessionDiffer.SessionAction>()
        observations.forEach { observation ->
            val result = PresenceSessionDeriver().derive(observation, openSession)
            actions += result.actions
            openSession = result.openSession
        }
        return actions
    }

    /**
     * Reconcile live state around a fully stored ongoing interval.
     *
     * A fully covered ongoing app emits no game observation — there is no new play — but while
     * it names a different game than a stored open it still proves that open stale, and while
     * the cloud reports it running the stored row for it must itself read open. The fold tracks
     * only the single seeded open, so without this a seeded A->B switch closes A and leaves B
     * closed with no live session, and when stale A and current B are both already open the
     * outcome depends on which single open row happened to seed the fold. Closing every other
     * open proven older than the cloud evidence and reopening the stored current row through
     * the normal writer path keeps both halves independent of that pick, with no extra minutes
     * and no duplicate row: the reopen is an Extend of the existing row, which the writer trims
     * to zero added minutes.
     */
    private fun reconcileCurrentState(
        snapshot: CloudPresenceSnapshot,
        sources: Map<Long, GameSource>,
        sessions: List<Session>,
        sharedIds: Set<Long>,
        actions: List<SessionDiffer.SessionAction>,
    ): List<SessionDiffer.SessionAction> {
        val ongoing = snapshot.intervals.asSequence()
            .filter { it.ongoing }
            .maxByOrNull { it.startAt }
            ?.takeIf { sources[it.appId] == GameSource.FAMILY_SHARED }
            ?: return emptyList()
        val confirmedEnd = CloudPresenceSessionIngest.confirmedEndAt(ongoing)
            ?: return emptyList()
        val openRows = sessions.filter { it.open && it.appId in sharedIds }
        val maxOpenObserved = openRows.maxOfOrNull { it.endAt ?: it.startAt }
        val extra = mutableListOf<SessionDiffer.SessionAction>()
        for (row in openRows) {
            if (row.appId == ongoing.appId) continue
            val lastObserved = row.endAt ?: row.startAt
            // Older cloud evidence must not out-of-order close a newer live session.
            if (lastObserved > confirmedEnd) continue
            if (actions.none {
                    it is SessionDiffer.SessionAction.Close &&
                        it.appId == row.appId && it.startAt == row.startAt
                }
            ) {
                extra += SessionDiffer.SessionAction.Close(
                    appId = row.appId,
                    startAt = row.startAt,
                    endAt = lastObserved,
                )
            }
        }
        val hasOngoingWrite = actions.any {
            it.appId == ongoing.appId &&
                (it is SessionDiffer.SessionAction.Open || it is SessionDiffer.SessionAction.Extend)
        }
        if (!hasOngoingWrite && openRows.none { it.appId == ongoing.appId }) {
            val candidate = sessions.asSequence()
                .filter { it.appId == ongoing.appId }
                .maxByOrNull { it.endAt ?: it.startAt }
            if (candidate != null) {
                val candidateEnd = candidate.endAt ?: candidate.startAt
                if (confirmedEnd >= candidateEnd &&
                    (maxOpenObserved == null || confirmedEnd >= maxOpenObserved)
                ) {
                    extra += SessionDiffer.SessionAction.Extend(
                        appId = candidate.appId,
                        startAt = candidate.startAt,
                        minutes = candidate.minutes,
                        endAt = maxOf(candidateEnd, confirmedEnd),
                        addedMinutes = 0,
                    )
                }
            }
        }
        return extra
    }

    private fun closedOverlapSeed(
        observations: List<PresenceSessionDeriver.Observation>,
        sessions: List<Session>,
        sharedIds: Set<Long>,
    ): PresenceSessionDeriver.OpenSession? {
        val firstGame = observations.firstOrNull { it.appId != null } ?: return null
        val appId = firstGame.appId ?: return null
        if (appId !in sharedIds) return null
        val existing = sessions.asSequence()
            .filter { it.appId == appId }
            .maxByOrNull { it.endAt ?: it.startAt }
            ?: return null
        val lastObservedAt = existing.endAt ?: existing.startAt
        if (firstGame.at < lastObservedAt) return null
        if (firstGame.at - lastObservedAt > CloudPresenceSessionIngest.DEFAULT_GAP_TOLERANCE_MILLIS) {
            return null
        }
        return existing.toOpenSession(lastObservedAt)
    }

    private fun Session.toOpenSession(
        lastObservedAt: Long = endAt ?: startAt,
    ) = PresenceSessionDeriver.OpenSession(
        appId = appId,
        startAt = startAt,
        minutes = minutes,
        lastObservedAt = lastObservedAt,
    )

    private fun CloudPresenceSnapshot.ingestPosition(): IngestPosition {
        val fallback = maxOf(
        windowStart,
        windowEnd,
        intervals.maxOfOrNull { interval -> interval.endAt ?: interval.startAt } ?: windowStart,
        )
        val raw = nextPosition?.trim()?.takeIf { it.isNotBlank() } ?: fallback.toString()
        return IngestPosition(raw = raw, at = positionAt(raw) ?: fallback)
    }

    private fun positionAt(raw: String): Long? = raw.toLongOrNull()
        ?: runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()

    private data class IngestPosition(val raw: String, val at: Long)
}
