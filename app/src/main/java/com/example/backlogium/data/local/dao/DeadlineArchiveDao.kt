package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.backlogium.data.local.entity.DailyPlaytimeSnapshot
import com.example.backlogium.data.local.entity.DeadlineArchiveRow
import com.example.backlogium.data.local.entity.DeadlineRevision
import com.example.backlogium.data.local.entity.DeadlineRevisionBatch
import kotlinx.coroutines.flow.Flow

@Dao
interface DeadlineArchiveDao {
    @Query("SELECT * FROM deadline_revision_batch WHERE id = 0")
    suspend fun activeBatch(): DeadlineRevisionBatch?

    @Insert
    suspend fun beginBatch(batch: DeadlineRevisionBatch)

    @Query("UPDATE deadline_revision_batch SET depth = depth + 1 WHERE id = 0")
    suspend fun nestBatch()

    @Query("UPDATE deadline_revision_batch SET depth = depth - 1 WHERE id = 0")
    suspend fun leaveBatch()

    @Query("UPDATE deadline_revisions SET effectiveAt = :completedAt, batchCompletedAt = :completedAt WHERE batchId = :batchId")
    suspend fun completeBatch(batchId: String, completedAt: Long)

    @Query("DELETE FROM deadline_revision_batch WHERE id = 0")
    suspend fun clearBatch()

    @Query("SELECT * FROM deadline_revisions WHERE collectionId = :id ORDER BY effectiveAt, id")
    suspend fun revisions(id: Long): List<DeadlineRevision>

    @Query("""
        SELECT EXISTS(SELECT 1 FROM deadline_revisions
            WHERE collectionId = :id AND batchStartedAt IS NOT NULL AND batchCompletedAt IS NOT NULL
              AND MIN(batchStartedAt, batchCompletedAt) < :cutoff
              AND MAX(batchStartedAt, batchCompletedAt) >= :cutoff)
    """)
    suspend fun hasAmbiguousBatch(id: Long, cutoff: Long): Boolean

    @Query("SELECT * FROM deadline_archives")
    fun observeArchives(): Flow<List<DeadlineArchiveRow>>

    @Query("SELECT * FROM deadline_archives")
    suspend fun archives(): List<DeadlineArchiveRow>

    @Query("SELECT * FROM deadline_archives WHERE originId = :originId AND createdAt = :createdAt")
    suspend fun archive(originId: Long, createdAt: Long): DeadlineArchiveRow?

    @Insert
    suspend fun insert(row: DeadlineArchiveRow)

    @Query("DELETE FROM deadline_archives")
    suspend fun deleteAll()

    @Query("""
        SELECT * FROM playtime_daily_snapshots
        WHERE account = (SELECT steamId FROM player_profile WHERE id = 0)
        AND appId = :appId AND observedAt < :cutoff
        ORDER BY observedAt DESC LIMIT 1
    """)
    suspend fun before(appId: Long, cutoff: Long): DailyPlaytimeSnapshot?

    @Query("""
        SELECT * FROM playtime_daily_snapshots
        WHERE account = (SELECT steamId FROM player_profile WHERE id = 0)
        AND appId = :appId AND observedAt >= :cutoff AND observedAt <= :now
        ORDER BY observedAt LIMIT 1
    """)
    suspend fun after(appId: Long, cutoff: Long, now: Long): DailyPlaytimeSnapshot?

    @Query("""
        SELECT COUNT(*) FROM sessions WHERE appId = :appId AND minutes > 0
        AND open = 0 AND startAt < :cutoff AND endAt <= :cutoff
    """)
    suspend fun recordedSessions(appId: Long, cutoff: Long): Int

    @Query("""
        SELECT COUNT(*) FROM achievements WHERE appId = :appId AND unlocked = 1
        AND unlockedAt > 0 AND unlockedAt < :cutoff
    """)
    suspend fun recordedTrophies(appId: Long, cutoff: Long): Int

    @Query("""
        SELECT EXISTS(SELECT 1 FROM game_achievement_sync
            WHERE appId = :appId AND completedAt < :cutoff)
        AND NOT EXISTS(SELECT 1 FROM achievements WHERE appId = :appId AND unlocked = 1
            AND (unlockedAt IS NULL OR unlockedAt <= 0))
    """)
    suspend fun hasDatedTrophyCoverage(appId: Long, cutoff: Long): Boolean
}
