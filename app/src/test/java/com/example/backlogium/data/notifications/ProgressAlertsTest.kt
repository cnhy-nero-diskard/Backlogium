package com.example.backlogium.data.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.GameNotificationIntent
import com.example.backlogium.GameNotificationTarget
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.achievement.AchievementObservationStore
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.AchievementAdmissionWork
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.remote.dto.AchievementSchemaDto
import com.example.backlogium.data.remote.dto.PlayerAchievementDto
import com.example.backlogium.data.repo.*
import com.example.backlogium.domain.*
import com.example.backlogium.test.SettingsDataStoreRule
import com.example.backlogium.work.SteamSyncCoordinator
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ProgressAlertsTest {
    @get:Rule val settingsRule = SettingsDataStoreRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val settings by lazy { settingsRule.create() }
    private lateinit var db: BacklogiumDatabase
    private lateinit var platform: ProgressAlertPlatform
    private lateinit var marker: AccountChangeMarkerStore
    private val coordinator = SteamSyncCoordinator()
    private var account = "fixture"
    private var failCredentials = false
    private var beforeCredentialCheck: (suspend () -> Unit)? = null
    private val credentials = object : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured {
            beforeCredentialCheck?.also { beforeCredentialCheck = null }?.invoke()
            if (failCredentials) error("definitive pre-post failure")
            return CredentialsState.Configured("fixture-key", account)
        }
    }
    private fun alerts() = ProgressAlerts(db, settings, platform, marker, credentials, coordinator)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val databaseName = "progress-alert-test.db"
    private fun open() = Room.databaseBuilder(context, BacklogiumDatabase::class.java, databaseName)
        .setQueryExecutor(Executor { it.run() })
        .allowMainThreadQueries().build()

    @Before fun setup() = runBlocking {
        context.deleteDatabase(databaseName)
        db = open()
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().resetForAccountChange(account)
        platform = ProgressAlertPlatform(context)
        platform.registerChannels()
        marker = AccountChangeMarkerStore(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ProgressCategory.entries.forEach { settings.setProgressAlert(it, true) }
    }
    @After fun close() { db.close(); context.deleteDatabase(databaseName) }

    private suspend fun CoroutineScope.commitQuotaWhilePreferenceChanges(
        date: LocalDate,
        enabledBeforePreparation: Boolean,
        enabledAtCommit: Boolean,
        enabledBeforeDispatch: Boolean,
    ): DataStoreProgressMarksStore {
        settings.setProgressAlert(ProgressCategory.QUOTA, enabledBeforePreparation)
        val backingStore = settingsRule.backingStore()
        val editReached = CompletableDeferred<Unit>()
        val releaseEdit = CompletableDeferred<Unit>()
        var gateFirstEdit = true
        val gatedStore = object : DataStore<Preferences> by backingStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (gateFirstEdit) {
                    gateFirstEdit = false
                    editReached.complete(Unit)
                    releaseEdit.await()
                }
                return backingStore.updateData(transform)
            }
        }
        val gatedSettings = SettingsDataStore(gatedStore)
        val alerts = ProgressAlerts(db, gatedSettings, platform, marker, credentials, coordinator)
        val marksStore = DataStoreProgressMarksStore(gatedSettings, alerts)
        val write = launch {
            marksStore.update { it.copy(initialized = true, pendingQuestDates = setOf(date)) }
        }

        // updateData is reached only after earnedBoundary() has prepared the event payload.
        editReached.await()
        settings.setProgressAlert(ProgressCategory.QUOTA, enabledAtCommit)
        releaseEdit.complete(Unit)
        write.join()
        settings.setProgressAlert(ProgressCategory.QUOTA, enabledBeforeDispatch)
        alerts.dispatch()
        return marksStore
    }

    @Test fun earnedOptOutAtCommitStaysSuppressedAfterReenableAndKeepsInAppEvent() = runBlocking {
        val date = LocalDate.of(2026, 11, 25)
        val store = commitQuotaWhilePreferenceChanges(date,
            enabledBeforePreparation = true, enabledAtCommit = false, enabledBeforeDispatch = true)
        val eventKey = "$account:${marker.generation()}:QUOTA:$date"

        assertEquals(ProgressAlert.SUPPRESSED, db.progressAlertDao().get(eventKey)?.state)
        assertTrue(manager.activeNotifications.isEmpty())
        val repository = ProgressEventRepository(store, db.playerProfileDao(), db.dailyProgressDao(), ProgressTransitionCoordinator())
        assertTrue(repository.pendingEvents.first().contains(ProgressEvent.QuestMet(date)))
    }

    @Test fun earnedOptInAtCommitCreatesPendingAlertEvenIfDisabledAtPreparation() = runBlocking {
        val date = LocalDate.of(2026, 11, 24)
        val store = commitQuotaWhilePreferenceChanges(date,
            enabledBeforePreparation = false, enabledAtCommit = true, enabledBeforeDispatch = true)
        val eventKey = "$account:${marker.generation()}:QUOTA:$date"

        assertEquals(ProgressAlert.DELIVERED, db.progressAlertDao().get(eventKey)?.state)
        assertEquals(1, manager.activeNotifications.size)
        val repository = ProgressEventRepository(store, db.playerProfileDao(), db.dailyProgressDao(), ProgressTransitionCoordinator())
        assertTrue(repository.pendingEvents.first().contains(ProgressEvent.QuestMet(date)))
    }

    @Test fun ownedAndSharedSourceCommitsSurviveDeathBeforeEnqueueAndUseAdmissionIdentity() = runBlocking {
        val generation = marker.generation()
        db.withTransaction {
            for ((appId, shared) in listOf(10L to false, 20L to true)) {
                db.gameDao().upsert(Game(appId, "Game $appId", "", 0, 0, 0))
                db.achievementAdmissionDao().insert(AchievementAdmissionWork(account, generation, appId, 100L))
                alerts().admission(account, generation, appId, "Game $appId", shared)
            }
        }
        db.close() // No worker or notification enqueue has happened.
        db = open()
        val pending = db.progressAlertDao().pending()
        assertEquals(2, pending.size)
        pending.forEach {
            val admission = db.achievementAdmissionDao().get(it.account, it.generation, it.sourceId.toLong())!!
            assertEquals(admission.appId, it.appId)
            assertEquals(admission.appId.toString(), it.sourceId)
        }
        alerts().dispatch()
        assertEquals(2, manager.activeNotifications.size)
        val targets = manager.activeNotifications.map { GameNotificationIntent.read(shadowOf(it.notification.contentIntent).savedIntent) }.toSet()
        assertEquals(setOf(GameNotificationTarget(account, 10L), GameNotificationTarget(account, 20L)), targets)
        manager.cancelAll()
        alerts().dispatch()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun foregroundAcknowledgementAndReplayDoNotChangeIndependentEarnedReceipt() = runBlocking {
        val store = DataStoreProgressMarksStore(settings, alerts())
        val date = LocalDate.of(2026, 11, 25)
        store.write(ProgressMarks(initialized = true))
        store.update { it.copy(pendingQuestDates = setOf(date), pendingStreakBreak = PendingStreakBreak(date, 5)) }
        assertEquals(2, settings.progressAlertHandoffs.first().size)
        val repository = ProgressEventRepository(store, db.playerProfileDao(), db.dailyProgressDao(), ProgressTransitionCoordinator())
        repository.acknowledge(ProgressEvent.QuestMet(date))
        repository.acknowledge(ProgressEvent.StreakBroken(5))
        alerts().dispatch()
        assertEquals(2, manager.activeNotifications.size)
        assertTrue(store.read().pendingQuestDates.isEmpty())
        assertNull(store.read().pendingStreakBreak)
        assertTrue(settings.progressAlertHandoffs.first().isEmpty())
        manager.cancelAll()
        // Restore uses write(), not earned finalization, and must never reconstruct receipts.
        store.write(ProgressMarks(initialized = true, pendingQuestDates = setOf(date)))
        store.update { it }
        alerts().dispatch()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun optOutPermissionAndDisabledStableChannelAreTerminal() = runBlocking {
        settings.setProgressAlert(ProgressCategory.OWNED, false)
        val optedOut = alerts().prepare(ProgressCategory.OWNED, account, marker.generation(), "opt-out", title = "Owned", text = "")
        assertEquals(ProgressAlert.SUPPRESSED, optedOut.state)
        db.progressAlertDao().insert(optedOut)
        settings.setProgressAlert(ProgressCategory.OWNED, true)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(ProgressAlert.SUPPRESSED, alerts().prepare(ProgressCategory.QUOTA, account, marker.generation(), "denied", title = "Quota", text = "").state)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val channel = android.app.NotificationChannel(ProgressCategory.STREAK.channelId, "Existing", NotificationManager.IMPORTANCE_NONE)
        manager.deleteNotificationChannel(channel.id)
        manager.createNotificationChannel(channel)
        platform.registerChannels()
        assertEquals(false, platform.channelAllowed(ProgressCategory.STREAK))
        assertEquals(ProgressAlert.SUPPRESSED, alerts().prepare(ProgressCategory.STREAK, account, marker.generation(), "disabled", title = "Streak", text = "").state)
        alerts().dispatch()
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(6, manager.notificationChannels.size)
    }

    @Test fun missingChannelRemainsRetryableAndFirstEnabledEventPosts() = runBlocking {
        manager.deleteNotificationChannel(ProgressCategory.QUOTA.channelId)
        assertNull(platform.channelAllowed(ProgressCategory.QUOTA))
        val alert = alerts().prepare(ProgressCategory.QUOTA, account, marker.generation(), "first", title = "Quota", text = "")
        assertEquals(ProgressAlert.PENDING, alert.state)
        db.progressAlertDao().insert(alert)
        alerts().dispatch()
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun crashClaimsAreTerminalIncludingDismissalAndConcurrentClaimsHaveOneWinner() = runBlocking {
        val alert = alerts().prepare(ProgressCategory.QUOTA, account, marker.generation(), "race", title = "Quota", text = "")
        db.progressAlertDao().insert(alert)
        val claims = (1..8).map { async(Dispatchers.IO) {
            db.progressAlertDao().transition(alert.eventKey, ProgressAlert.PENDING, ProgressAlert.POST_CLAIMED)
        } }.awaitAll()
        assertEquals(1, claims.sum())
        db.close(); db = open()
        alerts().dispatch() // Crash after claim, before notify: intentionally lost.
        assertTrue(manager.activeNotifications.isEmpty())
        val second = alert.copy(eventKey = "after-post", sourceId = "after-post")
        db.progressAlertDao().insert(second)
        db.progressAlertDao().transition(second.eventKey, ProgressAlert.PENDING, ProgressAlert.POST_CLAIMED)
        platform.post(second, platform.prepare(second))
        manager.cancelAll() // Android accepted, user dismissed, DELIVERED never persisted.
        db.close(); db = open()
        alerts().dispatch()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun preClaimFailureRetriesAndAccountChangesSuppress() = runBlocking {
        val alert = alerts().prepare(ProgressCategory.QUOTA, account, marker.generation(), "retry", title = "Quota", text = "")
        db.progressAlertDao().insert(alert)
        failCredentials = true
        try { alerts().dispatch(); fail("expected failure") } catch (_: IllegalStateException) { }
        assertEquals(ProgressAlert.PENDING, db.progressAlertDao().get(alert.eventKey)?.state)
        failCredentials = false
        account = "other"
        alerts().dispatch()
        assertEquals(ProgressAlert.SUPPRESSED, db.progressAlertDao().get(alert.eventKey)?.state)
        account = "fixture"
        alerts().dispatch()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun suppressedSharedNoticeRetainsFallbackAndResetClearsOperationalState() = runBlocking {
        settings.setProgressAlert(ProgressCategory.SHARED, false)
        db.withTransaction {
            db.gameDao().upsert(Game(30L, "Borrowed", "", 0, 0, 0))
            alerts().admission(account, marker.generation(), 30L, "Borrowed", shared = true)
        }
        db.close(); db = open()
        alerts().dispatch()
        assertEquals(30L, settings.sharedGameAnnouncementFlow.first()?.appId)
        settings.clearSharedGameAnnouncement(30L)
        alerts().dispatch()
        assertNull(settings.sharedGameAnnouncementFlow.first())
        assertTrue(manager.activeNotifications.isEmpty())
        val store = DataStoreProgressMarksStore(settings, alerts())
        store.write(ProgressMarks(initialized = true))
        store.update { it.copy(pendingQuestDates = setOf(LocalDate.of(2026, 11, 25))) }
        assertFalse(settings.progressAlertHandoffs.first().isEmpty())
        AccountRoomReset(db).resetForAccountChange("other")
        settings.clearAccountDerivedState()
        assertTrue(db.progressAlertDao().pending().isEmpty())
        assertTrue(settings.progressAlertHandoffs.first().isEmpty())
    }

    @Test fun concurrentDispatchDoesNotFallbackAnAdmissionDeliveredByAnotherWorker() = runBlocking {
        settings.progressAlertHandoffs.first()
        val pending = alerts().prepare(ProgressCategory.QUOTA, account, marker.generation(), "dispatch-first",
            title = "Quota", text = "")
        db.progressAlertDao().insert(pending)

        val shared = alerts().prepare(ProgressCategory.SHARED, account, marker.generation(), "30", 30L,
            "Now tracking Borrowed", "Family Sharing").copy(sharedFallbackPending = true)
        db.gameDao().upsert(Game(30L, "Borrowed", "", 0, 0, 0))
        var secondWorker: kotlinx.coroutines.Job? = null
        beforeCredentialCheck = {
            db.progressAlertDao().insert(shared)
            // This worker snapshots the new admission while the first worker still owns the fence.
            secondWorker = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { alerts().dispatch() }
        }

        alerts().dispatch()
        secondWorker?.join()

        assertEquals(ProgressAlert.DELIVERED, db.progressAlertDao().get(shared.eventKey)?.state)
        assertTrue(manager.activeNotifications.any { it.tag == shared.eventKey })
        assertNull(settings.sharedGameAnnouncementFlow.first())
    }

    @Test fun concurrentTerminalFallbackReadersCreateOneAnnouncement() = runBlocking {
        settings.progressAlertHandoffs.first()
        settings.setProgressAlert(ProgressCategory.SHARED, false)
        db.gameDao().upsert(Game(31L, "Borrowed", "", 0, 0, 0))
        val suppressed = alerts().prepare(ProgressCategory.SHARED, account, marker.generation(), "31", 31L,
            "Now tracking Borrowed", "Family Sharing").copy(sharedFallbackPending = true)
        db.progressAlertDao().insert(suppressed)

        val workers = coordinator.withLock {
            // Both workers read the terminal receipt before either can consume it under the fence.
            listOf(
                async(start = CoroutineStart.UNDISPATCHED) { alerts().dispatch() },
                async(start = CoroutineStart.UNDISPATCHED) { alerts().dispatch() },
            )
        }
        workers.awaitAll()

        assertEquals(1, settings.sharedGameAnnouncementsFlow.first().size)
        assertFalse(db.progressAlertDao().get(suppressed.eventKey)!!.sharedFallbackPending)
        settings.clearSharedGameAnnouncement(31L)
        alerts().dispatch()
        assertNull(settings.sharedGameAnnouncementFlow.first())
    }

    @Test fun sharedFallbackPublicationSurvivesFailureDismissalAndRestartedRetry() = runBlocking {
        settings.setProgressAlert(ProgressCategory.SHARED, false)
        val appId = 32L
        val eventId = "${account}:${marker.generation()}:SHARED:$appId"
        db.gameDao().upsert(Game(appId, "Borrowed", "", 0, 0, 0))
        val fallback = alerts().prepare(ProgressCategory.SHARED, account, marker.generation(), appId.toString(), appId,
            "Now tracking Borrowed", "Family Sharing").copy(sharedFallbackPending = true)
        db.progressAlertDao().insert(fallback)

        var failAfterCommit = true
        val backingStore = settingsRule.backingStore()
        val failingStore = object : DataStore<Preferences> by backingStore {
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val committed = backingStore.updateData(transform)
                if (failAfterCommit) {
                    failAfterCommit = false
                    error("simulated process death after fallback DataStore commit")
                }
                return committed
            }
        }
        try {
            ProgressAlerts(db, SettingsDataStore(failingStore), platform, marker, credentials, coordinator)
                .dispatch()
            fail("expected failure after DataStore committed")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated process death after fallback DataStore commit", expected.message)
        }

        assertTrue(db.progressAlertDao().get(eventId)!!.sharedFallbackPending)
        assertEquals(appId, settings.sharedGameAnnouncementFlow.first()?.appId)
        settings.clearSharedGameAnnouncement(appId) // The foreground dismisses the committed cue.

        db.close(); db = open() // Restart with the Room half still pending.
        val restartedSettings = settingsRule.create()
        ProgressAlerts(db, restartedSettings, platform, marker, credentials, coordinator).dispatch()
        assertNull(restartedSettings.sharedGameAnnouncementFlow.first())
        assertFalse(db.progressAlertDao().get(eventId)!!.sharedFallbackPending)

        restartedSettings.clearAccountDerivedState()
        assertTrue(restartedSettings.publishSharedGameAnnouncement(eventId, appId, "Borrowed", 99L))
    }

    @Test fun sourceRollbackDoesNotLeaveAnAlertOrAdmission() = runBlocking {
        try {
            db.withTransaction {
                db.gameDao().upsert(Game(40L, "Rollback", "", 0, 0, 0))
                db.achievementAdmissionDao().insert(AchievementAdmissionWork(account, marker.generation(), 40L, 100L))
                alerts().admission(account, marker.generation(), 40L, "Rollback", shared = false)
                error("simulate source transaction failure")
            }
        } catch (_: IllegalStateException) { }
        assertNull(db.gameDao().getById(40L))
        assertTrue(db.progressAlertDao().pending().isEmpty())
        assertTrue(db.achievementAdmissionDao().pendingIdentities().isEmpty())
    }

    @Test fun completionRequiresTwoConfirmedFullSetsAndSurvivesRestart() = runBlocking {
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, _, _ -> error("No network expected") } as SteamApi
        val time = object : TimeProvider {
            override fun nowMillis() = 1000L
            override fun today() = LocalDate.of(2026, 11, 25)
            override fun zone() = ZoneId.of("UTC")
        }
        val hidden = HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time)
        val repo = AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(), hidden, time,
            observationStore = AchievementObservationStore(db, credentials, marker, coordinator),
            transaction = RoomDatabaseTransactionScope(db), progressAlerts = alerts())
        val schema = listOf("a", "b").associateWith { AchievementSchemaDto(it, it) }
        suspend fun merge(id: Long, at: Long, states: List<Int>) {
            repo.applyRefreshes(listOf(AchievementRefresh(id, at,
                states.mapIndexed { i, achieved -> PlayerAchievementDto(if (i == 0) "a" else "b", achieved, 0L) },
                emptyMap(), schema, at, schemaLoaded = true, account = account)))
        }
        db.gameDao().upsert(Game(1L, "First complete", "", 0, 0, 0))
        merge(1, 1, listOf(1, 1))
        assertTrue(db.progressAlertDao().pending().isEmpty())
        db.gameDao().upsert(Game(2L, "Earned", "", 0, 0, 0))
        merge(2, 2, listOf(0, 0))
        merge(2, 3, listOf(1)) // Partial response cannot complete a game.
        assertTrue(db.progressAlertDao().pending().isEmpty())
        merge(2, 4, listOf(1, 1))
        merge(2, 5, listOf(1, 1))
        assertEquals(1, db.progressAlertDao().pending().size)
        db.close(); db = open()
        alerts().dispatch()
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        alerts().dispatch()
        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test fun completionUsesTheAcceptedCatalogueAfterAnOlderSchemaRefreshLosesTheRace() = runBlocking {
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, _, _ -> error("No network expected") } as SteamApi
        val time = object : TimeProvider {
            override fun nowMillis() = 1000L
            override fun today() = LocalDate.of(2026, 11, 25)
            override fun zone() = ZoneId.of("UTC")
        }
        val hidden = HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time)
        val repo = AchievementRepository(api, db.achievementDao(), db.gameAchievementSyncDao(), db.gameDao(), hidden, time,
            observationStore = AchievementObservationStore(db, credentials, marker, coordinator),
            transaction = RoomDatabaseTransactionScope(db), progressAlerts = alerts())
        db.gameDao().upsert(Game(3L, "Expanded catalogue", "", 0, 0, 0))
        fun schema(names: List<String>) = names.associateWith { AchievementSchemaDto(it, it) }
        suspend fun player(at: Long, names: List<String>, states: List<Int>, schemaByName: Map<String, AchievementSchemaDto> = emptyMap(), schemaAt: Long? = null, schemaLoaded: Boolean = false) {
            repo.applyRefreshes(listOf(AchievementRefresh(
                appId = 3L,
                fetchedAt = at,
                achievements = names.mapIndexed { index, name -> PlayerAchievementDto(name, states[index], 0L) },
                globalPercentByName = emptyMap(),
                schemaByName = schemaByName,
                schemaFetchedAt = schemaAt,
                schemaLoaded = schemaLoaded,
                account = account,
            )))
        }

        player(10L, listOf("a", "b"), listOf(0, 0), schema(listOf("a", "b")), 10L, schemaLoaded = true)
        repo.applyRefreshes(listOf(AchievementRefresh(
            appId = 3L,
            fetchedAt = 30L,
            achievements = null,
            globalPercentByName = emptyMap(),
            schemaByName = schema(listOf("a", "b", "c")),
            schemaFetchedAt = 30L,
            schemaLoaded = true,
            account = account,
        )))

        // This older schema loses the catalogue timestamp race, but its individual unlocks remain valid.
        player(20L, listOf("a", "b"), listOf(1, 1), schema(listOf("a", "b")), 20L, schemaLoaded = true)
        assertTrue(db.progressAlertDao().pending().isEmpty())
        assertEquals(setOf("a", "b", "c"), db.achievementCatalogueDao().entries(3L).map { it.apiName }.toSet())
        val individuallyUpdated = db.achievementDao().getForGame(3L).filter { it.apiName in setOf("a", "b") }
        assertEquals(2, individuallyUpdated.size)
        assertTrue(individuallyUpdated.all { it.unlocked })

        // Confirm all three names while C is still locked; only a later full unlock is a transition.
        player(40L, listOf("a", "b", "c"), listOf(1, 1, 0))
        assertTrue(db.progressAlertDao().pending().isEmpty())
        player(50L, listOf("a", "b", "c"), listOf(1, 1, 1))
        assertEquals(1, db.progressAlertDao().pending().count { it.category == ProgressCategory.COMPLETION.name })
    }
}
