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

    /** Single achievement lookup for the backup/restore merge engine (add-backup-restore). */
    @Query("SELECT * FROM achievements WHERE appId = :appId AND apiName = :apiName LIMIT 1")
    suspend fun getOne(appId: Long, apiName: String): Achievement?

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

    /**
     * Rarity snapshot of every unlocked achievement, per game — the Library XP badge's
     * achievement input. Distinct from [observeCounts], which gives unlocked/total counts the
     * engine's `achievementXp` cannot tier from: XP depends on each achievement's own rarity,
     * so the percents themselves have to cross the boundary.
     */
    @Query(
        "SELECT appId, apiName, displayName, snapshotPercent FROM achievements " +
            "WHERE apiName != '$NO_ACHIEVEMENTS_MARKER' AND unlocked = 1",
    )
    fun observeUnlockedRarity(): Flow<List<AchievementRarity>>

    /**
     * Achievements unlocked at or after [cutoff] (epoch millis), across every game — feeds the
     * History screen's per-day thumbnail row (regroup-history). Deliberately not scoped to a
     * game played that day: an achievement can unlock retroactively or from idle progress, so the
     * caller joins these to a day by [Achievement.unlockedAt]'s local date, not by [Achievement.appId].
     */
    @Query(
        "SELECT appId, iconUrl, unlockedAt FROM achievements " +
            "WHERE apiName != '$NO_ACHIEVEMENTS_MARKER' AND unlocked = 1 AND unlockedAt >= :cutoff " +
            "ORDER BY unlockedAt ASC",
    )
    fun observeUnlockedSince(cutoff: Long): Flow<List<AchievementUnlock>>
}

data class AchievementCounts(val appId: Long, val total: Int, val unlocked: Int)
data class AchievementFetchedAt(val appId: Long, val fetchedAt: Long)

/** One unlock event for the History screen's day thumbnail row. */
data class AchievementUnlock(val appId: Long, val iconUrl: String?, val unlockedAt: Long)

/**
 * One unlocked achievement's frozen rarity snapshot. A null [snapshotPercent] is un-tierable
 * (Steam reported no global stat) and worth zero XP — preserved rather than filtered out so the
 * count of unlocked achievements stays honest.
 */
data class AchievementRarity(
    val appId: Long,
    val snapshotPercent: Double?,
    val apiName: String = "",
    val displayName: String? = null,
)
