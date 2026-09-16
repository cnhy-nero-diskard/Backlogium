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
        val alreadyObservedThrough = sessions.asSequence()
            .filter { it.appId in sharedIds }
            .groupBy { it.appId }
            .mapValues { (_, rows) ->
                rows.maxOf { it.endAt ?: it.startAt }
            }
        val observations = CloudPresenceSessionIngest.observations(
            intervals = snapshot.intervals,
            gameSources = sources,
            alreadyObservedThrough = alreadyObservedThrough,
        )
        val openSession = sessions.firstOrNull { it.open && it.appId in sharedIds }
            ?.toOpenSession()
            ?: closedOverlapSeed(observations, sessions, sharedIds)
        val actions = fold(observations, openSession)
        val goalIds = games.asSequence()
            .filter { it.isGoal }
            .map { it.appId }
            .toSet()
        val wrote = sessionActionWriter.apply(actions, goalIds)
        if (wrote) recompute()
        settings.setCloudIngestPosition(position.raw)
        CloudPresenceIngestResult(
            processed = true,
            wrote = wrote,
            actionCount = actions.size,
            creditedMinutes = if (wrote) actions.sumOf { it.addedMinutes } else 0,
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
