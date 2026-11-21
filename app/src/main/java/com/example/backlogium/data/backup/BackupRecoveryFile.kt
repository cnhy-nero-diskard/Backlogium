package com.example.backlogium.data.backup

import kotlinx.serialization.Serializable

/**
 * The mandatory version 3 recovery section (recover-missed-playtime, design.md Decision 10).
 *
 * Version 3 makes recovery a first-class, *explicit* part of a backup rather than something an
 * importer might infer from session timestamps. Every section is present in a v3 file — an empty
 * section is the empty list — and [BackupValidator] rejects a v3 payload whose `recovery` block or
 * any of its sections is absent or null, so a truncated write can never read as "nothing to
 * recover".
 *
 * The fields are nullable-with-default purely to tell *absent* from *explicitly empty* at decode
 * time, which is the distinction the v3 preflight is built on. [BackupExportMapper] always writes
 * every section non-null, so a file it produced never exercises the nullable path.
 *
 * Nothing here is a gameplay boundary: [BackupUnknownAllocation] has a date and an observation
 * window but no session start/end, and observation instants are retained as windows for
 * explanations, never promoted into a session. No credentials, cloud reader URL, read cursor or
 * pending interval evidence is representable in this section.
 */
@Serializable
data class BackupRecovery(
    val observations: List<BackupRecoveryObservation>? = null,
    val contributions: List<BackupContribution>? = null,
    val timedPlacements: List<BackupTimedPlacement>? = null,
    val unknownAllocations: List<BackupUnknownAllocation>? = null,
    val recipes: List<BackupAllocationRecipe>? = null,
    val replacements: List<BackupReplacement>? = null,
)

/**
 * One per-account, per-game recovery baseline: the authoritative cumulative high-water the next
 * reading is diffed against, the baseline epoch that contribution identities embed, and the last
 * usable observation window. [lastObservationZone]/[lastObservationDate] stay null when the
 * install never captured a trustworthy zone, rather than inventing one.
 */
@Serializable
data class BackupRecoveryObservation(
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    val highWaterMinutes: Long,
    val lastObservationAt: String? = null,
    val lastObservationDate: String? = null,
    val lastObservationZone: String? = null,
    val clockState: String = "UNKNOWN",
)

/**
 * One funded Steam quantity authorization over the cumulative range `(fromMinutes, toMinutes]`.
 * [authorizedMinutes] equals the range width; [revision] is the placement revision shared with the
 * placements below, and [zone]/[observedZone] freeze the recovery zone separately from the device
 * zone the reading was made in.
 */
@Serializable
data class BackupContribution(
    val contributionId: String,
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    val fromMinutes: Long,
    val toMinutes: Long,
    val windowStartAt: String? = null,
    val windowEndAt: String,
    val authorizedMinutes: Long,
    val revision: Long,
    val provenance: String,
    val zone: String? = null,
    val observedZone: String? = null,
    val createdAt: Long = 0L,
)

/**
 * A funded share placed against an actual timestamped session. It documents *where* contribution
 * quantity lives; it never earns quantity on its own. [protected] marks a manually-protected
 * placement a later automatic upgrade must not replace.
 *
 * The session is referenced by its *portable natural key* ([sessionStartAt]/[sessionEndAt]) rather
 * than a local autoincrement row id, so a restored share can be re-linked to the exact session row
 * an import inserts/upserts. This is what keeps a mixed session's funded minutes inside the row
 * instead of becoming a detached share that would double-count against the same row's minutes.
 */
@Serializable
data class BackupTimedPlacement(
    val placementId: String,
    val contributionId: String,
    val account: String,
    val appId: Long,
    val revision: Long,
    val minutes: Long,
    val creditedDate: String,
    val zone: String,
    val sessionStartAt: String? = null,
    val sessionEndAt: String? = null,
    val evidenceIdentity: String? = null,
    val evidenceQuality: String = "ESTIMATE",
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean = false,
    val superseded: Boolean = false,
    val protected: Boolean = false,
    val createdAt: Long = 0L,
)

/**
 * One day-level unknown-time allocation (or explicit pending date-unknown quantity). There is no
 * session start/end: [date] alone is the attribution, and a pending allocation carries a null
 * [date] with [pendingDate] set. [method] drives the `!` explanation.
 */
@Serializable
data class BackupUnknownAllocation(
    val placementId: String,
    val contributionId: String,
    val account: String,
    val appId: Long,
    val revision: Long,
    val date: String? = null,
    val minutes: Long,
    val method: String = "MULTI_DAY_ESTIMATE",
    val zone: String? = null,
    val windowStartAt: String? = null,
    val windowEndAt: String? = null,
    val pendingDate: Boolean = false,
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean = false,
    val superseded: Boolean = false,
    val protected: Boolean = false,
    val createdAt: Long = 0L,
)

/**
 * A compact quotient/remainder recipe for a wide unknown-time window. Preserving it is what keeps
 * a long gap's exact quantity in a backup without one row per day and without inventing dated
 * rows; the materialized slices are still carried in [BackupRecovery.unknownAllocations].
 */
@Serializable
data class BackupAllocationRecipe(
    val contributionId: String,
    val account: String,
    val appId: Long,
    val revision: Long,
    val method: String,
    val quotient: Long? = null,
    val remainderCount: Long? = null,
    val dateStart: String? = null,
    val dateEnd: String? = null,
    val eligibleCount: Int = 0,
    val zone: String? = null,
    val windowStartAt: String? = null,
    val windowEndAt: String? = null,
    val excludedDatesCsv: String? = null,
    val pending: Boolean = false,
    val materializedMinutes: Long = 0L,
    val materializedThroughDate: String? = null,
    val hiddenAtCredit: Boolean = false,
    val goalAtCredit: Boolean = false,
)

/**
 * A durable replacement alias/tombstone exported from the recovery journal. [replacedPlacementIdsCsv]
 * names the superseded placement ids, so an older backup that still carries one of them cannot
 * resurrect it as live placement during a merge.
 *
 * [payloadJson] carries the journal's durable stage description (fresh-earned input ids/versions for
 * a mixed commit) and may additionally carry portable session tombstones under
 * [RecoveryBackupSessionTombstones.key] when a superseded synthetic session is known
 * (recover-missed-playtime tasks 6.4 and 8.3). Both fields round-trip verbatim so an unfinished
 * derived finalization stays resumable across a clear-and-rewrite.
 */
@Serializable
data class BackupReplacement(
    val operationId: String,
    val contributionId: String,
    val account: String,
    val appId: Long,
    val priorRevision: Long? = null,
    val currentRevision: Long,
    val affectedDatesCsv: String,
    val replacedPlacementIdsCsv: String? = null,
    val replacedRecipeJson: String? = null,
    val payloadJson: String? = null,
    val derivedStatus: String = "PENDING",
)
