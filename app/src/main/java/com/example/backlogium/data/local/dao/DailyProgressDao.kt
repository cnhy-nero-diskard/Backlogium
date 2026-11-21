package com.example.backlogium.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.backlogium.data.local.entity.DailyProgress
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyProgressDao {

    @Upsert
    suspend fun upsert(day: DailyProgress)

    /** Ensure the date exists without replacing a concurrent quest result. */
    @Query(
        "INSERT OR IGNORE INTO daily_progress " +
            "(date, minutesPlayed, goalMinutesPlayed, questMet) VALUES (:date, 0, 0, 0)",
    )
    suspend fun ensureDate(date: String)

    /** Add session-start-attributed minutes atomically; never read-add-write a daily total. */
    @Query(
        "UPDATE daily_progress SET minutesPlayed = minutesPlayed + :minutesPlayed, " +
            "goalMinutesPlayed = goalMinutesPlayed + :goalMinutesPlayed WHERE date = :date",
    )
    suspend fun addMinutes(date: String, minutesPlayed: Int, goalMinutesPlayed: Int)

    /**
     * Set a day's totals absolutely, replacing whatever is stored.
     *
     * Deliberately separate from [addMinutes], which is the only correct write for a poll — the
     * additive update exists so concurrent polls cannot lose minutes. This one serves the one-time
     * correction that rebuilds a day from the session ledger (auditfix-day-attribution Decision 7),
     * where replacing the stored value *is* the point. It must not be used on a sync path.
     */
    @Query(
        "UPDATE daily_progress SET minutesPlayed = :minutesPlayed, " +
            "goalMinutesPlayed = :goalMinutesPlayed WHERE date = :date",
    )
    suspend fun setMinutes(date: String, minutesPlayed: Int, goalMinutesPlayed: Int)

    /**
     * Apply a *signed* contribution delta to one date's totals, for a canonical placement
     * correction that moves minutes off an old date and onto a new one.
     *
     * Unlike [setMinutes], which replaces a date's whole total, this only applies the difference.
     * That is what keeps a day's unrelated legacy or manual remainder intact: the recovery ledger
     * is authoritative for the shares it owns, not for every minute the row happens to hold.
     * Underflow is deliberately **not** clamped: the caller validates the target total inside the
     * raw transaction and rejects (throws) on a negative result, so an inconsistent quantity graph
     * surfaces instead of being silently erased into a zero.
     */
    @Query(
        "UPDATE daily_progress SET minutesPlayed = minutesPlayed + :minutesDelta, " +
            "goalMinutesPlayed = goalMinutesPlayed + :goalDelta WHERE date = :date",
    )
    suspend fun applyMinutesDelta(date: String, minutesDelta: Int, goalDelta: Int)

    /** Gamification owns only the derived quest flag; raw playtime remains sync-owned. */
    @Query("UPDATE daily_progress SET questMet = :questMet WHERE date = :date")
    suspend fun updateQuestMet(date: String, questMet: Boolean)

    @Query("SELECT * FROM daily_progress WHERE date = :date")
    suspend fun getByDate(date: String): DailyProgress?

    @Query("SELECT * FROM daily_progress ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyProgress>>

    @Query("SELECT * FROM daily_progress ORDER BY date ASC")
    suspend fun getAllOrdered(): List<DailyProgress>

    @Query("DELETE FROM daily_progress")
    suspend fun deleteAll()

    @Query("DELETE FROM daily_progress WHERE date = :date")
    suspend fun deleteByDate(date: String)
}
