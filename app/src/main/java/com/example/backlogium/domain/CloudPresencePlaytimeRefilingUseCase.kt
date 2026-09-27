package com.example.backlogium.domain

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
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
                original.copy(
                    id = 0L,
                    startAt = action.startAt,
                    endAt = action.endAt,
                    minutes = action.minutes,
                    open = false,
                    openAppId = null,
                    timingInformedSteamPlay = TimingInformedSteamPlayState.FULL,
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
                val inProgress = settings.cloudPresenceRefilingBackup()
                if (inProgress == null) {
                    val changes = findChanges(intervals)
                    val originals = changes.map { it.original }
                    val dailyBefore =
                        if (changes.isEmpty()) emptyList() else dailyProgressDao.getAllOrdered()
                    settings.setCloudPresenceRefilingBackup(
                        CloudPresenceRefilingBackup(
                            sessions = originals,
                            dailyProgress = dailyBefore,
                        ),
                    )

                    if (changes.isEmpty()) {
                        settings.setCloudPresenceRefilingApplied(true)
                        return@withLock CloudPresenceRefilingResult(
                            operation = CloudPresenceRefilingOperation.APPLIED,
                        )
                    }

                    val createdIds = commitSessionRefiles(changes)
                    settings.setCloudPresenceRefilingBackup(
                        CloudPresenceRefilingBackup(
                            sessions = originals,
                            createdSessionIds = createdIds,
                            dailyProgress = dailyBefore,
                            createdDailyProgressDates = emptySet(),
                        ),
                    )
                    return@withLock finishApply(originals, createdIds, dailyBefore, changes)
                }

                if (inProgress.sessions.isEmpty()) {
                    // No prior mutation could have happened without originals: re-evaluate fresh
                    // rather than preserving an empty backup that would hide new evidence.
                    val changes = findChanges(intervals)
                    val originals = changes.map { it.original }
                    val dailyBefore =
                        if (changes.isEmpty()) emptyList() else dailyProgressDao.getAllOrdered()
                    settings.setCloudPresenceRefilingBackup(
                        CloudPresenceRefilingBackup(
                            sessions = originals,
                            dailyProgress = dailyBefore,
                        ),
                    )
                    if (changes.isEmpty()) {
                        settings.setCloudPresenceRefilingApplied(true)
                        return@withLock CloudPresenceRefilingResult(
                            operation = CloudPresenceRefilingOperation.APPLIED,
                        )
                    }
                    val createdIds = commitSessionRefiles(changes)
                    settings.setCloudPresenceRefilingBackup(
                        CloudPresenceRefilingBackup(
                            sessions = originals,
                            createdSessionIds = createdIds,
                            dailyProgress = dailyBefore,
                            createdDailyProgressDates = emptySet(),
                        ),
                    )
                    return@withLock finishApply(originals, createdIds, dailyBefore, changes)
                }

                // A previous attempt wrote originals but never marked applied. Never recompute
                // changes from the current ledger here: after the Room commit it already holds
                // the re-filed rows, so a fresh diff finds nothing and would overwrite the
                // useful backup with an empty one, making an exact reversal impossible.
                val currentById = sessionDao.getAll().associateBy { it.id }
                val intact = inProgress.sessions.all { original ->
                    currentById[original.id] == original
                }
                if (intact) {
                    // The Room commit never landed (or rolled back): no mutation to preserve,
                    // so discarding the stale backup for a fresh diff is safe and also picks
                    // up any intervals that arrived between attempts.
                    val changes = findChanges(intervals)
                    val originals = changes.map { it.original }
                    val dailyBefore =
                        if (changes.isEmpty()) emptyList() else dailyProgressDao.getAllOrdered()
                    settings.setCloudPresenceRefilingBackup(
                        CloudPresenceRefilingBackup(
                            sessions = originals,
                            dailyProgress = dailyBefore,
                        ),
                    )
                    if (changes.isEmpty()) {
                        settings.setCloudPresenceRefilingApplied(true)
                        return@withLock CloudPresenceRefilingResult(
                            operation = CloudPresenceRefilingOperation.APPLIED,
                        )
                    }
                    val createdIds = commitSessionRefiles(changes)
                    settings.setCloudPresenceRefilingBackup(
                        CloudPresenceRefilingBackup(
                            sessions = originals,
                            createdSessionIds = createdIds,
                            dailyProgress = dailyBefore,
                            createdDailyProgressDates = emptySet(),
                        ),
                    )
                    return@withLock finishApply(originals, createdIds, dailyBefore, changes)
                }

                // The session commit landed atomically; the crash came after. Recover the
                // created ids from the expected splits rather than trusting a backup that may
                // predate them, then re-run the idempotent recompute to completion.
                // Assumes no other ledger writer interleaved between attempts: retries are
                // user-initiated and prompt, and the locks exclude syncs during each attempt.
                val ownedIds = gameDao.getAll()
                    .asSequence()
                    .filter { it.source == GameSource.STEAM_OWNED }
                    .map { it.appId }
                    .toSet()
                val expectedByOriginal = cloudPresenceSessionRefiles(
                    inProgress.sessions,
                    ownedIds,
                    intervals,
                ).associateBy { it.original.id }
                val originalIds = inProgress.sessions.map { it.id }.toSet()
                val currentSessions = sessionDao.getAll()
                val usedIds = mutableSetOf<Long>()
                val recoveredIds = mutableSetOf<Long>()
                for (original in inProgress.sessions) {
                    val current = currentById[original.id]
                        ?: throw IllegalStateException(
                            "Cloud re-filing resume found no session for backup id ${original.id}",
                        )
                    if (current == original) {
                        throw IllegalStateException(
                            "Cloud re-filing resume found a partially applied ledger",
                        )
                    }
                    val expected = expectedByOriginal[original.id]
                        ?: throw IllegalStateException(
                            "Cloud re-filing resume no longer places backup id ${original.id}",
                        )
                    val first = expected.replacement.first()
                    if (current.appId != first.appId || current.startAt != first.startAt ||
                        current.endAt != first.endAt || current.minutes != first.minutes ||
                        current.open
                    ) {
                        throw IllegalStateException(
                            "Cloud re-filing resume found unexpected content for id ${original.id}",
                        )
                    }
                    for (split in expected.replacement.drop(1)) {
                        val match = currentSessions.firstOrNull { candidate ->
                            candidate.id !in originalIds && candidate.id !in usedIds &&
                                candidate.appId == split.appId &&
                                candidate.startAt == split.startAt &&
                                candidate.endAt == split.endAt &&
                                candidate.minutes == split.minutes && !candidate.open
                        } ?: throw IllegalStateException(
                            "Cloud re-filing resume found no split for backup id ${original.id}",
                        )
                        recoveredIds += match.id
                        usedIds += match.id
                    }
                }
                if (inProgress.createdSessionIds.isNotEmpty() &&
                    recoveredIds != inProgress.createdSessionIds
                ) {
                    throw IllegalStateException(
                        "Cloud re-filing resume disagrees with the stored created ids",
                    )
                }
                val originals = inProgress.sessions
                val dailyBefore = inProgress.dailyProgress
                settings.setCloudPresenceRefilingBackup(
                    CloudPresenceRefilingBackup(
                        sessions = originals,
                        createdSessionIds = recoveredIds,
                        dailyProgress = dailyBefore,
                        createdDailyProgressDates = inProgress.createdDailyProgressDates,
                    ),
                )
                val changes = expectedByOriginal.values.map { refile ->
                    Change(refile.original, refile.replacement)
                }
                return@withLock finishApply(originals, recoveredIds, dailyBefore, changes)
            }
        }

    suspend fun reverse(): CloudPresenceRefilingResult = syncCoordinator.withLock {
        if (!settings.cloudPresenceRefilingApplied.first()) {
            return@withLock CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
        }

        derivedStateWrites.withLock {
            val backup = settings.cloudPresenceRefilingBackup() ?: CloudPresenceRefilingBackup(emptyList())
            val current = sessionDao.getAll()
            val affectedDates = backup.sessions.map(::dateOf).toMutableSet().apply {
                addAll(current.filter { it.id in backup.createdSessionIds }.map(::dateOf))
                addAll(backup.dailyProgress.map { it.date })
                addAll(backup.createdDailyProgressDates)
            }
            transaction.run {
                backup.createdSessionIds.forEach { id -> sessionDao.deleteById(id) }
                backup.sessions.forEach { session -> sessionDao.update(session) }
            }
            // Daily progress is rebuilt from the restored ledger rather than from the
            // pre-apply snapshot: restoring whole rows verbatim would erase play recorded
            // after the apply, and deleting created dates outright would do the same for
            // a date the re-file created that has since gained its own sessions. The
            // snapshot in the backup is intentionally not read here.
            val hasWork = backup.sessions.isNotEmpty() || backup.createdSessionIds.isNotEmpty() ||
                backup.dailyProgress.isNotEmpty() || backup.createdDailyProgressDates.isNotEmpty()
            val recomputedDates = if (hasWork) recompute() else emptySet()
            // Drop only the rows the apply introduced that the restored ledger no longer
            // supports. A created date that has gained sessions since the apply keeps its
            // row, with the recompute above having corrected it to the ledger total.
            if (hasWork && backup.createdDailyProgressDates.isNotEmpty()) {
                val datesWithSessions = sessionDao.getAll().map(::dateOf).toSet()
                backup.createdDailyProgressDates
                    .filter { it !in datesWithSessions }
                    .forEach { date -> dailyProgressDao.deleteByDate(date) }
            }
            settings.clearCloudPresenceRefiling()
            CloudPresenceRefilingResult(
                operation = CloudPresenceRefilingOperation.REVERSED,
                sessionsRefiled = backup.sessions.size,
                datesAffected = affectedDates + recomputedDates,
            )
        }
    }

    private suspend fun commitSessionRefiles(changes: List<Change>): Set<Long> =
        transaction.run {
            changes.flatMap { change ->
                sessionDao.update(change.replacement.first().copy(id = change.original.id))
                change.replacement.drop(1).map { replacement ->
                    sessionDao.insert(replacement.copy(id = 0L))
                }
            }.toSet()
        }

    private suspend fun finishApply(
        originals: List<Session>,
        createdIds: Set<Long>,
        dailyBefore: List<DailyProgress>,
        changes: List<Change>,
    ): CloudPresenceRefilingResult {
        val recomputedDates = recompute()
        val createdDailyDates = dailyProgressDao.getAllOrdered()
            .map { it.date }
            .toSet() - dailyBefore.map { it.date }.toSet()
        settings.setCloudPresenceRefilingBackup(
            CloudPresenceRefilingBackup(
                sessions = originals,
                createdSessionIds = createdIds,
                dailyProgress = dailyBefore,
                createdDailyProgressDates = createdDailyDates,
            ),
        )
        settings.setCloudPresenceRefilingApplied(true)
        return CloudPresenceRefilingResult(
            operation = CloudPresenceRefilingOperation.APPLIED,
            sessionsRefiled = changes.size,
            datesAffected = (changes.flatMap { change ->
                (listOf(change.original) + change.replacement).map(::dateOf)
            } + recomputedDates).toSet(),
        )
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

    private suspend fun recompute(): Set<String> {
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
        recomputeGamification()
        return corrections.map { it.date }.toSet()
    }

    private suspend fun recomputeGamification() {
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
