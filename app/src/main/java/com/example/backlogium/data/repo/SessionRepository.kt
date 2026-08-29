package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.domain.MeaningfulSessionSignals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A synthesized play session as consumers see it. Timestamps are epoch milliseconds; [open] is
 * true while the session is still being extended by successive polls.
 */
data class PlaySession(
    val id: Long,
    val appId: Long,
    val startAt: Long,
    val minutes: Int,
    val open: Boolean,
)

/** Read access to synthesized play sessions. */
@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
) {
    /**
     * Sessions starting at or after [cutoffMillis]. Backs the History screen's day-grouped view
     * (regroup-history), which needs every session in a window of calendar days rather than a
     * fixed row count.
     */
    fun sessionsSince(cutoffMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeSince(cutoffMillis).map { rows -> rows.map(Session::toDomain) }

    /** Sessions whose start timestamps fall inside an explicit start-inclusive/end-exclusive window. */
    fun sessionsBetween(startInclusiveMillis: Long, endExclusiveMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeBetween(startInclusiveMillis, endExclusiveMillis)
            .map { rows -> rows.map(Session::toDomain) }

    /** Earliest tracked session start, or null before the first session is recorded. */
    val earliestSessionStart: Flow<Long?> = sessionDao.observeEarliestSessionStart()

    /** Closed synthesized sessions used by Personal Pace; open sessions are excluded in Room. */
    fun closedSessionsSince(cutoffMillis: Long): Flow<List<PlaySession>> =
        sessionDao.observeClosedSince(cutoffMillis).map { rows -> rows.map(Session::toDomain) }

    /**
     * Tracked minutes summed per game, keyed by appId. Games with no tracked session are absent
     * rather than zero — a caller deriving XP treats a missing entry as zero tracked minutes.
     */
    val trackedMinutesByGame: Flow<Map<Long, Int>> = sessionDao.observeTrackedMinutesByGame()
        .map { rows -> rows.associate { it.appId to it.minutes } }

    /**
     * Each game's earliest recorded session start, keyed by appId. Games with no session are absent
     * rather than zero: "never had a first session" and "first session at the epoch" are different
     * answers, and the recency derivation must not confuse them.
     */
    val firstSessionAtByGame: Flow<Map<Long, Long>> = sessionDao.observeFirstSessionStartByGame()
        .map { rows -> rows.associate { it.appId to it.at } }

    /** Most recent tracked session timestamp per game, absent when no session exists. */
    val latestSessionAtByGame: Flow<Map<Long, Long>> = sessionDao.observeLatestSessionInstantByGame()
        .map { rows -> rows.associate { it.appId to it.at } }

    /** Synthesized session count per game, keyed by appId. */
    val sessionCountByGame: Flow<Map<Long, Int>> = sessionDao.observeSessionCountsByGame()
        .map { rows -> rows.associate { it.appId to it.sessions } }

    /** Meaningful session count, latest meaningful play, and meaningful minutes per game. */
    val meaningfulSessionSignalsByGame: Flow<Map<Long, MeaningfulSessionSignals>> =
        sessionDao.observeMeaningfulSessionSignalsByGame().map { rows ->
            rows.associate { row ->
                row.appId to MeaningfulSessionSignals(
                    meaningfulSessionCount = row.meaningfulSessionCount,
                    lastMeaningfulSessionAt = row.lastMeaningfulSessionAt,
                    meaningfulMinutes = row.meaningfulMinutes,
                )
            }
        }

    /**
     * Tracked minutes summed per game over sessions starting at or after [cutoffMillis], keyed
     * by appId. Feeds the Analytics screen's most-played-games-in-the-window list — distinct
     * from [trackedMinutesByGame] (all-time) the Library's XP badge reads.
     */
    fun minutesByGameSince(cutoffMillis: Long): Flow<Map<Long, Int>> =
        sessionDao.observeMinutesByGameSince(cutoffMillis)
            .map { rows -> rows.associate { it.appId to it.minutes } }

    /** Tracked minutes per game inside an explicit start-inclusive/end-exclusive window. */
    fun minutesByGameBetween(startInclusiveMillis: Long, endExclusiveMillis: Long): Flow<Map<Long, Int>> =
        sessionDao.observeMinutesByGameBetween(startInclusiveMillis, endExclusiveMillis)
            .map { rows -> rows.associate { it.appId to it.minutes } }
}

private fun Session.toDomain() = PlaySession(
    id = id,
    appId = appId,
    startAt = startAt,
    minutes = minutes,
    open = open,
)
