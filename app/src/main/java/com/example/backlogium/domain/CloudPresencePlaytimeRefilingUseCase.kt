package com.example.backlogium.domain

import com.example.backlogium.data.backup.DatabaseTransactionScope
import com.example.backlogium.data.backup.PassThroughTransactionScope
import com.example.backlogium.data.local.dao.CloudHistoricalDao
import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.CloudHistoricalApplyPayload
import com.example.backlogium.data.local.entity.CloudHistoricalApplyPayloadCodec
import com.example.backlogium.data.local.entity.CloudHistoricalDailyProgressSnapshot
import com.example.backlogium.data.local.entity.CloudHistoricalGameMinutes
import com.example.backlogium.data.local.entity.CloudHistoricalJournal
import com.example.backlogium.data.local.entity.CloudHistoricalOperation
import com.example.backlogium.data.local.entity.CloudHistoricalSessionSnapshot
import com.example.backlogium.data.local.entity.CloudHistoricalStates
import com.example.backlogium.data.local.entity.CloudHistoricalInterval
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.local.entity.toCloudHistoricalSnapshot
import com.example.backlogium.data.local.entity.toSession
import com.example.backlogium.data.repo.CloudPresenceRefilingBackup
import com.example.backlogium.data.repo.CloudPresenceRefilingGameMinutes
import com.example.backlogium.data.repo.CloudPresenceRefilingReceipt
import com.example.backlogium.data.repo.SettingsRepository
import java.time.Instant
import java.time.ZoneId
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

/**
 * Pure session selection/replacement rule used by both the one-time action and its tests.
 *
 * Sessions that already carry an active funded recovery share ([fundedSessionIds]) are never
 * selected: their placement is authoritative and must survive the historical operation intact
 * (recover-missed-playtime, tasks 6.3/6.4). A historical re-file may only move the unlinked legacy
 * remainder of an unfunded session, never a funded share and never a superseded estimate.
 */
internal fun cloudPresenceSessionRefiles(
    sessions: List<Session>,
    ownedAppIds: Set<Long>,
    intervals: List<CloudPresenceInterval>,
    range: CloudPresenceHistoricalSelection? = null,
    fundedSessionIds: Set<Long> = emptySet(),
): List<CloudPresenceSessionRefile> = sessions
    .asSequence()
    .filter {
        it.appId in ownedAppIds && !it.open && it.endAt != null && it.minutes > 0 &&
            it.id !in fundedSessionIds
    }
    .mapNotNull { original ->
        val originalEndAt = original.endAt ?: return@mapNotNull null
        val periodStartAt = maxOf(original.startAt, range?.effectiveStartAt ?: Long.MIN_VALUE)
        val periodEndAt = minOf(originalEndAt, range?.throughAt ?: Long.MAX_VALUE)
        if (periodEndAt <= periodStartAt) return@mapNotNull null
        val replacement = CloudPresencePlaytimePlacement.place(
            CloudPresencePlaytimePlacement.Request(
                appId = original.appId,
                diffedMinutes = original.minutes,
                periodStartAt = periodStartAt,
                periodEndAt = periodEndAt,
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
    private val playerProfileDao: PlayerProfileDao,
    private val cloudHistoricalDao: CloudHistoricalDao,
    private val settings: SettingsRepository,
    /**
     * Recovery contribution graph (recover-missed-playtime). Optional only so pure-JVM tests can
     * construct this use case without Room; when present, historical re-filing never moves a
     * session that carries an active funded share and reversal preserves independent upgrades.
     */
    private val recoveryDao: PlaytimeRecoveryDao? = null,
    private val gamificationUpdater: GamificationUpdater,
    private val time: TimeProvider,
    private val syncCoordinator: com.example.backlogium.work.SteamSyncCoordinator,
    private val derivedStateWrites: DerivedStateWriteCoordinator,
    private val transaction: DatabaseTransactionScope = PassThroughTransactionScope,
) {

    /** Current per-owned-game upper bounds shown before the player confirms a transfer. */
    suspend fun currentImportedBalanceCeiling(): List<CloudPresenceRefilingGameMinutes> {
        if (playerProfileDao.get()?.playtimeBackfilled != true) return emptyList()
        return gameDao.getAll()
            .asSequence()
            .filter { it.source == GameSource.STEAM_OWNED && it.backfillMinutes > 0 }
            .map { CloudPresenceRefilingGameMinutes(it.appId, it.backfillMinutes) }
            .sortedBy { it.appId }
            .toList()
    }

    suspend fun apply(
        intervals: List<CloudPresenceInterval>,
        range: CloudPresenceHistoricalSelection? = null,
    ): CloudPresenceRefilingResult =
        syncCoordinator.withLock {
            if (settings.cloudPresenceRefilingApplied.first()) {
                return@withLock CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
            }

            derivedStateWrites.withLock {
                val inProgress = settings.cloudPresenceRefilingBackup()
                if (inProgress == null) {
                    val changes = findChanges(intervals, range)
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
                    val changes = findChanges(intervals, range)
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
                    val changes = findChanges(intervals, range)
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
                    sessions = inProgress.sessions,
                    ownedAppIds = ownedIds,
                    intervals = intervals,
                    range = range,
                    fundedSessionIds = fundedSessionIds(ownedIds),
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

    /**
     * Atomically applies a fully-acquired historical operation. The Room journal and the session
     * and imported-balance writes commit together; the DataStore marker is finalized afterward and
     * can be safely completed from the immutable Room payload after process death.
     */
    suspend fun applyHistorical(
        operation: CloudHistoricalOperation,
    ): CloudPresenceRefilingResult = applyHistorical(operation) { apply -> apply(operation) }

    /** Applies while the caller verifies the active reader identity under the established lock order. */
    suspend fun applyHistorical(
        operation: CloudHistoricalOperation,
        withCurrentOperation: suspend (
            suspend (CloudHistoricalOperation) -> CloudPresenceRefilingResult,
        ) -> CloudPresenceRefilingResult,
    ): CloudPresenceRefilingResult = syncCoordinator.withLock {
        derivedStateWrites.withLock {
            withCurrentOperation(::applyHistoricalGuarded)
        }
    }

    private suspend fun applyHistoricalGuarded(
        operation: CloudHistoricalOperation,
    ): CloudPresenceRefilingResult {
            val existingJournal = cloudHistoricalDao.journal(operation.operationId)
            val alreadyApplied = settings.cloudPresenceRefilingApplied.first()
            if (existingJournal?.state == CloudHistoricalStates.JOURNAL_REVERSED) {
                return CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
            }
            if (existingJournal != null && existingJournal.state !in setOf(
                    CloudHistoricalStates.JOURNAL_APPLY_COMMITTED,
                    CloudHistoricalStates.JOURNAL_APPLIED,
                )
            ) {
                error("Cloud historical apply journal is in an unsupported state")
            }
            if (alreadyApplied && existingJournal?.state != CloudHistoricalStates.JOURNAL_APPLY_COMMITTED) {
                return CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
            }

            val payload = if (existingJournal != null) {
                CloudHistoricalApplyPayloadCodec.decode(existingJournal.payloadJson).also { saved ->
                    require(saved.operationId == operation.operationId &&
                        saved.account == operation.account &&
                        existingJournal.readerGeneration == operation.readerGeneration &&
                        existingJournal.endpointIdentity == operation.endpointIdentity
                    ) { "Cloud historical journal does not match the requested operation" }
                }
            } else {
                if (alreadyApplied) {
                    return CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
                }
                transaction.run {
                    // Recheck under the Room transaction: only a complete, fixed operation can
                    // supply evidence to this all-or-nothing ledger commit.
                    val savedOperation = cloudHistoricalDao.operation(operation.operationId)
                        ?: error("Cloud historical operation no longer exists")
                    require(savedOperation.sameApplyIdentity(operation)) {
                        "Cloud historical operation identity changed before apply"
                    }
                    require(savedOperation.acquisitionComplete &&
                        savedOperation.state == CloudHistoricalStates.COMPLETE
                    ) { "Cloud historical acquisition is not complete" }

                    val stagedIntervals = cloudHistoricalDao.intervals(operation.operationId)
                        .mapNotNull { staged -> staged.toPresenceInterval() }
                    val sessionsBefore = sessionDao.getAll()
                    val ownedGames = gameDao.getAll().filter { it.source == GameSource.STEAM_OWNED }
                    val ownedIds = ownedGames.map { it.appId }.toSet()
                    val selection = savedOperation.toSelection()
                    val changes = cloudPresenceSessionRefiles(
                        sessions = sessionsBefore,
                        ownedAppIds = ownedIds,
                        intervals = stagedIntervals,
                        range = selection,
                        fundedSessionIds = fundedSessionIds(ownedIds),
                    )
                    val changedOriginalIds = changes.map { it.original.id }.toSet()
                    val replacementRows = changes.flatMap { change ->
                        change.replacement.mapIndexed { index, replacement ->
                            replacement.copy(id = if (index == 0) change.original.id else 0L)
                        }
                    }

                    val profile = playerProfileDao.get()
                    val importedBefore = ownedGames.associate { it.appId to it.backfillMinutes }
                    val canTransfer = profile?.playtimeBackfilled == true &&
                        savedOperation.confirmedCutoffAt != null
                    val allocation = if (canTransfer) {
                        CloudPresencePreDataAllocationRule.allocate(
                            intervals = stagedIntervals,
                            ownedAppIds = ownedIds,
                            importedMinutesByAppId = importedBefore,
                            existingSessions = sessionsBefore.filter { it.id !in changedOriginalIds },
                            refiledSessions = replacementRows,
                            selection = selection,
                            cutoffAt = checkNotNull(savedOperation.confirmedCutoffAt),
                        )
                    } else {
                        CloudPresencePreDataAllocation(
                            sessions = emptyList(),
                            transferredMinutesByAppId = emptyMap(),
                            remainingImportedMinutesByAppId = importedBefore,
                        )
                    }

                    val dailyBefore = if (changes.isNotEmpty() || allocation.sessions.isNotEmpty()) {
                        dailyProgressDao.getAllOrdered()
                    } else {
                        emptyList()
                    }
                    val replacementSnapshots = mutableListOf<CloudHistoricalSessionSnapshot>()
                    val createdSnapshots = mutableListOf<CloudHistoricalSessionSnapshot>()
                    for (change in changes) {
                        val rows = change.replacement
                        val first = rows.first().copy(id = change.original.id)
                        sessionDao.update(first)
                        replacementSnapshots += first.toCloudHistoricalSnapshot()
                        rows.drop(1).forEach { split ->
                            val inserted = split.copy(id = 0L)
                            val id = sessionDao.insert(inserted)
                            val stored = inserted.copy(id = id)
                            replacementSnapshots += stored.toCloudHistoricalSnapshot()
                            createdSnapshots += stored.toCloudHistoricalSnapshot()
                        }
                    }
                    allocation.transferredMinutesByAppId.toSortedMap().forEach { (appId, minutes) ->
                        check(gameDao.decrementImportedBalanceIfEnough(appId, minutes) == 1) {
                            "Imported balance changed before historical apply for game $appId"
                        }
                    }
                    allocation.sessions.forEach { session ->
                        val inserted = session.copy(id = 0L)
                        val id = sessionDao.insert(inserted)
                        createdSnapshots += inserted.copy(id = id).toCloudHistoricalSnapshot()
                    }

                    val dates = (changes.flatMap { change ->
                        listOf(change.original) + change.replacement
                    } + allocation.sessions).map { it.localDate(selection.zoneId) }.toSortedSet()
                    val payload = CloudHistoricalApplyPayload(
                        operationId = savedOperation.operationId,
                        account = savedOperation.account,
                        startChoice = savedOperation.startChoice,
                        selectedStartAt = savedOperation.selectedStartAt,
                        effectiveStartAt = savedOperation.fromAt,
                        throughAt = savedOperation.throughAt,
                        coveredStartAt = savedOperation.coveredStartAt,
                        coveredEndAt = savedOperation.coveredEndAt,
                        zoneId = savedOperation.zoneId,
                        confirmedCutoffAt = savedOperation.confirmedCutoffAt,
                        pagesFetched = savedOperation.pagesFetched,
                        transitionsFetched = savedOperation.transitionsFetched,
                        originalSessions = changes.map { it.original.toCloudHistoricalSnapshot() },
                        replacementSessions = replacementSnapshots,
                        createdSessions = createdSnapshots,
                        transferredMinutesByAppId = allocation.transferredMinutesByAppId
                            .toSortedMap().map { (appId, minutes) ->
                                CloudHistoricalGameMinutes(appId, minutes)
                            },
                        remainingImportedMinutesByAppId = allocation.remainingImportedMinutesByAppId
                            .toSortedMap().map { (appId, minutes) ->
                                CloudHistoricalGameMinutes(appId, minutes)
                            },
                        dailyProgressBefore = dailyBefore.map { day ->
                            CloudHistoricalDailyProgressSnapshot(
                                day.date, day.minutesPlayed, day.goalMinutesPlayed, day.questMet,
                            )
                        },
                        sessionsRefiled = changes.size,
                        datesAffected = dates.toList(),
                    )
                    cloudHistoricalDao.upsertJournal(
                        CloudHistoricalJournal(
                            operationId = savedOperation.operationId,
                            account = savedOperation.account,
                            readerGeneration = savedOperation.readerGeneration,
                            endpointIdentity = savedOperation.endpointIdentity,
                            state = CloudHistoricalStates.JOURNAL_APPLY_COMMITTED,
                            payloadVersion = CloudHistoricalApplyPayload.CURRENT_VERSION,
                            payloadJson = CloudHistoricalApplyPayloadCodec.encode(payload),
                            updatedAt = time.nowMillis(),
                        ),
                    )
                    payload
                }
            }

            return finishHistoricalApply(payload)
    }

    suspend fun reverse(): CloudPresenceRefilingResult = syncCoordinator.withLock {
        derivedStateWrites.withLock {
            val applied = settings.cloudPresenceRefilingApplied.first()
            val receipt = settings.cloudPresenceRefilingReceipt.first()
            val journal = cloudHistoricalDao.reverseCommittedJournal()
                ?: receipt?.let { cloudHistoricalDao.journal(it.operationId) }

            if (journal != null) {
                when (journal.state) {
                    CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED ->
                        return@withLock finishHistoricalReverse(journal)

                    CloudHistoricalStates.JOURNAL_REVERSED -> {
                        if (!applied) {
                            return@withLock CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
                        }
                        val payload = CloudHistoricalApplyPayloadCodec.decode(journal.payloadJson)
                        settings.clearCloudPresenceRefiling()
                        return@withLock CloudPresenceRefilingResult(
                            operation = CloudPresenceRefilingOperation.REVERSED,
                            sessionsRefiled = payload.sessionsRefiled,
                            datesAffected = payload.datesAffected.toSet(),
                        )
                    }

                    CloudHistoricalStates.JOURNAL_APPLIED -> if (applied) {
                        return@withLock reverseHistorical(journal)
                    }

                    CloudHistoricalStates.JOURNAL_APPLY_COMMITTED -> if (applied) {
                        val payload = CloudHistoricalApplyPayloadCodec.decode(journal.payloadJson)
                        finishHistoricalApply(payload)
                        val finalized = cloudHistoricalDao.journal(journal.operationId)
                            ?: error("Cloud historical apply journal disappeared before reversal")
                        return@withLock reverseHistorical(finalized)
                    }
                }
            }

            if (!applied) {
                return@withLock CloudPresenceRefilingResult(CloudPresenceRefilingOperation.NO_OP)
            }
            reverseLegacy()
        }
    }

    private suspend fun reverseHistorical(
        journal: CloudHistoricalJournal,
    ): CloudPresenceRefilingResult {
        val payload = CloudHistoricalApplyPayloadCodec.decode(journal.payloadJson)
        require(journal.operationId == payload.operationId && journal.account == payload.account) {
            "Cloud historical reversal journal does not match its payload"
        }
        require(journal.state in setOf(
            CloudHistoricalStates.JOURNAL_APPLIED,
            CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED,
        )) { "Cloud historical reversal journal is in an unsupported state" }

        if (journal.state == CloudHistoricalStates.JOURNAL_APPLIED) {
            transaction.run {
                val savedJournal = cloudHistoricalDao.journal(payload.operationId)
                    ?: error("Cloud historical apply journal disappeared before reversal")
                require(savedJournal.state == CloudHistoricalStates.JOURNAL_APPLIED &&
                    savedJournal.payloadJson == journal.payloadJson &&
                    savedJournal.account == payload.account &&
                    savedJournal.readerGeneration == journal.readerGeneration &&
                    savedJournal.endpointIdentity == journal.endpointIdentity
                ) { "Cloud historical journal changed before reversal" }
                val operation = cloudHistoricalDao.operation(payload.operationId)
                    ?: error("Cloud historical operation disappeared before reversal")
                require(operation.account == payload.account &&
                    operation.readerGeneration == journal.readerGeneration &&
                    operation.endpointIdentity == journal.endpointIdentity &&
                    operation.state == CloudHistoricalStates.APPLIED
                ) { "Cloud historical operation identity changed before reversal" }

                val currentById = sessionDao.getAll().associateBy { it.id }
                // Preserve an entire original/replacement group when it has acquired funded state
                // or its total quantity changed since apply. In particular, never restore the
                // full parent beside a surviving funded/reduced child.
                val retainedRefileGroups = refileGroups(payload).filter { group ->
                    val currentMinutes = group.replacements.sumOf { replacement ->
                        currentById[replacement.id]?.minutes?.toLong() ?: 0L
                    }
                    currentMinutes != group.original.minutes.toLong() ||
                        group.replacements.any { activeFundedMinutes(it.id) > 0 }
                }
                val preservedOriginalIds = retainedRefileGroups.map { it.original.id }.toSet()
                val preservedReplacementIds = retainedRefileGroups
                    .flatMap { group -> group.replacements.map { it.id } }
                    .toSet()
                payload.createdSessions.forEach { created ->
                    // A created row that has since acquired an active funded share is owned by the
                    // recovery graph; deleting it would orphan that share and drop its minutes.
                    if (created.id in preservedReplacementIds || activeFundedMinutes(created.id) > 0) {
                        return@forEach
                    }
                    sessionDao.deleteById(created.id)
                }
                payload.originalSessions.forEach { original ->
                    if (original.id in preservedOriginalIds) return@forEach
                    restoreOriginalSession(original.toSession(), currentById)
                }
                payload.transferredMinutesByAppId.forEach { delta ->
                    check(gameDao.restoreImportedBalanceDelta(delta.appId, delta.minutes) == 1) {
                        "Imported balance could not be restored for game ${delta.appId}"
                    }
                }
                check(
                    cloudHistoricalDao.updateOperationState(
                        operationId = operation.operationId,
                        account = operation.account,
                        readerGeneration = operation.readerGeneration,
                        endpointIdentity = operation.endpointIdentity,
                        expectedState = CloudHistoricalStates.APPLIED,
                        state = CloudHistoricalStates.REVERSING,
                        updatedAt = time.nowMillis(),
                    ) == 1,
                ) { "Cloud historical operation could not enter reversal" }
                cloudHistoricalDao.upsertJournal(
                    savedJournal.copy(
                        state = CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED,
                        updatedAt = time.nowMillis(),
                    ),
                )
            }
        }

        return finishHistoricalReverse(
            cloudHistoricalDao.journal(payload.operationId)
                ?: error("Cloud historical reversal journal disappeared after ledger commit"),
        )
    }

    /** Finish non-Room side effects idempotently from the immutable journal after Room reversal. */
    private suspend fun finishHistoricalReverse(
        journal: CloudHistoricalJournal,
    ): CloudPresenceRefilingResult {
        val payload = CloudHistoricalApplyPayloadCodec.decode(journal.payloadJson)
        require(journal.state == CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED &&
            journal.operationId == payload.operationId && journal.account == payload.account
        ) { "Cloud historical reversal journal does not match its payload" }

        val hasLedgerChanges = payload.originalSessions.isNotEmpty() || payload.createdSessions.isNotEmpty()
        val hasBalanceChanges = payload.transferredMinutesByAppId.isNotEmpty()
        val recomputedDates = if (hasLedgerChanges || hasBalanceChanges) recompute() else emptySet()
        val affectedDates = (
            payload.datesAffected + payload.originalSessions.map { it.toSession().localDate(payload.zoneId) } +
                payload.replacementSessions.map { it.toSession().localDate(payload.zoneId) } +
                payload.createdSessions.map { it.toSession().localDate(payload.zoneId) } + recomputedDates
            ).toSet()

        transaction.run {
            val savedJournal = cloudHistoricalDao.journal(payload.operationId)
                ?: error("Cloud historical reversal journal disappeared before finalization")
            require(savedJournal.state == CloudHistoricalStates.JOURNAL_REVERSE_COMMITTED &&
                savedJournal.payloadJson == journal.payloadJson && savedJournal.account == payload.account
            ) { "Cloud historical reversal journal changed before finalization" }
            val operation = cloudHistoricalDao.operation(payload.operationId)
                ?: error("Cloud historical operation disappeared before reversal finalization")
            require(operation.account == payload.account &&
                operation.readerGeneration == journal.readerGeneration &&
                operation.endpointIdentity == journal.endpointIdentity
            ) { "Cloud historical operation identity changed before reversal finalization" }
            if (operation.state == CloudHistoricalStates.REVERSING) {
                check(
                    cloudHistoricalDao.updateOperationState(
                        operationId = operation.operationId,
                        account = operation.account,
                        readerGeneration = operation.readerGeneration,
                        endpointIdentity = operation.endpointIdentity,
                        expectedState = CloudHistoricalStates.REVERSING,
                        state = CloudHistoricalStates.REVERSED,
                        updatedAt = time.nowMillis(),
                    ) == 1,
                ) { "Cloud historical operation could not finish reversal" }
            } else {
                require(operation.state == CloudHistoricalStates.REVERSED) {
                    "Cloud historical operation is not recoverable after reversal"
                }
            }
            cloudHistoricalDao.upsertJournal(
                savedJournal.copy(state = CloudHistoricalStates.JOURNAL_REVERSED, updatedAt = time.nowMillis()),
            )
        }

        // The Room journal is now a durable proof that offsets and rows were already restored.
        // If this edit fails or the process dies, the next call only clears this marker; it never
        // replays the ledger mutation.
        settings.clearCloudPresenceRefiling()
        return CloudPresenceRefilingResult(
            operation = CloudPresenceRefilingOperation.REVERSED,
            sessionsRefiled = payload.sessionsRefiled,
            datesAffected = affectedDates,
        )
    }

    private suspend fun reverseLegacy(): CloudPresenceRefilingResult {
        val backup = settings.cloudPresenceRefilingBackup() ?: CloudPresenceRefilingBackup(emptyList())
        val current = sessionDao.getAll()
        val affectedDates = backup.sessions.map(::dateOf).toMutableSet().apply {
            addAll(current.filter { it.id in backup.createdSessionIds }.map(::dateOf))
            addAll(backup.dailyProgress.map { it.date })
            addAll(backup.createdDailyProgressDates)
        }
        transaction.run {
            val currentById = sessionDao.getAll().associateBy { it.id }
            // The legacy DataStore backup has no original->split mapping. Recover each group from
            // the immutable original interval and the operation-owned created ids. If overlapping
            // source intervals make ownership ambiguous, conservatively retain that app's whole
            // operation rather than restore a parent beside a funded child.
            val (groups, ambiguousApps) = legacyRefileGroups(backup, currentById)
            val retainedGroups = groups.filter { group ->
                val currentMinutes = group.replacements.sumOf { it.minutes.toLong() }
                currentMinutes != group.original.minutes.toLong() ||
                    group.replacements.any { activeFundedMinutes(it.id) > 0 }
            }
            val preservedOriginalIds = retainedGroups.map { it.original.id }.toSet()
            val preservedCreatedIds = retainedGroups.flatMap { group ->
                group.replacements.map { it.id }
            }.toSet()
            backup.createdSessionIds.forEach { id ->
                // Keep a funded group intact, plus every id whose ownership could not be proved.
                if (id in preservedCreatedIds || currentById[id]?.appId in ambiguousApps) return@forEach
                sessionDao.deleteById(id)
            }
            backup.sessions.forEach { session ->
                if (session.id in preservedOriginalIds || session.appId in ambiguousApps) return@forEach
                restoreOriginalSession(session, currentById)
            }
        }
        // Rebuild from the restored ledger so later ordinary sessions on these dates survive.
        val hasWork = backup.sessions.isNotEmpty() || backup.createdSessionIds.isNotEmpty() ||
            backup.dailyProgress.isNotEmpty() || backup.createdDailyProgressDates.isNotEmpty()
        val recomputedDates = if (hasWork) recompute() else emptySet()
        if (hasWork && backup.createdDailyProgressDates.isNotEmpty()) {
            val datesWithSessions = sessionDao.getAll().map(::dateOf).toSet()
            backup.createdDailyProgressDates
                .filter { it !in datesWithSessions }
                .forEach { date -> dailyProgressDao.deleteByDate(date) }
        }
        settings.clearCloudPresenceRefiling()
        return CloudPresenceRefilingResult(
            operation = CloudPresenceRefilingOperation.REVERSED,
            sessionsRefiled = backup.sessions.size,
            datesAffected = affectedDates + recomputedDates,
        )
    }

    private suspend fun finishHistoricalApply(
        payload: CloudHistoricalApplyPayload,
    ): CloudPresenceRefilingResult {
        val savedOperation = cloudHistoricalDao.operation(payload.operationId)
            ?: error("Cloud historical operation disappeared before receipt persistence")
        val originals = payload.originalSessions.map(CloudHistoricalSessionSnapshot::toSession)
        val createdIds = payload.createdSessions.map { it.id }.toSet()
        val dailyBefore = payload.dailyProgressBefore.map { day ->
            DailyProgress(
                date = day.date,
                minutesPlayed = day.minutesPlayed,
                goalMinutesPlayed = day.goalMinutesPlayed,
                questMet = day.questMet,
            )
        }
        val hasLedgerChanges = originals.isNotEmpty() || createdIds.isNotEmpty()

        val recomputedDates = if (hasLedgerChanges) recompute() else emptySet()
        val createdDailyDates = if (hasLedgerChanges) {
            dailyProgressDao.getAllOrdered().map { it.date }.toSet() - dailyBefore.map { it.date }.toSet()
        } else {
            emptySet()
        }
        val affectedDates = (payload.datesAffected.toSet() + recomputedDates).toSortedSet().toList()
        settings.completeCloudPresenceRefiling(
            CloudPresenceRefilingBackup(
                sessions = originals,
                createdSessionIds = createdIds,
                dailyProgress = dailyBefore,
                createdDailyProgressDates = createdDailyDates,
            ),
            CloudPresenceRefilingReceipt(
                operationId = payload.operationId,
                account = payload.account,
                startChoice = payload.startChoice,
                selectedStartAt = payload.selectedStartAt,
                effectiveStartAt = payload.effectiveStartAt,
                throughAt = payload.throughAt,
                coveredStartAt = payload.coveredStartAt ?: savedOperation.coveredStartAt,
                coveredEndAt = payload.coveredEndAt ?: savedOperation.coveredEndAt,
                confirmedCutoffAt = payload.confirmedCutoffAt,
                zoneId = payload.zoneId,
                pagesFetched = payload.pagesFetched.takeIf { it > 0 } ?: savedOperation.pagesFetched,
                transitionsFetched = payload.transitionsFetched.takeIf { it > 0 }
                    ?: savedOperation.transitionsFetched,
                sessionsRefiled = payload.sessionsRefiled,
                datesAffected = affectedDates,
                createdSessionIds = createdIds.sorted(),
                transferredMinutesByAppId = payload.transferredMinutesByAppId.map { delta ->
                    CloudPresenceRefilingGameMinutes(delta.appId, delta.minutes)
                },
                remainingImportedMinutesByAppId = payload.remainingImportedMinutesByAppId.map { balance ->
                    CloudPresenceRefilingGameMinutes(balance.appId, balance.minutes)
                },
            ),
        )

        finalizeHistoricalJournal(payload)

        return CloudPresenceRefilingResult(
            operation = CloudPresenceRefilingOperation.APPLIED,
            sessionsRefiled = payload.sessionsRefiled,
            datesAffected = affectedDates.toSet(),
        )
    }

    private suspend fun finalizeHistoricalJournal(payload: CloudHistoricalApplyPayload) = transaction.run {
        val savedOperation = cloudHistoricalDao.operation(payload.operationId)
            ?: error("Cloud historical operation disappeared after apply")
        val journal = cloudHistoricalDao.journal(payload.operationId)
            ?: error("Cloud historical apply journal disappeared after apply")
        require(journal.account == payload.account &&
            CloudHistoricalApplyPayloadCodec.decode(journal.payloadJson) == payload
        ) { "Cloud historical apply journal changed before finalization" }
        if (journal.state == CloudHistoricalStates.JOURNAL_APPLY_COMMITTED) {
            cloudHistoricalDao.upsertJournal(
                journal.copy(state = CloudHistoricalStates.JOURNAL_APPLIED, updatedAt = time.nowMillis()),
            )
        } else {
            require(journal.state == CloudHistoricalStates.JOURNAL_APPLIED) {
                "Cloud historical apply journal is not recoverable"
            }
        }
        when (savedOperation.state) {
            CloudHistoricalStates.COMPLETE -> check(
                cloudHistoricalDao.updateOperationState(
                    operationId = savedOperation.operationId,
                    account = savedOperation.account,
                    readerGeneration = savedOperation.readerGeneration,
                    endpointIdentity = savedOperation.endpointIdentity,
                    expectedState = CloudHistoricalStates.COMPLETE,
                    state = CloudHistoricalStates.APPLIED,
                    updatedAt = time.nowMillis(),
                ) == 1,
            ) { "Cloud historical operation could not be marked applied" }
            CloudHistoricalStates.APPLIED -> Unit
            else -> error("Cloud historical operation state changed before finalization")
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

    private suspend fun findChanges(
        intervals: List<CloudPresenceInterval>,
        range: CloudPresenceHistoricalSelection?,
    ): List<Change> {
        val ownedIds = gameDao.getAll()
            .asSequence()
            .filter { it.source == GameSource.STEAM_OWNED }
            .map { it.appId }
            .toSet()
        return cloudPresenceSessionRefiles(
            sessions = sessionDao.getAll(),
            ownedAppIds = ownedIds,
            intervals = intervals,
            range = range,
            fundedSessionIds = fundedSessionIds(ownedIds),
        ).map { change -> Change(change.original, change.replacement) }
    }

    /**
     * Sessions carrying an active funded recovery share — never moved by a historical re-file.
     * Looked up per owned game through the recovery contribution graph (no extra network reads).
     */
    private suspend fun fundedSessionIds(ownedAppIds: Set<Long>): Set<Long> {
        val dao = recoveryDao ?: return emptySet()
        val account = playerProfileDao.get()?.steamId?.takeIf { it.isNotBlank() } ?: return emptySet()
        val ids = mutableSetOf<Long>()
        for (appId in ownedAppIds) {
            for (contribution in dao.contributionsForGame(account, appId)) {
                dao.activeTimedPlacements(contribution.contributionId).forEach { share ->
                    share.sessionId?.let { ids += it }
                }
            }
        }
        return ids
    }

    /** Active funded minutes currently hosted by one session; the part a reversal must preserve. */
    private suspend fun activeFundedMinutes(sessionId: Long): Int =
        (recoveryDao?.activeTimedSharesForSession(sessionId)?.sumOf { it.minutes } ?: 0L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()

    private data class HistoricalRefileGroup(
        val original: CloudHistoricalSessionSnapshot,
        val replacements: List<CloudHistoricalSessionSnapshot>,
    )

    private data class LegacyRefileGroup(
        val original: Session,
        val replacements: List<Session>,
    )

    /**
     * Reconstruct the legacy backup's split families from original ids and bounded time intervals.
     * The returned set contains apps whose original->split ownership could not be proven.
     */
    private fun legacyRefileGroups(
        backup: CloudPresenceRefilingBackup,
        currentById: Map<Long, Session>,
    ): Pair<List<LegacyRefileGroup>, Set<Long>> {
        val originals = backup.sessions
        val rowsByOriginalId = originals.associate { original ->
            original.id to mutableListOf<Session>().apply { currentById[original.id]?.let(::add) }
        }.toMutableMap()
        val ambiguousApps = mutableSetOf<Long>()

        backup.createdSessionIds.forEach { createdId ->
            val row = currentById[createdId] ?: return@forEach
            val owners = originals.filter { original ->
                original.appId == row.appId &&
                    original.endAt != null && row.endAt != null &&
                    row.startAt >= original.startAt && row.endAt <= original.endAt
            }
            if (owners.size == 1) {
                rowsByOriginalId[owners.single().id]?.add(row) ?: run { ambiguousApps += row.appId }
            } else {
                ambiguousApps += row.appId
            }
        }

        val groups = originals.mapNotNull { original ->
            val rows = rowsByOriginalId[original.id].orEmpty()
            if (rows.isEmpty()) return@mapNotNull null
            LegacyRefileGroup(original, rows)
        }
        return groups to ambiguousApps
    }

    /** The payload stores replacements in original order, with each group's minutes conserving its original. */
    private fun refileGroups(payload: CloudHistoricalApplyPayload): List<HistoricalRefileGroup> {
        var nextReplacement = 0
        val groups = payload.originalSessions.map { original ->
            val replacements = mutableListOf<CloudHistoricalSessionSnapshot>()
            var groupedMinutes = 0L
            while (groupedMinutes < original.minutes && nextReplacement < payload.replacementSessions.size) {
                val replacement = payload.replacementSessions[nextReplacement++]
                require(replacement.appId == original.appId) {
                    "Cloud historical replacement group changed app at original ${original.id}"
                }
                replacements += replacement
                groupedMinutes += replacement.minutes
            }
            require(groupedMinutes == original.minutes.toLong()) {
                "Cloud historical replacement group does not conserve original ${original.id}"
            }
            HistoricalRefileGroup(original, replacements)
        }
        require(nextReplacement == payload.replacementSessions.size) {
            "Cloud historical payload has unowned replacement sessions"
        }
        return groups
    }

    /** Restore only a split family already proved unchanged and unfunded by its caller. */
    private suspend fun restoreOriginalSession(
        original: Session,
        currentById: Map<Long, Session>,
    ) {
        if (currentById[original.id] != null) sessionDao.update(original)
    }

    private suspend fun recompute(): Set<String> {
        val account = playerProfileDao.get()?.steamId?.trim()?.takeIf { it.isNotEmpty() }
        val activeTimed = account?.let { recoveryDao?.activeTimedPlacementsForAccount(it) }.orEmpty()
        val activeAllocations = account?.let { recoveryDao?.activeAllocationsForAccount(it) }.orEmpty()
        val corrections = dailyProgressCorrections(
            sessions = sessionDao.getAll(),
            goalAppIds = gameDao.getAll().asSequence()
                .filter { it.isGoal }
                .map { it.appId }
                .toSet(),
            stored = dailyProgressDao.getAllOrdered(),
            zone = time.zone(),
            timedShares = activeTimed,
            allocations = activeAllocations,
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

    private fun Session.localDate(zoneId: String): String =
        Instant.ofEpochMilli(startAt).atZone(ZoneId.of(zoneId)).toLocalDate().toString()

    private fun CloudHistoricalOperation.toSelection(): CloudPresenceHistoricalSelection =
        CloudPresenceHistoricalSelection(
            choice = CloudPresenceHistoricalStartChoice.valueOf(startChoice),
            selectedStartAt = selectedStartAt,
            effectiveStartAt = fromAt,
            throughAt = throughAt,
            zoneId = zoneId,
        )

    private fun CloudHistoricalInterval.toPresenceInterval(): CloudPresenceInterval =
        CloudPresenceInterval(
            appId = appId,
            gameName = gameName,
            startAt = startAt,
            endAt = endAt,
            ongoing = ongoing,
            coverage = runCatching { CloudCoverageState.valueOf(coverage) }
                .getOrDefault(CloudCoverageState.UNKNOWN),
            observedUntil = observedUntil,
            coverageLapseFrom = coverageLapseFrom,
            coverageLapseRecoveredAt = coverageLapseRecoveredAt,
            mayHaveStartedBefore = mayHaveStartedBefore,
        )

    private fun CloudHistoricalOperation.sameApplyIdentity(other: CloudHistoricalOperation): Boolean =
        operationId == other.operationId &&
            account == other.account &&
            readerGeneration == other.readerGeneration &&
            endpointIdentity == other.endpointIdentity &&
            startChoice == other.startChoice &&
            zoneId == other.zoneId &&
            selectedStartAt == other.selectedStartAt &&
            fromAt == other.fromAt &&
            throughAt == other.throughAt &&
            confirmedCutoffAt == other.confirmedCutoffAt

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
