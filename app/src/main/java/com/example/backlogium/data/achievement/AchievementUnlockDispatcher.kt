package com.example.backlogium.data.achievement

import com.example.backlogium.data.local.dao.AchievementWatchDao
import com.example.backlogium.data.local.entity.AchievementWatchWork
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.HiddenGamesRepository
import com.example.backlogium.data.repo.ProgressEventRepository
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.domain.AchievementUnlockGroup
import com.example.backlogium.domain.ProgressEvent
import com.example.backlogium.domain.priority
import com.example.backlogium.work.ActivityVisibilityTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AchievementUnlockDispatcher @Inject constructor(
    private val dao: AchievementWatchDao,
    private val store: AchievementObservationStore,
    private val settings: SettingsRepository,
    private val hidden: HiddenGamesRepository,
    private val credentials: CredentialsRepository,
    private val progress: ProgressEventRepository,
    private val visibility: ActivityVisibilityTracker,
    private val notifier: AchievementUnlockNotifier,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val presentations = Channel<AchievementUnlockGroup>(Channel.UNLIMITED)
    val foregroundGroups = presentations.receiveAsFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(dao.observePending(), settings.achievementWatch, hidden.hiddenAppIds, credentials.steamIdFlow, progress.pendingEvents) {
                work, _, _, _, events -> work to events
            }.collectLatest { (work, events) ->
                for (item in work) {
                    val foreground = visibility.hasResumedActivity
                    if (foreground && events.any { it.priority < 2 }) continue
                    var failures = 0
                    while (!dispatch(item, foreground)) delay(achievementRetryDelay(failures++))
                }
            }
        }
    }

    suspend fun canOpen(account: String, appId: Long): Boolean =
        store.accountReady(account) && store.database.playerProfileDao().get()?.steamId == account &&
            !hidden.isHidden(appId) && store.database.gameDao().getById(appId) != null

    suspend fun allowed(group: AchievementUnlockGroup): Boolean =
        settings.achievementWatch.first().alertsEnabled && canOpen(group.account, group.appId)

    /** True means terminal or no longer pending; false leaves the same background identity retryable. */
    internal suspend fun dispatch(item: AchievementWatchWork, foreground: Boolean): Boolean {
        val row = dao.get(item.eventId) ?: return true
        if (row.deliveryState != AchievementWatchWork.PENDING && row.deliveryState != AchievementWatchWork.BACKGROUND) return true
        val group = row.payload?.let { runCatching { Json.decodeFromString<AchievementUnlockGroup>(it) }.getOrNull() }
        if (group == null || group.eventId != row.eventId || group.account != row.account || group.appId != row.appId || !allowed(group)) {
            dao.finishDelivery(row.eventId, AchievementWatchWork.SUPPRESSED)
            return true
        }
        return withContext(NonCancellable) {
            val chosen = store.commit(group.account, group.appId, { true }) {
                val latest = dao.get(row.eventId) ?: return@commit null
                if (!settings.achievementWatch.first().alertsEnabled) {
                    dao.finishDelivery(row.eventId, AchievementWatchWork.SUPPRESSED)
                    null
                } else if (latest.deliveryState == AchievementWatchWork.BACKGROUND) {
                    AchievementWatchWork.BACKGROUND
                } else if (latest.deliveryState == AchievementWatchWork.PENDING) {
                    val surface = if (foreground) AchievementWatchWork.FOREGROUND else AchievementWatchWork.BACKGROUND
                    if (dao.claim(row.eventId, surface) == 1) surface else null
                } else null
            }
            // The choice is committed before touching either external presentation surface.
            when (chosen) {
                AchievementWatchWork.FOREGROUND -> { if (allowed(group)) presentations.send(group); true }
                AchievementWatchWork.BACKGROUND -> {
                    if (!allowed(group)) { dao.finishDelivery(row.eventId, AchievementWatchWork.SUPPRESSED); true }
                    else when (notifier.post(group)) {
                        AchievementUnlockNotifier.Result.POSTED -> { dao.finishDelivery(row.eventId, AchievementWatchWork.DELIVERED); true }
                        AchievementUnlockNotifier.Result.SUPPRESSED -> { dao.finishDelivery(row.eventId, AchievementWatchWork.SUPPRESSED); true }
                        AchievementUnlockNotifier.Result.RETRY -> false
                    }
                }
                else -> { dao.finishDelivery(row.eventId, AchievementWatchWork.SUPPRESSED); true }
            }
        }
    }
}
