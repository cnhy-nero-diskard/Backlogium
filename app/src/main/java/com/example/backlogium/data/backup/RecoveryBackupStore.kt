package com.example.backlogium.data.backup

import com.example.backlogium.data.local.dao.PlaytimeRecoveryDao
import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.GameRecoveryState
import com.example.backlogium.data.local.entity.PlaytimeContribution
import com.example.backlogium.data.local.entity.PlaytimeRecoveryJournal
import com.example.backlogium.data.local.entity.RecoveryAllocationMethods
import com.example.backlogium.data.local.entity.RecoveryJournalStatus
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimedContributionPlacement
import com.example.backlogium.data.local.entity.UnknownTimeAllocation
import com.example.backlogium.data.local.entity.UnknownTimeAllocationRecipe
import com.example.backlogium.data.local.recovery.PlaytimeRecoveryIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The narrow seam between the backup format and the recovery storage tables (recover-missed-playtime,
 * design.md Decision 10). It exposes only what an export/import needs, in DTO terms, so the export
 * mapper and merge engine stay plain and JVM-testable and no storage entity leaks into the format.
 *
 * A no-op implementation is the default for tests that do not exercise recovery; production is
 * bound to [DaoRecoveryBackupStore], which is the only class that touches
 * [PlaytimeRecoveryDao].
 */
interface RecoveryBackupStore {
    /** The account's recovery graph, read at one point in time. */
    suspend fun read(account: String): BackupRecovery

    /**
     * Apply a validated merge plan, rewriting only the affected accounts' contribution graphs.
     * [sessionIdByKey] resolves a share's portable session key to the actual session row this
     * import inserted/upserted; a timed share whose session does not exist is written as a dated
     * allocation so its funded quantity is never duplicated against a row that is not present.
     */
    suspend fun apply(
        plan: RecoveryMergePlan,
        nowMillis: Long,
        sessionIdByKey: Map<RecoverySessionKey, Long>,
    )
}

/** Portable session identity a timed share references; see [BackupTimedPlacement]. */
data class RecoverySessionKey(val appId: Long, val startAt: Long?, val endAt: Long?) {
    override fun toString(): String = "$appId|${startAt ?: "-"}|${endAt ?: "-"}"
}

/** Used by callers that have no recovery storage (legacy tests); recovery simply is not surfaced. */
object NoopRecoveryBackupStore : RecoveryBackupStore {
    override suspend fun read(account: String): BackupRecovery = emptyBackupRecovery()
    override suspend fun apply(
        plan: RecoveryMergePlan,
        nowMillis: Long,
        sessionIdByKey: Map<RecoverySessionKey, Long>,
    ) = Unit
}

/** An explicit, fully-populated empty recovery section — never a null section. */
internal fun emptyBackupRecovery() = BackupRecovery(
    observations = emptyList(),
    contributions = emptyList(),
    timedPlacements = emptyList(),
    unknownAllocations = emptyList(),
    recipes = emptyList(),
    replacements = emptyList(),
)

/**
 * Production [RecoveryBackupStore] over [PlaytimeRecoveryDao]. The per-account clear-and-rewrite is
 * atomic inside the caller's Room transaction: the merge engine already wraps it in
 * [DatabaseTransactionScope], so a failure anywhere rolls the whole import back together.
 *
 * Only existing DAO operations are used. Superseded audit rows are not re-exported, so a rewrite
 * flattens them into the carried replacement tombstones rather than re-inserting them.
 */
@Singleton
class DaoRecoveryBackupStore @Inject constructor(
    private val dao: PlaytimeRecoveryDao,
    private val sessionDao: SessionDao,
) : RecoveryBackupStore {

    override suspend fun read(account: String): BackupRecovery {
        if (account.isBlank()) return emptyBackupRecovery()
        val states = dao.statesForAccount(account)
        val recipesByContribution = dao.recipesForAccount(account).associateBy { it.contributionId }
        val journalsByContribution = dao.journalsForAccount(account).groupBy { it.contributionId }

        // Keep the entities and grouping maps for only one bounded game batch at a time. An
        // account-wide recovery read retained every Room entity, all group maps, and every backup
        // DTO simultaneously; the L-sized 100,000-contribution export exceeded Android's 201 MB
        // app-heap limit while formatting allocation windows.
        val contributions = mutableListOf<BackupContribution>()
        val timed = mutableListOf<BackupTimedPlacement>()
        val allocations = mutableListOf<BackupUnknownAllocation>()
        val recipes = mutableListOf<BackupAllocationRecipe>()
        val replacements = mutableListOf<BackupReplacement>()
        dao.contributionAppIdsForAccount(account).chunked(EXPORT_APP_ID_BATCH_SIZE).forEach { appIds ->
            // Contributions are the durable quantity authority and may legitimately outlive or
            // temporarily lack a baseline-state row. Read by contribution account/app, never by
            // baseline rows, so those recovery graphs remain exportable.
            val batchContributions = dao.contributionsForGames(account, appIds)
            val timedEntities = dao.activeTimedPlacementsForGames(account, appIds)
            val allocationEntities = dao.activeAllocationsForGames(account, appIds)
            val timedByContribution = timedEntities.groupBy { it.contributionId }
            val allocationsByContribution = allocationEntities.groupBy { it.contributionId }

            // The main export already reads all sessions. Resolve only hosts for this batch, in
            // bounded primary-key reads, rather than retaining a second full-table session scan.
            val sessionIds = timedEntities.mapNotNull { it.sessionId }.distinct()
            val hostSessions = sessionIds.chunked(SQLITE_BIND_BATCH_SIZE).flatMap { ids ->
                sessionDao.sessionsByIds(ids)
            }
            val sessionsById = hostSessions.associateBy { it.id }

            batchContributions.forEach { contribution ->
                val id = contribution.contributionId
                contributions += contribution.toBackup()
                timedByContribution[id].orEmpty().sortedBy { it.creditedDate }.forEach { placement ->
                    timed += placement.toBackup(sessionsById[placement.sessionId])
                }
                allocationsByContribution[id].orEmpty()
                    .sortedWith(compareBy<UnknownTimeAllocation> { it.pendingDate }.thenBy { it.date })
                    .forEach { allocation -> allocations += allocation.toBackup() }
                recipesByContribution[id]?.let { recipes += it.toBackup() }
                journalsByContribution[id].orEmpty().sortedBy { it.updatedAt }
                    .forEach { journal -> replacements += journal.toBackup() }
            }
        }
        return BackupRecovery(
            observations = states.map { it.toBackup() },
            contributions = contributions,
            timedPlacements = timed,
            unknownAllocations = allocations,
            recipes = recipes,
            replacements = replacements,
        )
    }

    override suspend fun apply(
        plan: RecoveryMergePlan,
        nowMillis: Long,
        sessionIdByKey: Map<RecoverySessionKey, Long>,
    ) {
        plan.affectedAccounts.forEach { account ->
            dao.clearJournalsForAccount(account)
            dao.clearAllocationsForAccount(account)
            dao.clearTimedPlacementsForAccount(account)
            dao.clearRecipesForAccount(account)
            dao.clearContributionsForAccount(account)
        }
        if (plan.observations.isNotEmpty()) {
            plan.observations.forEach { dao.upsertState(it.toEntity()) }
        }
        plan.contributions.forEach { graph ->
            val resolvedTimed = mutableListOf<TimedContributionPlacement>()
            val resolvedAllocations = mutableListOf<UnknownTimeAllocation>()
            graph.timedPlacements.forEach { placement ->
                val sessionId = placement.toSessionKey()?.let(sessionIdByKey::get)
                // Keep the funded timed share even if its portable host session was tombstoned or
                // could not be matched. A detached timed share is still a placement of the same
                // contribution, not another quantity; converting it beside a compact recipe would
                // double-count the residual graph and can lose evidence provenance.
                resolvedTimed += placement.toEntity(sessionId)
            }
            resolvedAllocations += graph.unknownAllocations.map { it.toEntity() }
            dao.commitAllocatedGraph(
                contribution = graph.contribution.toEntity(),
                timed = resolvedTimed,
                allocations = resolvedAllocations,
                recipe = graph.recipe?.takeUnless { it.pending }?.toEntity(),
            )
            if (graph.carriedTombstonePlacementIds.isNotEmpty()) {
                dao.upsertJournal(carryingJournal(graph, nowMillis))
            }
            graph.carriedJournals.forEach { replacement ->
                dao.upsertJournal(
                    replacement.toEntity(nowMillis).copy(
                        // The surrounding restore marks a durable pending-import recompute in this
                        // same Room transaction and recomputes all merged raw state under the
                        // receiver's rules. A journal from the displaced source cannot resume its
                        // old corrective payload against the replacement segment.
                        derivedStatus = RecoveryJournalStatus.FINALIZED,
                    ),
                )
            }
        }
        plan.replacements.forEach { replacement ->
            dao.upsertJournal(
                replacement.toEntity(nowMillis).copy(derivedStatus = RecoveryJournalStatus.FINALIZED),
            )
        }
    }

    private fun carryingJournal(
        graph: RecoveryContributionGraph,
        nowMillis: Long,
    ): PlaytimeRecoveryJournal {
        val contribution = graph.contribution
        return PlaytimeRecoveryJournal(
            operationId = PlaytimeRecoveryIdentity.journalOperationId(
                contribution.contributionId,
                contribution.revision,
                BACKUP_CARRY_EVIDENCE,
            ),
            contributionId = contribution.contributionId,
            account = contribution.account,
            appId = contribution.appId,
            priorRevision = null,
            currentRevision = contribution.revision,
            affectedDatesCsv = "",
            replacedPlacementIdsCsv = graph.carriedTombstonePlacementIds.joinToString(","),
            replacedRecipeJson = null,
            payloadJson = null,
            derivedStatus = RecoveryJournalStatus.FINALIZED,
            createdAt = nowMillis,
            updatedAt = nowMillis,
        )
    }

    private companion object {
        const val SQLITE_BIND_BATCH_SIZE = 900
        const val EXPORT_APP_ID_BATCH_SIZE = 250

        /** Marks a journal row that exists only to carry displaced tombstones through an import. */
        const val BACKUP_CARRY_EVIDENCE = "backup-carry"
    }
}

// ---------------------------------------------------------------------- entity <-> DTO

private fun BackupTimedPlacement.toSessionKey(): RecoverySessionKey? {
    val startAt = sessionStartAt?.iso8601ToEpochMilli() ?: return null
    val endAt = sessionEndAt?.iso8601ToEpochMilli()
    return RecoverySessionKey(appId, startAt, endAt)
}

private fun GameRecoveryState.toBackup() = BackupRecoveryObservation(
    account = account,
    appId = appId,
    baselineEpoch = baselineEpoch,
    highWaterMinutes = highWaterMinutes,
    lastObservationAt = lastObservationAt?.toIso8601(),
    lastObservationDate = lastObservationDate,
    lastObservationZone = lastObservationZone,
    clockState = clockState,
)

private fun BackupRecoveryObservation.toEntity() = GameRecoveryState(
    account = account,
    appId = appId,
    baselineEpoch = baselineEpoch,
    highWaterMinutes = highWaterMinutes,
    lastObservationAt = lastObservationAt?.iso8601ToEpochMilli(),
    lastObservationDate = lastObservationDate,
    lastObservationZone = lastObservationZone,
    clockState = clockState,
    updatedAt = lastObservationAt?.iso8601ToEpochMilli() ?: 0L,
)

private fun PlaytimeContribution.toBackup() = BackupContribution(
    contributionId = contributionId,
    account = account,
    appId = appId,
    baselineEpoch = baselineEpoch,
    fromMinutes = fromMinutes,
    toMinutes = toMinutes,
    windowStartAt = windowStartAt?.toIso8601(),
    windowEndAt = windowEndAt.toIso8601(),
    authorizedMinutes = authorizedMinutes,
    revision = revision,
    provenance = provenance,
    zone = zone,
    observedZone = observedZone,
    createdAt = createdAt,
)

private fun BackupContribution.toEntity() = PlaytimeContribution(
    contributionId = contributionId,
    account = account,
    appId = appId,
    baselineEpoch = baselineEpoch,
    fromMinutes = fromMinutes,
    toMinutes = toMinutes,
    windowStartAt = windowStartAt?.iso8601ToEpochMilli(),
    windowEndAt = windowEndAt.iso8601ToEpochMilli(),
    authorizedMinutes = authorizedMinutes,
    revision = revision,
    provenance = provenance,
    zone = zone,
    observedZone = observedZone,
    createdAt = createdAt,
)

private fun TimedContributionPlacement.toBackup(session: Session?) = BackupTimedPlacement(
    placementId = placementId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    minutes = minutes,
    creditedDate = creditedDate,
    zone = zone,
    sessionStartAt = session?.startAt?.toIso8601(),
    sessionEndAt = session?.endAt?.toIso8601(),
    evidenceIdentity = evidenceIdentity,
    evidenceQuality = evidenceQuality,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
    superseded = superseded,
    protected = protected,
    createdAt = createdAt,
)

private fun BackupTimedPlacement.toEntity(sessionId: Long?) = TimedContributionPlacement(
    placementId = placementId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    sessionId = sessionId,
    minutes = minutes,
    creditedDate = creditedDate,
    zone = zone,
    evidenceIdentity = evidenceIdentity,
    evidenceQuality = evidenceQuality,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
    superseded = superseded,
    protected = protected,
    createdAt = createdAt,
)

/** Fallback representation for a timed share whose session row is not part of the merge. */
private fun BackupTimedPlacement.toDetachedAllocation() = UnknownTimeAllocation(
    placementId = placementId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    date = creditedDate,
    minutes = minutes,
    method = RecoveryAllocationMethods.SINGLE_DAY,
    zone = zone,
    windowStartAt = null,
    windowEndAt = null,
    pendingDate = false,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
    superseded = false,
    protected = protected,
    createdAt = createdAt,
)

private fun UnknownTimeAllocation.toBackup() = BackupUnknownAllocation(
    placementId = placementId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    date = date,
    minutes = minutes,
    method = method,
    zone = zone,
    windowStartAt = windowStartAt?.toIso8601(),
    windowEndAt = windowEndAt?.toIso8601(),
    pendingDate = pendingDate,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
    superseded = superseded,
    protected = protected,
    createdAt = createdAt,
)

private fun BackupUnknownAllocation.toEntity() = UnknownTimeAllocation(
    placementId = placementId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    date = date,
    minutes = minutes,
    method = method,
    zone = zone,
    windowStartAt = windowStartAt?.iso8601ToEpochMilli(),
    windowEndAt = windowEndAt?.iso8601ToEpochMilli(),
    pendingDate = pendingDate,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
    superseded = superseded,
    protected = protected,
    createdAt = createdAt,
)

private fun UnknownTimeAllocationRecipe.toBackup() = BackupAllocationRecipe(
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    method = method,
    quotient = quotient,
    remainderCount = remainderCount,
    dateStart = dateStart,
    dateEnd = dateEnd,
    eligibleCount = eligibleCount,
    zone = zone,
    windowStartAt = windowStartAt?.toIso8601(),
    windowEndAt = windowEndAt?.toIso8601(),
    excludedDatesCsv = excludedDatesCsv,
    pending = pending,
    materializedMinutes = materializedMinutes,
    materializedThroughDate = materializedThroughDate,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
)

private fun BackupAllocationRecipe.toEntity() = UnknownTimeAllocationRecipe(
    contributionId = contributionId,
    account = account,
    appId = appId,
    revision = revision,
    method = method,
    quotient = quotient,
    remainderCount = remainderCount,
    dateStart = dateStart,
    dateEnd = dateEnd,
    eligibleCount = eligibleCount,
    zone = zone,
    windowStartAt = windowStartAt?.iso8601ToEpochMilli(),
    windowEndAt = windowEndAt?.iso8601ToEpochMilli(),
    excludedDatesCsv = excludedDatesCsv,
    pending = pending,
    materializedMinutes = materializedMinutes,
    materializedThroughDate = materializedThroughDate,
    hiddenAtCredit = hiddenAtCredit,
    goalAtCredit = goalAtCredit,
    updatedAt = 0L,
)

private fun PlaytimeRecoveryJournal.toBackup() = BackupReplacement(
    operationId = operationId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    priorRevision = priorRevision,
    currentRevision = currentRevision,
    affectedDatesCsv = affectedDatesCsv,
    replacedPlacementIdsCsv = replacedPlacementIdsCsv,
    replacedRecipeJson = replacedRecipeJson,
    payloadJson = payloadJson,
    derivedStatus = derivedStatus,
)

private fun BackupReplacement.toEntity(nowMillis: Long) = PlaytimeRecoveryJournal(
    operationId = operationId,
    contributionId = contributionId,
    account = account,
    appId = appId,
    priorRevision = priorRevision,
    currentRevision = currentRevision,
    affectedDatesCsv = affectedDatesCsv,
    replacedPlacementIdsCsv = replacedPlacementIdsCsv,
    replacedRecipeJson = replacedRecipeJson,
    payloadJson = payloadJson,
    derivedStatus = derivedStatus,
    createdAt = nowMillis,
    updatedAt = nowMillis,
)
