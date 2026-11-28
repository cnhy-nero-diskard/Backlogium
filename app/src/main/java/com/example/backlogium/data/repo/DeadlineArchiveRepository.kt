package com.example.backlogium.data.repo

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.local.dao.CollectionDao
import com.example.backlogium.data.local.dao.DeadlineArchiveDao
import com.example.backlogium.data.local.entity.Collection
import com.example.backlogium.data.local.entity.CollectionMember
import com.example.backlogium.data.local.entity.DeadlineArchiveRow
import com.example.backlogium.data.local.entity.DeadlineRevision
import com.example.backlogium.data.local.entity.RecoverySnapshotClassifications
import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.CollectionTimeBasis
import com.example.backlogium.domain.DeadlineArchive
import com.example.backlogium.domain.DeadlineArchivedMember
import com.example.backlogium.domain.DeadlineCollectionIdentity
import com.example.backlogium.domain.DeadlinePlayEvidence
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.domain.deadlineCutoff
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

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
        val beforeCutoff = revisions.takeWhile { it.effectiveAt < cutoff }
        val goal = beforeCutoff.lastOrNull { it.appId == null }
        val applicableGoal = goal?.takeIf { it.targetDate == date && it.zone == zone }
        val basis = applicableGoal?.basis?.let(CollectionTimeBasis::valueOf) ?: active.timeBasis
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
        val before = dao.before(appId, cutoff)
        val after = dao.after(appId, cutoff, now)
        // An unchanged Steam total on both sides establishes the cumulative value at the boundary.
        // A changed or missing reading leaves the cutoff quantity unresolved.
        val play = if (before != null && after != null &&
            before.classification == RecoverySnapshotClassifications.TRUSTED &&
            after.classification == RecoverySnapshotClassifications.TRUSTED &&
            before.absoluteMinutes == after.absoluteMinutes
        ) {
            DeadlinePlayEvidence(before.absoluteMinutes, before.observedAt, after.observedAt)
        } else null
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
        )
    }
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
