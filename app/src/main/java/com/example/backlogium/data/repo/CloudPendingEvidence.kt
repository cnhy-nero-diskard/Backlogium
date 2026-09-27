package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PendingCloudEvidenceDao
import com.example.backlogium.data.local.entity.PendingCloudBoundary
import com.example.backlogium.data.local.entity.PendingCloudInterval
import com.example.backlogium.domain.CloudCoverageState
import com.example.backlogium.domain.CloudPresenceInterval
import com.example.backlogium.domain.CloudPresenceTransition
import com.example.backlogium.domain.GameSource
import javax.inject.Inject

/** The durable acquisition seam shared by every cursor-advancing read. */
interface CloudPendingEvidence {
    suspend fun boundary(account: String, generation: Long): CloudPresenceTransition?
    suspend fun retain(
        account: String,
        generation: Long,
        windowStart: Long,
        intervals: List<CloudPresenceInterval>,
        lastTransition: CloudPresenceTransition?,
    )
    suspend fun intervals(account: String, generation: Long): List<CloudPresenceInterval>
    suspend fun earliestWindowStart(account: String, generation: Long): Long?
    suspend fun clear()
    suspend fun clearExcept(account: String, generation: Long)
}

class RoomCloudPendingEvidence @Inject constructor(
    private val dao: PendingCloudEvidenceDao,
    private val gameDao: GameDao,
    private val transaction: DatabaseTransactionScope,
) : CloudPendingEvidence {
    override suspend fun boundary(account: String, generation: Long): CloudPresenceTransition? =
        dao.boundary(account, generation)?.let {
            CloudPresenceTransition(
                at = it.at, appId = it.appId, gameName = it.gameName,
                personastate = it.personastate,
                previousLastObservedAt = it.previousLastObservedAt,
                previousCoverageLapseFrom = it.previousCoverageLapseFrom,
                previousCoverageLapseRecoveredAt = it.previousCoverageLapseRecoveredAt,
                schemaVersion = it.schemaVersion,
            )
        }

    override suspend fun retain(
        account: String,
        generation: Long,
        windowStart: Long,
        intervals: List<CloudPresenceInterval>,
        lastTransition: CloudPresenceTransition?,
    ) {
        // Steam ownership may not have been fetched yet. Do not discard an unknown app's
        // timing evidence before a later Steam baseline can classify and use it.
        val sharedIds = gameDao.getAll().asSequence()
            .filter { it.source == GameSource.FAMILY_SHARED }.map { it.appId }.toSet()
        transaction.run {
            intervals.filter { it.appId !in sharedIds }.forEach { interval ->
                val previous = dao.intervals(account, generation)
                    .firstOrNull { it.appId == interval.appId && it.startAt == interval.startAt }
                // A replay of an earlier provisional page must not replace a later closed interval.
                if (previous != null && !previous.ongoing && interval.ongoing) return@forEach
                dao.upsert(PendingCloudInterval(
                    account = account, generation = generation, appId = interval.appId,
                    startAt = interval.startAt, endAt = interval.endAt,
                    ongoing = interval.ongoing, coverage = interval.coverage.name,
                    observedUntil = interval.observedUntil,
                    coverageLapseFrom = interval.coverageLapseFrom,
                    coverageLapseRecoveredAt = interval.coverageLapseRecoveredAt,
                    mayHaveStartedBefore = interval.mayHaveStartedBefore,
                    gameName = interval.gameName,
                    windowStart = minOf(windowStart, previous?.windowStart ?: windowStart),
                ))
            }
            if (lastTransition != null) {
                val current = dao.boundary(account, generation)
                if (current == null || lastTransition.at >= current.at) {
                    dao.upsertBoundary(PendingCloudBoundary(
                        account = account, generation = generation, at = lastTransition.at,
                        appId = lastTransition.appId, gameName = lastTransition.gameName,
                        personastate = lastTransition.personastate,
                        previousLastObservedAt = lastTransition.previousLastObservedAt,
                        previousCoverageLapseFrom = lastTransition.previousCoverageLapseFrom,
                        previousCoverageLapseRecoveredAt = lastTransition.previousCoverageLapseRecoveredAt,
                        schemaVersion = lastTransition.schemaVersion,
                    ))
                }
            }
        }
    }

    override suspend fun intervals(account: String, generation: Long): List<CloudPresenceInterval> =
        dao.intervals(account, generation).map {
            CloudPresenceInterval(
                appId = it.appId, gameName = it.gameName, startAt = it.startAt,
                endAt = it.endAt, ongoing = it.ongoing,
                coverage = CloudCoverageState.valueOf(it.coverage),
                observedUntil = it.observedUntil, coverageLapseFrom = it.coverageLapseFrom,
                coverageLapseRecoveredAt = it.coverageLapseRecoveredAt,
                mayHaveStartedBefore = it.mayHaveStartedBefore,
            )
        }

    override suspend fun earliestWindowStart(account: String, generation: Long): Long? =
        dao.intervals(account, generation).minOfOrNull { it.windowStart }

    override suspend fun clear() = transaction.run {
        dao.deleteAllIntervals()
        dao.deleteAllBoundaries()
    }

    override suspend fun clearExcept(account: String, generation: Long) = transaction.run {
        dao.deleteOtherIntervals(account, generation)
        dao.deleteOtherBoundaries(account, generation)
    }
}
