package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * One funded Steam quantity authorization: the authoritative minutes credited by a positive diff
 * over the cumulative range `(fromMinutes, toMinutes]` (recover-missed-playtime, design.md
 * Decision 2).
 *
 * [contributionId] is a *derived*, stable identity — account + app + baseline epoch + cumulative
 * range — not a poll timestamp or an autoincrement id, so replaying the same observation is
 * idempotent and a restored backup keeps the same identity. The Steam-authorized quantity lives
 * here once; timed placements and unknown-time allocations only say *where* it landed, so they are
 * never summed on top of it.
 *
 * [revision] increments when delayed evidence replaces unlocked estimate placement for this same
 * funded range; the previous placement rows are retained and marked superseded, and a journal entry
 * links the two revisions.
 */
@Entity(
    tableName = "playtime_contributions",
    primaryKeys = ["contributionId"],
    indices = [Index(value = ["account", "appId"])],
)
data class PlaytimeContribution(
    val contributionId: String,
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    /** Exclusive lower bound of the cumulative range. */
    val fromMinutes: Long,
    /** Inclusive upper bound of the cumulative range. */
    val toMinutes: Long,
    /** Previous usable observation instant that opened the recovery window; null when unknown. */
    val windowStartAt: Long? = null,
    /** The recovery observation instant that closed the window. */
    val windowEndAt: Long,
    val authorizedMinutes: Long,
    val revision: Long = 1L,
    /** Progression provenance for this credit (`SYNC` fresh versus `RETROACTIVE_PLAY`). */
    val provenance: String,
    /** Frozen recovery zone used to normalize [windowStartAt]/[windowEndAt] into dates. */
    val zone: String? = null,
    /**
     * The device zone the recovery reading was made in, kept for timezone diagnostics and `!`
     * explanations. Distinct from [zone] by construction in the current planner, but retained
     * independently so a later timezone change cannot erase where the endpoints were observed.
     */
    val observedZone: String? = null,
    val createdAt: Long,
)

/** Progression provenance markers for a funded contribution. */
object RecoveryProvenance {
    const val SYNC = "SYNC"
    const val RETROACTIVE_PLAY = "RETROACTIVE_PLAY"
}
