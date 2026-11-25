package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.backlogium.data.local.entity.AchievementAdmissionIdentity
import com.example.backlogium.data.local.entity.AchievementAdmissionQueueState
import com.example.backlogium.data.local.entity.AchievementAdmissionWork

@Dao
interface AchievementAdmissionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(work: AchievementAdmissionWork): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(work: List<AchievementAdmissionWork>)

    @Query(
        "SELECT * FROM achievement_admission_work " +
            "WHERE steamId = :steamId AND accountGeneration = :generation AND nextEligibleAt <= :now " +
            "ORDER BY admittedAt, appId LIMIT :limit",
    )
    suspend fun eligible(
        steamId: String,
        generation: Long,
        now: Long,
        limit: Int,
    ): List<AchievementAdmissionWork>

    @Query(
        "SELECT * FROM achievement_admission_work " +
            "WHERE steamId = :steamId AND accountGeneration = :generation AND appId = :appId",
    )
    suspend fun get(steamId: String, generation: Long, appId: Long): AchievementAdmissionWork?

    @Query(
        "UPDATE achievement_admission_work SET attempts = attempts + 1 " +
            "WHERE steamId = :steamId AND accountGeneration = :generation AND appId = :appId " +
            "AND nextEligibleAt <= :now",
    )
    suspend fun beginAttempt(steamId: String, generation: Long, appId: Long, now: Long): Int

    @Query(
        "UPDATE achievement_admission_work SET nextEligibleAt = :nextEligibleAt " +
            "WHERE steamId = :steamId AND accountGeneration = :generation AND appId = :appId",
    )
    suspend fun defer(steamId: String, generation: Long, appId: Long, nextEligibleAt: Long)

    @Query(
        "DELETE FROM achievement_admission_work " +
            "WHERE steamId = :steamId AND accountGeneration = :generation AND appId = :appId",
    )
    suspend fun delete(steamId: String, generation: Long, appId: Long)

    @Query(
        "SELECT COUNT(*) AS pendingCount, MIN(nextEligibleAt) AS nextEligibleAt " +
            "FROM achievement_admission_work WHERE steamId = :steamId AND accountGeneration = :generation",
    )
    suspend fun state(steamId: String, generation: Long): AchievementAdmissionQueueState

    @Query("SELECT DISTINCT steamId, accountGeneration FROM achievement_admission_work")
    suspend fun pendingIdentities(): List<AchievementAdmissionIdentity>

    @Query("DELETE FROM achievement_admission_work")
    suspend fun deleteAll()
}
