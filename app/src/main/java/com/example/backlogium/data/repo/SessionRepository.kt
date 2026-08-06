package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
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

    /**
     * Tracked minutes summed per game, keyed by appId. Games with no tracked session are absent
     * rather than zero — a caller deriving XP treats a missing entry as zero tracked minutes.
     */
    val trackedMinutesByGame: Flow<Map<Long, Int>> = sessionDao.observeTrackedMinutesByGame()
        .map { rows -> rows.associate { it.appId to it.minutes } }

    /** Synthesized session count per game, keyed by appId. */
    val sessionCountByGame: Flow<Map<Long, Int>> = sessionDao.observeSessionCountsByGame()
        .map { rows -> rows.associate { it.appId to it.sessions } }

    /**
     * Tracked minutes summed per game over sessions starting at or after [cutoffMillis], keyed
     * by appId. Feeds the Analytics screen's most-played-games-in-the-window list — distinct
     * from [trackedMinutesByGame] (all-time) the Library's XP badge reads.
     */
    fun minutesByGameSince(cutoffMillis: Long): Flow<Map<Long, Int>> =
        sessionDao.observeMinutesByGameSince(cutoffMillis)
            .map { rows -> rows.associate { it.appId to it.minutes } }
}

private fun Session.toDomain() = PlaySession(
    id = id,
    appId = appId,
    startAt = startAt,
    minutes = minutes,
    open = open,
)
