package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestRouteTotals
import com.example.backlogium.data.local.entity.RequestTotal
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
    @Query(
        "INSERT INTO request_totals (hourStart, route, status, ok, count) VALUES " +
            "(:hourStart, :route, :status, :ok, :count) " +
            "ON CONFLICT(hourStart, route, status) DO UPDATE SET count = count + excluded.count",
    )
    suspend fun incrementRequestTotal(hourStart: Long, route: String, status: String, ok: Boolean, count: Int)
    @Transaction
    suspend fun incrementRequestTotals(rows: List<RequestTotal>) {
        rows.forEach { row -> incrementRequestTotal(row.hourStart, row.route, row.status, row.ok, row.count) }
    }
    @Query("DELETE FROM request_totals WHERE hourStart < :cutoff") suspend fun pruneRequestTotals(cutoff: Long)
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN ok = 1 THEN count ELSE 0 END), 0) AS ok, " +
            "COALESCE(SUM(CASE WHEN ok = 0 THEN count ELSE 0 END), 0) AS failed " +
            "FROM request_totals WHERE hourStart >= :cutoff",
    )
    fun observeRequestTotals(cutoff: Long): Flow<RequestCounterTotals>
    @Query(
        "SELECT route, " +
            "COALESCE(SUM(CASE WHEN ok = 1 THEN count ELSE 0 END), 0) AS ok, " +
            "COALESCE(SUM(CASE WHEN ok = 0 THEN count ELSE 0 END), 0) AS failed " +
            "FROM request_totals WHERE hourStart >= :cutoff GROUP BY route ORDER BY route",
    )
    fun observeRequestRoutes(cutoff: Long): Flow<List<RequestRouteTotals>>
    @Query("DELETE FROM sync_runs WHERE id NOT IN (SELECT id FROM sync_runs ORDER BY startedAt DESC LIMIT :limit)") suspend fun pruneRuns(limit: Int)
    @Query("DELETE FROM presence_decisions WHERE id NOT IN (SELECT id FROM presence_decisions ORDER BY at DESC LIMIT :limit)") suspend fun prunePresenceDecisions(limit: Int)
    @Query("DELETE FROM request_breakdowns") suspend fun deleteRequestBreakdowns()
    @Query("DELETE FROM sync_runs") suspend fun deleteSyncRuns()
    @Query("DELETE FROM presence_decisions") suspend fun deletePresenceDecisions()
    @Query("DELETE FROM request_totals") suspend fun deleteRequestTotals()
    @Transaction suspend fun insertRun(run: SyncRun, breakdowns: List<RequestBreakdown>) { val runId = insertRun(run); insertBreakdowns(breakdowns.map { it.copy(runId = runId) }) }
    @Transaction
    suspend fun deleteAll() {
        deleteRequestBreakdowns()
        deleteSyncRuns()
        deletePresenceDecisions()
        deleteRequestTotals()
    }
}
