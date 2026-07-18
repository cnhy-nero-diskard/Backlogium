package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.DailyProgressDao
import com.example.backlogium.data.local.dao.PlayerProfileDao
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.PlayerProfile
import com.example.backlogium.work.SyncScheduler
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Profile aggregates, per-day stats, and the manual "Sync now" trigger. */
@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: PlayerProfileDao,
    private val dailyProgressDao: DailyProgressDao,
    private val syncScheduler: SyncScheduler,
) {
    val profile: Flow<PlayerProfile?> = profileDao.observe()
    val dailyProgress: Flow<List<DailyProgress>> = dailyProgressDao.observeAll()

    /** Enqueue an immediate one-time poll. */
    fun syncNow() = syncScheduler.syncNow()
}
