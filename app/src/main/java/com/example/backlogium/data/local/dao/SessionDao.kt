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
     * All currently-open sessions, in a single query. Used when reconstructing per-game diff
     * state for a whole-library poll; associating by `appId` in memory avoids an N+1 query
     * against the session table (optimize-steam-sync).
     */
    @Query("SELECT * FROM sessions WHERE open = 1")
    suspend fun getAllOpenSessions(): List<Session>

    /**
     * Sessions starting at or after [cutoff] (epoch millis), for a date-ranged window rather than
     * a fixed row count — the History screen's day-grouped view (regroup-history) needs every
     * session in a window of calendar days, which a row cap cannot guarantee.
     */
    @Query("SELECT * FROM sessions WHERE startAt >= :cutoff ORDER BY startAt DESC")
    fun observeSince(cutoff: Long): Flow<List<Session>>

    /** Sessions whose start date falls within a complete local-day window. */
    @Query(
        "SELECT * FROM sessions WHERE startAt >= :startInclusive AND startAt < :endExclusive " +
            "ORDER BY startAt DESC",
    )
    fun observeBetween(startInclusive: Long, endExclusive: Long): Flow<List<Session>>

    /** Closed synthesized sessions starting at or after [cutoff], for Personal Pace training. */
    @Query(
        "SELECT * FROM sessions WHERE startAt >= :cutoff AND open = 0 ORDER BY startAt DESC",
    )
    fun observeClosedSince(cutoff: Long): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY startAt ASC")
    suspend fun getAll(): List<Session>

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** Earliest tracked session start, used to keep Analytics anchors inside available history. */
    @Query("SELECT MIN(startAt) FROM sessions")
    fun observeEarliestSessionStart(): Flow<Long?>

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

    /** Number of synthesized sessions per game, used by collection overviews. */
    @Query("SELECT appId, COUNT(*) AS sessions FROM sessions GROUP BY appId")
    fun observeSessionCountsByGame(): Flow<List<GameSessionCounts>>

    /**
     * Tracked minutes summed per game over sessions starting at or after [cutoff] (epoch millis).
     * Feeds the Analytics screen's most-played-games-in-the-window list, which is distinct from
     * the all-time [observeTrackedMinutesByGame] the Library's XP badge uses.
     */
    @Query(
        "SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions " +
            "WHERE startAt >= :cutoff GROUP BY appId",
    )
    fun observeMinutesByGameSince(cutoff: Long): Flow<List<GameTrackedMinutes>>

    /** Tracked minutes summed per game inside an explicit start-inclusive/end-exclusive window. */
    @Query(
        "SELECT appId, COALESCE(SUM(minutes), 0) AS minutes FROM sessions " +
            "WHERE startAt >= :startInclusive AND startAt < :endExclusive GROUP BY appId",
    )
    fun observeMinutesByGameBetween(startInclusive: Long, endExclusive: Long): Flow<List<GameTrackedMinutes>>

    /**
     * Each game's earliest recorded session start — the input that makes the newly-played recency
     * state fire once per game, for life (add-library-recency-signals).
     *
     * One grouped query for the whole library rather than a lookup per row: the Library derives a
     * state for every game it renders, so a per-game query would turn one scan into hundreds.
     */
    @Query("SELECT appId, MIN(startAt) AS at FROM sessions GROUP BY appId")
    fun observeFirstSessionStartByGame(): Flow<List<GameSessionInstant>>

    /**
     * Each game's most recent recorded play, observed. Feeds source-aware detail recency and the
     * derived collections' idle test, where it stands in for Steam's own last-played stamp on a
     * game Steam reports none for.
     */
    @Query("SELECT appId, MAX(COALESCE(endAt, startAt)) AS at FROM sessions GROUP BY appId")
    fun observeLatestSessionInstantByGame(): Flow<List<GameSessionInstant>>

    /**
     * The same most recent recorded play, as `endAt` where the session has one and `startAt` where
     * it is still open.
     *
     * Read by a poll *before* it overwrites `lastPlayedAt`, as one half of the previously-known
     * last-play time the dormancy evaluation compares against. Grouped for the same reason as
     * above: a poll examines the whole library.
     */
    @Query("SELECT appId, MAX(COALESCE(endAt, startAt)) AS at FROM sessions GROUP BY appId")
    suspend fun latestSessionInstantByGame(): List<GameSessionInstant>
}

/** Per-game tracked-minutes projection for [SessionDao.trackedMinutesByGame]. */
data class GameTrackedMinutes(val appId: Long, val minutes: Int)

/**
 * Per-game single-instant projection: one timestamp per game, whose meaning is the query's
 * ([SessionDao.observeFirstSessionStartByGame] the earliest start,
 * [SessionDao.latestSessionInstantByGame] the most recent play).
 */
data class GameSessionInstant(val appId: Long, val at: Long)

/** Per-game session-count projection for collection overview metrics. */
data class GameSessionCounts(val appId: Long, val sessions: Int)
