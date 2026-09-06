package com.example.backlogium.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sync_runs", indices = [Index("startedAt")])
data class SyncRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val durationMs: Long,
    val trigger: String,
    val requestCount: Int,
    val requestMillis: Long,
    val gamesExamined: Int,
    val gamesUpdated: Int,
    val outcome: String,
    val errorMessage: String?,
    val hotCount: Int = 0,
    val warmCount: Int = 0,
    val coldCount: Int = 0,
    val neverCount: Int = 0,
    /** Session boundaries clamped this run for a backward clock movement (auditfix-session-ledger-integrity, #115). */
    val clockRollbackCount: Int = 0,
)

@Entity(
    tableName = "game_achievement_sync",
    foreignKeys = [ForeignKey(entity = Game::class, parentColumns = ["appId"], childColumns = ["appId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("appId"), Index("playerStateFetchedAt"), Index("schemaFetchedAt")],
)
data class GameAchievementSync(
    @PrimaryKey val appId: Long,
    val playerStateFetchedAt: Long?,
    val schemaFetchedAt: Long?,
    val hasAchievements: Boolean?,
    val checkedAt: Long,
)

@Entity(tableName = "request_breakdowns", foreignKeys = [ForeignKey(entity = SyncRun::class, parentColumns = ["id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)], indices = [Index("runId")])
data class RequestBreakdown(@PrimaryKey(autoGenerate = true) val id: Long = 0, val runId: Long, val endpoint: String, val status: Int?, val requestCount: Int, val durationMs: Long)

@Entity(tableName = "request_totals", primaryKeys = ["hourStart", "route", "status"])
data class RequestTotal(
    val hourStart: Long,
    val route: String,
    val status: String,
    val ok: Boolean,
    val count: Int,
)

data class RequestCounterTotals(
    val ok: Long = 0,
    val failed: Long = 0,
)

data class RequestRouteTotals(
    val route: String,
    val ok: Long,
    val failed: Long,
)

@Entity(tableName = "presence_decisions", indices = [Index("at")])
data class PresenceDecision(@PrimaryKey(autoGenerate = true) val id: Long = 0, val at: Long, val trigger: String, val outcome: String, val appId: Long?, val retainedPriorState: Boolean)
