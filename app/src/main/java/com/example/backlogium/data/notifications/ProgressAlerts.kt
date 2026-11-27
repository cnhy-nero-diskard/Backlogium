package com.example.backlogium.data.notifications

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.work.SteamSyncCoordinator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class ProgressAlerts @Inject constructor(
    private val database: BacklogiumDatabase,
    private val settings: SettingsDataStore,
    private val platform: ProgressAlertPlatform,
    private val marker: AccountChangeMarkerStore,
    private val credentials: CredentialsProvider,
    private val coordinator: SteamSyncCoordinator,
    private val time: com.example.backlogium.domain.TimeProvider = com.example.backlogium.domain.SystemTimeProvider(),
) {
    suspend fun enabled(category: ProgressCategory) = settings.progressAlertPreferences.first()[category] == true

    suspend fun prepare(
        category: ProgressCategory, account: String, generation: Long, sourceId: String,
        appId: Long? = null, title: String, text: String,
    ): ProgressAlert {
        val allowed = enabled(category) && platform.permissionGranted() && platform.channelAllowed(category) != false
        return ProgressAlert("$account:$generation:${category.name}:$sourceId", account, generation,
            category.name, sourceId, appId, title, text,
            if (allowed) ProgressAlert.PENDING else ProgressAlert.SUPPRESSED)
    }

    /** Called inside the producer's Room transaction, with #159's exact account/game identity. */
    suspend fun admission(account: String, generation: Long, appId: Long, name: String, shared: Boolean) {
        val category = if (shared) ProgressCategory.SHARED else ProgressCategory.OWNED
        database.progressAlertDao().insert(prepare(category, account, generation, appId.toString(), appId,
            if (shared) "Now tracking $name" else "Added $name",
            if (shared) "Played through Family Sharing. Tracked time is what Backlogium observes." else "New in your Steam library.").copy(sharedFallbackPending = shared))
    }

    suspend fun completion(account: String, appId: Long, name: String) {
        database.progressAlertDao().insert(prepare(ProgressCategory.COMPLETION, account, marker.generation(),
            appId.toString(), appId, "Completed $name", "All confirmed achievements unlocked."))
    }

    /** Android eligibility is captured before finalization; category opt-in is read in its DataStore edit. */
    suspend fun earnedBoundary(): (com.example.backlogium.domain.ProgressMarks, com.example.backlogium.domain.ProgressMarks, Set<ProgressCategory>) -> Set<String> {
        val account = database.playerProfileDao().get()?.steamId?.takeIf { it.isNotBlank() }
            ?: return { _, _, _ -> emptySet() }
        val generation = marker.generation()
        fun boundaryAlert(category: ProgressCategory, title: String): ProgressAlert {
            val permissionGranted = platform.permissionGranted()
            val channelAllowed = platform.channelAllowed(category)
            return ProgressAlert("$account:$generation:${category.name}:", account, generation,
                category.name, "", null, title, "",
                if (permissionGranted && channelAllowed != false) ProgressAlert.PENDING else ProgressAlert.SUPPRESSED)
        }
        val quota = boundaryAlert(ProgressCategory.QUOTA, "Daily quota met")
        val streak = boundaryAlert(ProgressCategory.STREAK, "Streak ended")
        return { before, after, enabledCategories ->
            fun handoff(template: ProgressAlert, category: ProgressCategory, sourceId: String, text: String) =
                Json.encodeToString(template.copy(
                    eventKey = "${template.eventKey}$sourceId",
                    sourceId = sourceId,
                    text = text,
                    state = if (template.state == ProgressAlert.PENDING && category in enabledCategories)
                        ProgressAlert.PENDING else ProgressAlert.SUPPRESSED,
                ))
            buildSet {
                (after.pendingQuestDates - before.pendingQuestDates).forEach { date ->
                    add(handoff(quota, ProgressCategory.QUOTA, date.toString(), "Daily quota met on $date."))
                }
                after.pendingStreakBreak?.takeIf { it != before.pendingStreakBreak }?.let {
                    add(handoff(streak, ProgressCategory.STREAK, it.date.toString(),
                        "Your ${it.previousLength}-day streak ended on ${it.date}."))
                }
            }
        }
    }

    suspend fun recoverHandoffs() {
        for (raw in settings.progressAlertHandoffs.first()) {
            val alert = Json.decodeFromString<ProgressAlert>(raw)
            // Old-account handoffs are removed even if the reset already cleared the Room receipts.
            coordinator.withLock { marker.withAccountState {
                if (marker.pendingSteamId() == null && marker.generation() == alert.generation &&
                    credentials.currentCredentials()?.steamId == alert.account) {
                    database.progressAlertDao().insert(alert)
                }
                settings.removeProgressAlertHandoff(raw)
            } }
        }
    }

    suspend fun dispatch() {
        platform.registerChannels()
        recoverHandoffs()
        for (alert in database.progressAlertDao().pending()) {
            // The same fence as source commits and reset: no account promotion can race a post.
            coordinator.withLock { marker.withAccountState {
                val category = ProgressCategory.valueOf(alert.category)
                val current = marker.pendingSteamId() == null && marker.generation() == alert.generation &&
                    credentials.currentCredentials()?.steamId == alert.account
                val staleRecap = category == ProgressCategory.RECAP &&
                    alert.sourceId != previousRecapDate(time.nowMillis(), time.zone()).toString()
                if (!current || staleRecap || !enabled(category) || !platform.permissionGranted() || platform.channelAllowed(category) == false) {
                    database.progressAlertDao().transition(alert.eventKey, ProgressAlert.PENDING, ProgressAlert.SUPPRESSED)
                } else {
                    check(platform.channelAllowed(category) == true) { "Progress channel registration incomplete" }
                    val notification = platform.prepare(alert) // A definitive failure here remains retryable.
                    if (database.progressAlertDao().transition(alert.eventKey, ProgressAlert.PENDING, ProgressAlert.POST_CLAIMED) == 1) {
                        try {
                            platform.post(alert, notification)
                            database.progressAlertDao().transition(alert.eventKey, ProgressAlert.POST_CLAIMED, ProgressAlert.DELIVERED)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Android may have accepted the call. Never reopen an ambiguous claim.
                        }
                    }
                }
            } }
        }
        recoverSharedFallbacks()
    }

    private suspend fun recoverSharedFallbacks() {
        for (alert in database.progressAlertDao().sharedFallbacks()) {
            coordinator.withLock { marker.withAccountState {
                val dao = database.progressAlertDao()
                val current = dao.get(alert.eventKey) ?: return@withAccountState
                if (!current.sharedFallbackPending || current.state == ProgressAlert.PENDING) return@withAccountState

                if (marker.pendingSteamId() == null && marker.generation() == current.generation &&
                    credentials.currentCredentials()?.steamId == current.account && current.state != ProgressAlert.DELIVERED
                ) {
                    database.gameDao().getById(checkNotNull(current.appId))?.let {
                        settings.publishSharedGameAnnouncement(
                            current.eventKey, it.appId, it.name, time.nowMillis(),
                        )
                    }
                }
                dao.finishSharedFallback(current.eventKey)
            } }
        }
    }
}
