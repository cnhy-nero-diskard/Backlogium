package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.DeadlineArchiveDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DeadlineArchiveRow
import com.example.backlogium.data.local.entity.DeadlineRevision
import com.example.backlogium.data.local.entity.RecoverySnapshotClassifications
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.DeadlineArchive
import com.example.backlogium.domain.DeadlineArchivedMember
import com.example.backlogium.domain.DeadlineCollectionIdentity
import com.example.backlogium.domain.DeadlinePlayEvidence
import com.example.backlogium.domain.DeadlineSessionPlayEvidence
import com.example.backlogium.domain.GameSource
import com.example.backlogium.domain.PlaytimeReadProjector
import com.example.backlogium.domain.SessionCredit
import com.example.backlogium.domain.TimedShareCredit
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.deadlineCutoff
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DeadlineArchivePreview internal constructor(
    val result: DeadlineArchive,
    internal val active: Collection,
    internal val members: List<CollectionMember>,
    internal val revision: Long,
)

class DeadlinePreviewExpired : IllegalStateException("The collection changed. Review its archive preview again.")

@Singleton
class DeadlineArchiveRepository @Inject constructor(
    private val dao: DeadlineArchiveDao,
    private val collections: CollectionDao,
    private val sessions: SessionDao,
    private val transaction: DatabaseTransactionScope,
    private val time: TimeProvider,
) {
    val archives = dao.observeArchives().map { rows ->
        rows.map(DeadlineArchiveRow::toDomain).sortedByDescending { it.finalizedAt }
    }

    fun observeCutoff(id: Long) = collections.observeById(id).map { it?.cutoff() }

    suspend fun preview(id: Long): DeadlineArchivePreview? = transaction.run {
        currentPreview(id, time.nowMillis())
    }

    suspend fun confirm(preview: DeadlineArchivePreview): DeadlineArchive = transaction.run {
        val key = preview.result.identity
        dao.archive(key.originId, key.createdAt)?.let { return@run it.toDomain() }
        val current = currentPreview(preview.active.id, time.nowMillis()) ?: throw DeadlinePreviewExpired()
        if (current.copy(result = current.result.copy(finalizedAt = preview.result.finalizedAt)) != preview) {
            throw DeadlinePreviewExpired()
        }
        dao.insert(current.result.toRow())
        collections.delete(current.active.id)
        current.result
    }

    private suspend fun currentPreview(id: Long, now: Long): DeadlineArchivePreview? {
        val active = collections.getById(id)?.takeIf { it.mode == CollectionMode.DEADLINE_GOAL } ?: return null
        val date = active.targetDate ?: return null
        val zone = active.deadlineZone ?: return null
        val cutoff = active.cutoff() ?: return null
        if (now < cutoff) return null

        val revisions = dao.revisions(id)
        val beforeCutoff = revisions.filter { it.effectiveAt < cutoff }
        val goal = beforeCutoff.lastOrNull { it.appId == null }
        val applicableGoal = goal?.takeIf { it.targetDate == date && it.zone == zone }
        val basis = applicableGoal?.basis?.let(CollectionTimeBasis::valueOf) ?: active.timeBasis
        val cutoffAmbiguous = dao.hasAmbiguousBatch(id, cutoff)
        val members = beforeCutoff.filter { it.appId != null }
            .associateBy { it.appId }
            .values.filter { it.present == true }
            .sortedBy { it.appId }
            .map { memberEvidence(it, basis, cutoff, now) }
        val result = DeadlineArchive(
            identity = active.deadlineIdentity(),
            name = applicableGoal?.name ?: active.name,
            targetDate = date,
            zone = zone,
            timeBasis = basis,
            cutoff = cutoff,
            finalizedAt = now,
            goalObservedAt = applicableGoal?.effectiveAt,
            membershipKnown = goal != null,
            members = members,
            cutoffAmbiguous = cutoffAmbiguous,
        )
        return DeadlineArchivePreview(result, active, collections.getMembers(id), revisions.maxOfOrNull { it.id } ?: 0L)
    }

    private suspend fun memberEvidence(
        revision: DeadlineRevision,
        basis: CollectionTimeBasis,
        cutoff: Long,
        now: Long,
    ): DeadlineArchivedMember {
        val appId = requireNotNull(revision.appId)
        val source = revision.gameSource?.let { runCatching { GameSource.valueOf(it) }.getOrNull() }
        val play = if (source == GameSource.STEAM_OWNED) steamPlayEvidence(appId, cutoff, now) else null
        val sessionPlay = if (source == GameSource.FAMILY_SHARED) sessionPlayEvidence(appId, cutoff) else null
        return DeadlineArchivedMember(
            appId = appId,
            name = revision.name,
            hiddenAtCutoff = revision.hidden,
            membershipObservedAt = revision.effectiveAt,
            estimateMinutes = when (basis) {
                CollectionTimeBasis.MAIN_STORY -> revision.mainStory
                CollectionTimeBasis.MAIN_EXTRA -> revision.mainExtra
                CollectionTimeBasis.COMPLETIONIST -> revision.completionist
                CollectionTimeBasis.ALL_STYLES -> revision.allStyles
            }?.takeIf { it > 0 },
            play = play,
            recordedSessions = dao.recordedSessions(appId, cutoff).takeIf { it > 0 || play?.creditedMinutes == 0L },
            recordedTrophies = dao.recordedTrophies(appId, cutoff)
                .takeIf { it > 0 || dao.hasDatedTrophyCoverage(appId, cutoff) },
            sessionPlay = sessionPlay,
        )
    }

    private suspend fun steamPlayEvidence(appId: Long, cutoff: Long, now: Long): DeadlinePlayEvidence? {
        val before = dao.before(appId, cutoff)
        val after = dao.after(appId, cutoff, now)
        // An unchanged Steam total on both sides establishes the cumulative value at the boundary.
        // A changed or missing reading leaves the cutoff quantity unresolved.
        return if (before != null && after != null &&
            before.classification == RecoverySnapshotClassifications.TRUSTED &&
            after.classification == RecoverySnapshotClassifications.TRUSTED &&
            before.absoluteMinutes == after.absoluteMinutes
        ) {
            DeadlinePlayEvidence(before.absoluteMinutes, before.observedAt, after.observedAt)
        } else null
    }

    private suspend fun sessionPlayEvidence(appId: Long, cutoff: Long): DeadlineSessionPlayEvidence? {
        val closed = sessions.closedSessionsBefore(appId, cutoff).withoutOverlaps()
        if (closed.isEmpty()) return null
        val sessionIds = closed.mapTo(mutableSetOf(), Session::id)
        val shares = sessions.timedPlacementsForClosedSessionsBefore(appId, cutoff)
            .filter { it.sessionId in sessionIds }
        val canonicalRemainder = PlaytimeReadProjector.creditedMinutesByGame(
            contributions = emptyList(),
            sessions = closed.map(Session::toCredit),
            timedShares = shares.map(TimedContributionPlacement::toCredit),
        )[appId] ?: 0L
        val datedShares = shares.filter { it.dateWhollyBefore(cutoff) }.fold(0L) { total, share ->
            Math.addExact(total, share.minutes)
        }
        val credited = Math.addExact(canonicalRemainder, datedShares)
        if (credited <= 0L) return null
        return DeadlineSessionPlayEvidence(
            creditedMinutes = credited,
            sessionCount = closed.size,
            firstSessionAt = closed.minOf(Session::startAt),
            lastSessionEndAt = closed.maxOf { requireNotNull(it.endAt) },
        )
    }
}

private fun Session.toCredit() = SessionCredit(id, appId, startAt, minutes, open, endAt)

private fun TimedContributionPlacement.toCredit() = TimedShareCredit(
    placementId = placementId,
    contributionId = contributionId,
    appId = appId,
    sessionId = sessionId,
    minutes = minutes,
    creditedDate = runCatching { LocalDate.parse(creditedDate) }.getOrNull(),
    frozenZone = zone,
    evidenceQuality = evidenceQuality,
    goalAtCredit = goalAtCredit,
)

private fun TimedContributionPlacement.dateWhollyBefore(cutoff: Long): Boolean {
    val date = runCatching { LocalDate.parse(creditedDate) }.getOrNull() ?: return false
    val frozenZone = runCatching { ZoneId.of(this.zone) }.getOrNull() ?: return false
    return date < Instant.ofEpochMilli(cutoff).atZone(frozenZone).toLocalDate()
}

/** Discard every member of an overlapping interval component rather than crediting it twice. */
private fun List<Session>.withoutOverlaps(): List<Session> {
    val safe = mutableListOf<Session>()
    var component = mutableListOf<Session>()
    var componentEnd = Long.MIN_VALUE
    for (session in sortedBy(Session::startAt)) {
        val end = requireNotNull(session.endAt)
        if (component.isEmpty() || session.startAt >= componentEnd) {
            if (component.size == 1) safe += component.single()
            component = mutableListOf(session)
            componentEnd = end
        } else {
            component += session
            componentEnd = maxOf(componentEnd, end)
        }
    }
    if (component.size == 1) safe += component.single()
    return safe
}

internal fun Collection.deadlineIdentity() = DeadlineCollectionIdentity(originId ?: id, createdAt)

internal fun DeadlineArchiveRow.toDomain(): DeadlineArchive = Json.decodeFromString(payload)

internal fun DeadlineArchive.toRow() = DeadlineArchiveRow(
    identity.originId, identity.createdAt, Json.encodeToString(this),
)

private fun Collection.cutoff(): Long? {
    if (mode != CollectionMode.DEADLINE_GOAL) return null
    val date = targetDate ?: return null
    val zone = deadlineZone ?: return null
    return runCatching { deadlineCutoff(date, zone) }.getOrNull()
}
