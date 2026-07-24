package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.NO_ACHIEVEMENTS_MARKER
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementDao {

    @Upsert
    suspend fun upsertAll(achievements: List<Achievement>)

    /** Real achievements for a game (the [NO_ACHIEVEMENTS_MARKER] sentinel is never surfaced). */
    @Query(
        "SELECT * FROM achievements WHERE appId = :appId AND apiName != '$NO_ACHIEVEMENTS_MARKER' " +
            "ORDER BY unlocked DESC, apiName ASC",
    )
    fun observeForGame(appId: Long): Flow<List<Achievement>>

    @Query(
        "SELECT * FROM achievements WHERE appId = :appId AND apiName != '$NO_ACHIEVEMENTS_MARKER'",
    )
    suspend fun getForGame(appId: Long): List<Achievement>

    /** Unlocked/total achievement counts per game, for the Library row badge. */
    @Query(
        "SELECT appId, COUNT(*) AS total, SUM(CASE WHEN unlocked THEN 1 ELSE 0 END) AS unlocked " +
            "FROM achievements WHERE apiName != '$NO_ACHIEVEMENTS_MARKER' GROUP BY appId",
    )
    fun observeCounts(): Flow<List<AchievementCounts>>

    /**
     * Latest fetch time per game, including games recorded as having no achievements — both
     * count as "checked" for the freshness gate.
     */
    @Query("SELECT appId, MAX(fetchedAt) AS fetchedAt FROM achievements GROUP BY appId")
    suspend fun fetchedAtByApp(): List<AchievementFetchedAt>

    /** Drops the "no achievements" sentinel once a game is found to have real achievements. */
    @Query(
        "DELETE FROM achievements WHERE appId = :appId AND apiName = '$NO_ACHIEVEMENTS_MARKER'",
    )
    suspend fun deleteMarker(appId: Long)

    /** All unlocked achievements with a rarity snapshot, across every game — feeds the engine. */
    @Query(
        "SELECT * FROM achievements WHERE apiName != '$NO_ACHIEVEMENTS_MARKER' AND unlocked = 1",
    )
    suspend fun getAllUnlocked(): List<Achievement>
}

data class AchievementCounts(val appId: Long, val total: Int, val unlocked: Int)
data class AchievementFetchedAt(val appId: Long, val fetchedAt: Long)
