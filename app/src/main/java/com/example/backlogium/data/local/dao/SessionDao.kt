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

    /**
     * Sessions starting at or after [cutoff] (epoch millis), for a date-ranged window rather than
     * a fixed row count — the History screen's day-grouped view (regroup-history) needs every
     * session in a window of calendar days, which a row cap cannot guarantee.
     */
    @Query("SELECT * FROM sessions WHERE startAt >= :cutoff ORDER BY startAt DESC")
    fun observeSince(cutoff: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startAt ASC")
    suspend fun getAll(): List<Session>

    /**
     * Natural-key lookup for the backup/restore merge engine (add-backup-restore): [Session.id]
     * is a surrogate autoincrement that does not survive an export/import round trip, so a
     * merge must find a session by `(appId, startAt, endAt)` instead. `endAt IS :endAt` (not
     * `=`) so a null [endAt] matches other open sessions rather than matching nothing.
     */
    @Query(
        "SELECT * FROM sessions WHERE appId = :appId AND startAt = :startAt AND endAt IS :endAt " +
            "LIMIT 1",
    )
    suspend fun findByNaturalKey(appId: Long, startAt: Long, endAt: Long?): Session?

    /**
     * Tracked minutes summed per game. The gamification engine tapers XP against each game's
     * own completionist length, so it needs the per-`appId` breakdown rather than a single
     * library-wide total.
     */
    @Query("SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions GROUP BY appId")
    suspend fun trackedMinutesByGame(): List<GameTrackedMinutes>

    /**
     * The same per-game breakdown, observed. The Library's XP badge is derived from the engine's
     * own inputs (tracked + backfill minutes), so it has to follow tracked minutes live rather
     * than read them once.
     */
    @Query("SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions GROUP BY appId")
    fun observeTrackedMinutesByGame(): Flow<List<GameTrackedMinutes>>
}

/** Per-game tracked-minutes projection for [SessionDao.trackedMinutesByGame]. */
data class GameTrackedMinutes(val appId: Long, val minutes: Int)
