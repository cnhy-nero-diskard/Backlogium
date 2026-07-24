package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.backlogium.data.local.entity.Session
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM sessions WHERE appId = :appId AND open = 1 LIMIT 1")
    suspend fun getOpenSession(appId: Long): Session?

    @Query("SELECT * FROM sessions ORDER BY startAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startAt ASC")
    suspend fun getAll(): List<Session>

    /**
     * Tracked minutes summed per game. The gamification engine tapers XP against each game's
     * own completionist length, so it needs the per-`appId` breakdown rather than a single
     * library-wide total.
     */
    @Query("SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions GROUP BY appId")
    suspend fun trackedMinutesByGame(): List<GameTrackedMinutes>
}

/** Per-game tracked-minutes projection for [SessionDao.trackedMinutesByGame]. */
data class GameTrackedMinutes(val appId: Long, val minutes: Int)
