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
    val recentSessions: Flow<List<PlaySession>> = sessionDao.observeRecent(RECENT_LIMIT)
        .map { rows -> rows.map(Session::toDomain) }

    /**
     * Tracked minutes summed per game, keyed by appId. Games with no tracked session are absent
     * rather than zero — a caller deriving XP treats a missing entry as zero tracked minutes.
     */
    val trackedMinutesByGame: Flow<Map<Long, Int>> = sessionDao.observeTrackedMinutesByGame()
        .map { rows -> rows.associate { it.appId to it.minutes } }

    private companion object {
        const val RECENT_LIMIT = 100
    }
}

private fun Session.toDomain() = PlaySession(
    id = id,
    appId = appId,
    startAt = startAt,
    minutes = minutes,
    open = open,
)
