package com.example.backlogium.domain

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.CloudPresenceRefilingBackup
import com.example.backlogium.data.repo.SettingsRepository
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.flow.first

enum class CloudPresenceRefilingOperation {
    APPLIED,
    REVERSED,
    NO_OP,
}

data class CloudPresenceRefilingResult(
    val operation: CloudPresenceRefilingOperation,
    val sessionsRefiled: Int = 0,
    val datesAffected: Set<String> = emptySet(),
)

internal data class CloudPresenceSessionRefile(
    val original: Session,
    val replacement: List<Session>,
)

/** Pure session selection/replacement rule used by both the one-time action and its tests. */
internal fun cloudPresenceSessionRefiles(
    sessions: List<Session>,
    ownedAppIds: Set<Long>,
    intervals: List<CloudPresenceInterval>,
): List<CloudPresenceSessionRefile> = sessions
    .asSequence()
    .filter { it.appId in ownedAppIds && !it.open && it.endAt != null && it.minutes > 0 }
    .mapNotNull { original ->
        val replacement = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = original.appId,
                diffedMinutes = original.minutes,
                periodStartAt = original.startAt,
                periodEndAt = original.endAt ?: return@mapNotNull null,
                intervals = intervals,
            ),
        )
            ?.filterIsInstance<SessionDiffer.SessionAction.Open>()
            ?.map { action ->
                Session(
                    appId = action.appId,
                    startAt = action.startAt,
                    endAt = action.endAt,
                    minutes = action.minutes,
                    open = false,
                )
            }
            ?.takeIf { it.isNotEmpty() && it.sumOf(Session::minutes) == original.minutes }
            ?: return@mapNotNull null
        if (replacement.isSameLedgerRowAs(original)) {
            null
        } else {
            CloudPresenceSessionRefile(original, replacement)
        }
    }
    .toList()

/**
 * Re-files already-recorded owned-game sessions against a complete cloud-presence snapshot.
 *
 * The session ledger remains the source of quantity: cloud intervals can change only a session's
 * boundaries and therefore its calendar attribution. Original rows are retained in DataStore and
 * newly-created split rows are recorded by id, making the operation exactly reversible without a
 * Room migration.
 */
class CloudPresencePlaytimeRefilingUseCase @Inject constructor(
    private val gameDao: GameDao,
    private val sessionDao: SessionDao,
    private val dailyProgressDao: DailyProgressDao,
    private val settings: SettingsRepository,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val syncCoordinator: com.example.backlogium.work.SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
) {

    suspend fun apply(intervals: List<CloudPresenceInterval>): CloudPresenceRefilingResult =
        syncCoordinator.withLock {
            if (settings.cloudPresenceRefilingApplied.first()) {
                return@withLock CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
            }

            derivedStateWrites.withLock {
                val changes = findChanges(intervals)
                val originals = changes.map { it.original }
                settings.setCloudPresenceRefilingBackup(
                    CloudPresenceRefilingBackup(sessions = originals),
                )

                if (changes.isEmpty()) {
                    settings.setCloudPresenceRefilingApplied(true)
                    return@withLock CloudPresenceRefilingResult(
                        operation = CloudPresenceRefilingOperation.APPLIED,
                    )
                }

                val createdIds = transaction.run {
                    changes.flatMap { change ->
                        sessionDao.update(change.replacement.first().copy(id = change.original.id))
                        change.replacement.drop(1).map { replacement ->
                            sessionDao.insert(replacement.copy(id = 0L))
                        }
                    }.toSet()
                }
                settings.setCloudPresenceRefilingBackup(
                    CloudPresenceRefilingBackup(
                        sessions = originals,
                        createdSessionIds = createdIds,
                    ),
                )
                recompute()
                settings.setCloudPresenceRefilingApplied(true)
                CloudPresenceRefilingResult(
                    operation = CloudPresenceRefilingOperation.APPLIED,
                    sessionsRefiled = changes.size,
                    datesAffected = changes.flatMap { change ->
                        (listOf(change.original) + change.replacement).map(::dateOf)
                    }.toSet(),
                )
            }
        }

    suspend fun reverse(): CloudPresenceRefilingResult = syncCoordinator.withLock {
        if (!settings.cloudPresenceRefilingApplied.first()) {
            return@withLock CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
        }

        derivedStateWrites.withLock {
            val backup = settings.cloudPresenceRefilingBackup() ?: CloudPresenceRefilingBackup(emptyList())
            val current = sessionDao.getAll()
            val affectedDates = (backup.sessions + current.filter { it.id in backup.createdSessionIds })
                .map(::dateOf)
                .toSet()
            transaction.run {
                backup.createdSessionIds.forEach { id -> sessionDao.deleteById(id) }
                backup.sessions.forEach { session -> sessionDao.update(session) }
            }
            if (backup.sessions.isNotEmpty() || backup.createdSessionIds.isNotEmpty()) {
                recompute()
            }
            settings.clearCloudPresenceRefiling()
            CloudPresenceRefilingResult(
                operation = CloudPresenceRefilingOperation.REVERSED,
                sessionsRefiled = backup.sessions.size,
                datesAffected = affectedDates,
            )
        }
    }

    private suspend fun findChanges(intervals: List<CloudPresenceInterval>): List<Change> {
        val ownedIds = gameDao.getAll()
            .asSequence()
            .filter { it.source == GameSource.STEAM_OWNED }
            .map { it.appId }
            .toSet()
        return cloudPresenceSessionRefiles(sessionDao.getAll(), ownedIds, intervals)
            .map { change -> Change(change.original, change.replacement) }
    }

    private suspend fun recompute() {
        val corrections = dailyProgressCorrections(
            sessions = sessionDao.getAll(),
            goalAppIds = gameDao.getAll().asSequence()
                .filter { it.isGoal }
                .map { it.appId }
                .toSet(),
            stored = dailyProgressDao.getAllOrdered(),
            zone = time.zone(),
        )
        corrections.forEach { correction ->
            dailyProgressDao.ensureDate(correction.date)
            dailyProgressDao.setMinutes(
                date = correction.date,
                minutesPlayed = correction.correctedMinutes,
                goalMinutesPlayed = correction.correctedGoalMinutes,
            )
        }
        val rules = settings.ruleConfigWithVersion.first()
        gamificationUpdater.recompute(
            today = time.today(),
            source = RecomputeSource.RETROACTIVE_PLAY,
            config = rules.config,
            configVersion = rules.version,
        )
    }

    private fun dateOf(session: Session): String =
        Instant.ofEpochMilli(session.startAt).atZone(time.zone()).toLocalDate().toString()

    private data class Change(
        val original: Session,
        val replacement: List<Session>,
    )
}

private fun List<Session>.isSameLedgerRowAs(original: Session): Boolean =
    size == 1 && first().let { replacement ->
        replacement.appId == original.appId &&
            replacement.startAt == original.startAt &&
            replacement.endAt == original.endAt &&
            replacement.minutes == original.minutes &&
            !replacement.open
    }
