package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.SessionDao
import com.example.backlogium.data.local.entity.Session
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Read access to synthesized play sessions. */
@Singleton
class SessionRepository @Inject constructor(
    private val sessionDao: SessionDao,
) {
    val recentSessions: Flow<List<Session>> = sessionDao.observeRecent(RECENT_LIMIT)

    private companion object {
        const val RECENT_LIMIT = 100
    }
}
