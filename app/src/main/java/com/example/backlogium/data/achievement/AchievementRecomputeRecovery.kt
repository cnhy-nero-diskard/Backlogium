package com.example.backlogium.data.achievement

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.GamificationUpdater
import com.example.backlogium.domain.RecomputeSource
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.SteamSyncCoordinator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementRecomputeRecovery @Inject constructor(
    private val database: BacklogiumDatabase,
    private val settings: SettingsRepository,
    private val updater: GamificationUpdater,
    private val time: TimeProvider,
    private val sync: SteamSyncCoordinator,
    private val writes: DerivedStateWriteCoordinator,
    private val marker: AccountChangeMarkerStore,
) {
    suspend fun prepareStartup() = sync.withLock {
        database.achievementWatchDao().closeAbandonedEpochs()
        resumeWhileLocked()
        database.achievementWatchDao().prune(time.nowMillis() - 30L * 24 * 60 * 60 * 1_000)
    }

    suspend fun resume() = sync.withLock { resumeWhileLocked() }

    /** Called outside Room and outside the non-reentrant progress-transition protocol. */
    suspend fun resumeWhileLocked() = writes.withLock {
        if (marker.pendingSteamId() != null) return@withLock
        val account = database.playerProfileDao().get()?.steamId ?: return@withLock
        for (work in database.achievementWatchDao().needingRecompute()) {
            if (work.account != account) continue
            val rules = settings.readRuleConfigWithVersion()
            updater.recompute(time.today(), RecomputeSource.valueOf(work.recomputeSource), rules.config, rules.version)
            database.achievementWatchDao().finishRecompute(work.eventId)
        }
    }
}
