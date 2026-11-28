package com.example.backlogium.data.backup

import com.example.backlogium.domain.CollectionMode
import com.example.backlogium.domain.DeadlineCollectionIdentity
import com.example.backlogium.domain.assessDeadline
import com.example.backlogium.domain.deadlineCutoff
import java.time.ZoneId

internal class BackupDeadlineConflict(val collectionName: String) :
    IllegalArgumentException("Backup conflicts with archived collection '$collectionName'")

internal fun BackupCollection.deadlineIdentity() = DeadlineCollectionIdentity(originId ?: id, createdAt)

internal fun deadlineArchiveProblems(file: BackupFile): List<BackupValidationProblem> = buildList {
    if ((file.formatVersion >= 4) != (file.deadlineArchives != null)) {
        add(BackupValidationProblem("file", 0, "deadlineArchives must be present in version 4"))
    }

    val activeIdentities = mutableSetOf<DeadlineCollectionIdentity>()
    val activeIds = mutableSetOf<Long>()
    file.collections.forEachIndexed { index, collection ->
        val identity = collection.deadlineIdentity()
        val invalidZone = collection.deadlineZone?.let {
            collection.mode != CollectionMode.DEADLINE_GOAL.name || runCatching { ZoneId.of(it) }.isFailure
        } == true
        when {
            collection.id <= 0 || identity.originId <= 0 || collection.createdAt < 0 ->
                add(BackupValidationProblem("collection", index, "invalid collection identity"))
            !activeIds.add(collection.id) || !activeIdentities.add(identity) ->
                add(BackupValidationProblem("collection", index, "duplicate collection identity"))
            invalidZone || file.formatVersion < 4 && (collection.deadlineZone != null || collection.originId != null) ->
                add(BackupValidationProblem("collection", index, "invalid collection zone or backup version"))
        }
    }

    val seenArchives = mutableSetOf<DeadlineCollectionIdentity>()
    file.deadlineArchives.orEmpty().forEachIndexed { index, archive ->
        fun problem(detail: String) = add(BackupValidationProblem("deadlineArchive", index, detail))
        if (archive.identity.originId <= 0 || archive.identity.createdAt !in 0..archive.finalizedAt ||
            !seenArchives.add(archive.identity) || archive.identity in activeIdentities
        ) problem("invalid, duplicate or active collection identity")
        val cutoff = runCatching { deadlineCutoff(archive.targetDate, archive.zone) }.getOrNull()
        if (cutoff == null || cutoff != archive.cutoff || cutoff <= 0 || cutoff > archive.finalizedAt) {
            problem("invalid goal, cutoff or finalization time")
        }
        if (archive.goalObservedAt?.let { it !in archive.identity.createdAt until archive.cutoff } == true ||
            !archive.membershipKnown && (archive.members.isNotEmpty() || archive.goalObservedAt != null)
        ) problem("invalid cutoff goal or membership provenance")

        val members = mutableSetOf<Long>()
        if (archive.members.any { member ->
                member.appId <= 0 || !members.add(member.appId) ||
                    member.membershipObservedAt !in archive.identity.createdAt until archive.cutoff ||
                    (member.estimateMinutes ?: 1) <= 0 ||
                    (member.recordedSessions ?: 0) < 0 ||
                    (member.recordedTrophies ?: 0) < 0 ||
                    member.play?.let { play ->
                        play.creditedMinutes < 0 || play.observedBefore !in 1 until archive.cutoff ||
                            play.observedAfter !in archive.cutoff..archive.finalizedAt
                    } == true || member.playBounds?.let { bounds ->
                        bounds.lowerBoundMinutes < 0 || bounds.observedBefore !in 1 until archive.cutoff ||
                            (bounds.upperBoundMinutes == null) != (bounds.observedAfter == null) ||
                            bounds.upperBoundMinutes?.let { upper ->
                                upper < bounds.lowerBoundMinutes ||
                                    bounds.observedAfter?.let {
                                        it !in archive.cutoff..archive.finalizedAt
                                    } != false
                            } == true
                    } == true || member.sessionPlay?.let { play ->
                        play.creditedMinutes <= 0 || play.sessionCount <= 0 || play.firstSessionAt < 0 ||
                            play.firstSessionAt >= archive.cutoff || play.lastSessionEndAt <= play.firstSessionAt ||
                            play.lastSessionEndAt > archive.cutoff || member.recordedSessions == null ||
                            play.sessionCount > member.recordedSessions
                    } == true ||
                    (member.play != null && (member.playBounds != null || member.sessionPlay != null)) ||
                    (member.playBounds != null && member.sessionPlay != null)
            }
        ) problem("invalid member evidence")
        if (archive.outcome != assessDeadline(
                archive.goalObservedAt, archive.membershipKnown, archive.members, archive.cutoffAmbiguous,
            )
        ) {
            problem("outcome conflicts with the frozen evidence")
        }
    }
}
