package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.backlogium.data.local.entity.AchievementWatchWork
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementWatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(work: AchievementWatchWork): Long

    @Query("SELECT * FROM achievement_watch_work WHERE eventId = :eventId")
    suspend fun get(eventId: String): AchievementWatchWork?

    @Query("SELECT * FROM achievement_watch_work WHERE account = :account ORDER BY observedAt, eventId")
    suspend fun getAll(account: String): List<AchievementWatchWork>

    @Query("SELECT * FROM achievement_watch_work WHERE payload IS NOT NULL AND deliveryState IN ('pending', 'background') ORDER BY observedAt, eventId")
    fun observePending(): Flow<List<AchievementWatchWork>>

    @Query("SELECT * FROM achievement_watch_work WHERE recomputeNeeded = 1 ORDER BY observedAt, eventId")
    suspend fun needingRecompute(): List<AchievementWatchWork>

    @Query("UPDATE achievement_watch_work SET recomputeNeeded = 0 WHERE eventId = :eventId")
    suspend fun finishRecompute(eventId: String)

    /** Compare-and-set: a foreground claim is terminal before the UI renders. */
    @Query("UPDATE achievement_watch_work SET deliveryState = :surface WHERE eventId = :eventId AND deliveryState = 'pending'")
    suspend fun claim(eventId: String, surface: String): Int

    @Query("UPDATE achievement_watch_work SET deliveryState = :state WHERE eventId = :eventId AND deliveryState IN ('pending', 'background')")
    suspend fun finishDelivery(eventId: String, state: String)

    @Query("UPDATE achievement_watch_work SET epochClosed = 1 WHERE epoch = :epoch")
    suspend fun closeEpoch(epoch: String)

    @Query("DELETE FROM achievement_watch_work WHERE epochClosed = 1 AND recomputeNeeded = 0 AND observedAt < :before AND (payload IS NULL OR deliveryState IN ('foreground', 'delivered', 'suppressed'))")
    suspend fun prune(before: Long)

    @Query("DELETE FROM achievement_watch_work")
    suspend fun deleteAll()
}
