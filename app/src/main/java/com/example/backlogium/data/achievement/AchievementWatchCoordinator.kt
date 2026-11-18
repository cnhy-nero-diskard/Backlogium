package com.example.backlogium.data.achievement

import com.example.backlogium.data.diagnostics.SyncOutcome
import com.example.backlogium.data.diagnostics.SyncRunRecorder
import com.example.backlogium.data.repo.AchievementRepository
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.domain.TimeProvider
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/** Called only at the successful-presence dispatch seam, never from a retained-state collector. */
interface AchievementWatchLifecycle {
    fun monitorStarted()
    fun stop()
    fun beforePresence(account: String?, appId: Long?)
    suspend fun afterPresence(account: String?, appId: Long?)
}

@Singleton
class AchievementWatchCoordinator @Inject constructor(
    private val achievements: AchievementRepository,
    private val settings: SettingsRepository,
    private val credentials: CredentialsProvider,
    private val hidden: HiddenGamesRepository,
    private val network: AchievementNetwork,
    private val database: BacklogiumDatabase,
    private val time: TimeProvider,
    private val diagnostics: SyncRunRecorder,
    @ApplicationScope private val scope: CoroutineScope,
) : AchievementWatchLifecycle {
    private class Generation(val account: String, val appId: Long, val epoch: String = UUID.randomUUID().toString()) {
        var job: Job? = null
        var baseline: Set<String>? = null
    }
    private val monitor = Any()
    @Volatile private var running = false
    @Volatile private var generation: Generation? = null

    init {
        scope.launch {
            combine(settings.achievementWatch, settings.liveMonitorEnabled, hidden.hiddenAppIds) { watch, live, hidden ->
                Triple(watch.enabled, live, hidden)
            }.collect { (enabled, live, hiddenIds) ->
                if (!enabled || !live || generation?.appId in hiddenIds) cancelGeneration()
            }
        }
    }

    override fun monitorStarted() { running = true }
    override fun stop() { running = false; cancelGeneration() }

    private fun cancelGeneration() = synchronized(monitor) {
        val old = generation ?: return@synchronized
        generation = null
        old.job?.cancel()
        scope.launch {
            database.achievementWatchDao().closeEpoch(old.epoch)
            database.achievementWatchDao().prune(time.nowMillis() - 30L * 24 * 60 * 60 * 1_000)
        }
    }

    override fun beforePresence(account: String?, appId: Long?) {
        val old = generation
        if (old != null && (old.account != account || old.appId != appId)) cancelGeneration()
    }

    override suspend fun afterPresence(account: String?, appId: Long?) {
        if (account == null || appId == null || !running) return
        val prefs = settings.achievementWatch.first()
        if (!prefs.enabled || !settings.liveMonitorEnabled.first() || hidden.isHidden(appId) ||
            database.gameDao().getById(appId) == null || credentials.currentCredentials()?.steamId != account) return
        synchronized(monitor) {
            if (!running || generation != null) return@synchronized
            val next = Generation(account, appId)
            generation = next
            next.job = scope.launch { watch(next) }
        }
    }

    private fun current(target: Generation): Boolean = running && generation === target

    private suspend fun watch(target: Generation) {
        try {
            achievementWatchLoop({ current(target) }, { settings.achievementWatch.first().intervalMillis }) {

            val prefs = settings.achievementWatch.first()
            if (!prefs.enabled || !settings.liveMonitorEnabled.first()) { cancelGeneration(); return@achievementWatchLoop AchievementWatchTick(false) }
            val creds = credentials.currentCredentials()
            if (creds?.steamId != target.account || hidden.isHidden(target.appId)) { cancelGeneration(); return@achievementWatchLoop AchievementWatchTick(false) }
            val run = diagnostics.begin("achievement_watch")
            var outcome = "failed"
            var success = false
            var deadline = 0L
            try {
                if (!network.isOnline()) {
                    outcome = "offline"
                } else {
                    val prepared = achievements.prepareWatch(creds.apiKey, target.account, target.appId, run)
                    deadline = prepared?.retryAt ?: 0L
                    if (deadline > time.nowMillis()) {
                        outcome = "throttled"
                    } else if (prepared != null) {
                        when (val committed = achievements.commitWatch(target.account, target.epoch, prepared, target.baseline) { current(target) }) {
                            is AchievementRepository.WatchCommit.Committed -> {
                                target.baseline = committed.unlocked
                                outcome = if (committed.changedXp || committed.eventId != null) "success" else "unchanged"
                                success = true
                            }
                            AchievementRepository.WatchCommit.DeferredRarity -> outcome = "deferred_rarity"
                            AchievementRepository.WatchCommit.Stale -> outcome = "cancelled"
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                outcome = "cancelled"
                throw cancelled
            } catch (_: Exception) {
                outcome = "failed"
            } finally {
                withContext(NonCancellable) {
                    runCatching { diagnostics.finish(run, if (success) SyncOutcome.SUCCESS else SyncOutcome.INCOMPLETE,
                        "appId=${target.appId};outcome=$outcome", 1, if (success) 1 else 0) }
                }
            }
            AchievementWatchTick(success, outcome == "offline", (deadline - time.nowMillis()).coerceAtLeast(0))
            }
        } finally {
            synchronized(monitor) { if (generation === target) cancelGeneration() }
        }
    }
}
