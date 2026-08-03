package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.SyncRun
import kotlinx.coroutines.flow.Flow

@Dao
interface DiagnosticsDao {
    @Upsert suspend fun insertRun(run: SyncRun): Long
    @Upsert suspend fun insertBreakdowns(rows: List<RequestBreakdown>)
    @Upsert suspend fun insertPresenceDecision(decision: PresenceDecision)
    @Query("SELECT * FROM sync_runs ORDER BY startedAt DESC") fun observeRuns(): Flow<List<SyncRun>>
    @Query("SELECT * FROM sync_runs WHERE id = :runId") fun observeRun(runId: Long): Flow<SyncRun?>
    @Query("SELECT * FROM request_breakdowns WHERE runId = :runId ORDER BY durationMs DESC") fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>>
    @Query("SELECT * FROM presence_decisions ORDER BY at DESC") fun observePresenceDecisions(): Flow<List<PresenceDecision>>
    @Query("DELETE FROM sync_runs WHERE id NOT IN (SELECT id FROM sync_runs ORDER BY startedAt DESC LIMIT :limit)") suspend fun pruneRuns(limit: Int)
    @Query("DELETE FROM presence_decisions WHERE id NOT IN (SELECT id FROM presence_decisions ORDER BY at DESC LIMIT :limit)") suspend fun prunePresenceDecisions(limit: Int)
    @Transaction suspend fun insertRun(run: SyncRun, breakdowns: List<RequestBreakdown>) { val runId = insertRun(run); insertBreakdowns(breakdowns.map { it.copy(runId = runId) }) }
}
