package com.example.backlogium.data.local.entity

import androidx.room.Entity

/**
 * The durable per-account, per-game Steam quantity baseline that playtime recovery diffs against
 * (recover-missed-playtime, design.md Decision 3).
 *
 * [highWaterMinutes] is the authoritative cumulative total the *next* reading is measured beyond.
 * It only ever advances — a lower reported value is a regression diagnostic, not a new quantity —
 * so the recovery window can never be shortened by a replayed or rolled-back reading.
 *
 * [baselineEpoch] is monotonically advanced whenever the baseline is deliberately re-established:
 * an account reset, a shared→owned conversion, or a fresh first reading after an untrustworthy
 * restore. Contribution identities embed it, so a re-established baseline can never be diffed
 * against a contribution minted for the previous epoch.
 *
 * [lastObservationZone] and [lastObservationDate] are nullable on purpose. A baseline seeded from
 * a pre-recovery install has a known high-water total but no captured zone or date; null records
 * that honestly instead of inventing a timezone the device may never have used.
 */
@Entity(
    tableName = "game_recovery_state",
    primaryKeys = ["account", "appId"],
)
data class GameRecoveryState(
    val account: String,
    val appId: Long,
    val baselineEpoch: Long,
    val highWaterMinutes: Long,
    /** Instant of the last usable reading; null when no trustworthy observation exists. */
    val lastObservationAt: Long? = null,
    /** Local ISO date of the last usable reading; null when the zone was never captured. */
    val lastObservationDate: String? = null,
    /** Zone the last usable reading was normalized in; null when unknown. */
    val lastObservationZone: String? = null,
    val clockState: String = RecoveryClockStates.UNKNOWN,
    val updatedAt: Long = 0L,
)

/** Validity/clock classification of a retained recovery boundary. */
object RecoveryClockStates {
    const val VALID = "VALID"
    const val UNKNOWN = "UNKNOWN"
    const val REGRESSION = "REGRESSION"
    const val INVERTED = "INVERTED"
}
