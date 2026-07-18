package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-day play totals keyed by local calendar date (ISO-8601 "yyyy-MM-dd"). [questMet]
 * is recomputed by the gamification engine on each sync and on day rollover.
 */
@Entity(tableName = "daily_progress")
data class DailyProgress(
    @PrimaryKey val date: String,
    val minutesPlayed: Int = 0,
    val goalMinutesPlayed: Int = 0,
    val questMet: Boolean = false,
)
