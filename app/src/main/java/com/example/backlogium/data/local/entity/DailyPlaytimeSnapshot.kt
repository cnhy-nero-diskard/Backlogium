package com.example.backlogium.data.local.entity

import androidx.room.Entity

/**
 * One successful absolute Steam reading for a game on a local date, retained independently of the
 * quantity high-water mark (recover-missed-playtime). A regression below the high-water is still a
 * truthful observation and is stored here — [classification] records that it did not advance the
 * usable boundary, so a later diagnostic can see it without it ever becoming credited play.
 *
 * Keyed by `(account, appId, date)`: same-day successful reads replace the day's snapshot rather
 * than creating a second quantity detector.
 */
@Entity(
    tableName = "playtime_daily_snapshots",
    primaryKeys = ["account", "appId", "date"],
)
data class DailyPlaytimeSnapshot(
    val account: String,
    val appId: Long,
    /** Local ISO calendar date (`yyyy-MM-dd`) in [zone]. */
    val date: String,
    /** Zone the instant was converted into; never renormalized by a later timezone change. */
    val zone: String,
    /** The raw absolute cumulative minutes, exactly as Steam reported them. */
    val absoluteMinutes: Long,
    /** When the reading was observed (epoch millis). */
    val observedAt: Long,
    val classification: String = RecoverySnapshotClassifications.TRUSTED,
)

/** Whether an absolute reading advanced the usable quantity boundary or was a regression. */
object RecoverySnapshotClassifications {
    const val TRUSTED = "TRUSTED"
    const val REGRESSION = "REGRESSION"
}
