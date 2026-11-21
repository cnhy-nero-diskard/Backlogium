package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Durable journal entry for a recovery replacement/finalization operation
 * (recover-missed-playtime, design.md Decision 8).
 *
 * A correction supersedes one placement revision with another for the same funded contribution;
 * this row preserves the evidence/operation identity, the prior and current revisions, the dates
 * whose daily credit changed, and a durable description of the staged derived finalization (the
 * corrective baseline excluding this operation's fresh earned inputs, then the final state with
 * them). Stage completion persists here so a process-death retry can resume the same stages
 * without earning a transition twice or consuming a fresh event.
 *
 * Linking on [contributionId] means an operation's journal rows are removed with its contribution
 * (account reset, foreign import rejection), never silently left dangling.
 */
@Entity(
    tableName = "recovery_journal",
    primaryKeys = ["operationId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaytimeContribution::class,
            parentColumns = ["contributionId"],
            childColumns = ["contributionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["contributionId"]),
        Index(value = ["account", "derivedStatus"]),
    ],
)
data class PlaytimeRecoveryJournal(
    val operationId: String,
    val contributionId: String,
    val account: String,
    val appId: Long,
    val priorRevision: Long? = null,
    val currentRevision: Long,
    /** Comma-separated ISO dates whose raw daily credit this operation touched. */
    val affectedDatesCsv: String,
    /**
     * Durable replacement aliases: the superseded placement ids and the superseded recipe shape
     * (or null when only estimates existed). Persisting these means an older backup that still
     * carries the superseded revision cannot resurrect it as live placement during a merge.
     */
    val replacedPlacementIdsCsv: String? = null,
    val replacedRecipeJson: String? = null,
    val derivedStatus: String = RecoveryJournalStatus.PENDING,
    /** Durable description of input identities/versions needed to resume staged finalization. */
    val payloadJson: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Durable stage states for a recovery operation's derived finalization. */
object RecoveryJournalStatus {
    const val PENDING = "PENDING"
    const val RAW_COMMITTED = "RAW_COMMITTED"
    const val CORRECTIVE_DONE = "CORRECTIVE_DONE"
    const val EARNED_DONE = "EARNED_DONE"
    const val FINALIZED = "FINALIZED"
}