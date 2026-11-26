package com.example.backlogium.data.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import androidx.room.withTransaction
import com.example.backlogium.GameNotificationIntent
import com.example.backlogium.GameNotificationTarget
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.achievement.AchievementObservationStore
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
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
    private val credentials = object : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured {
            if (failCredentials) error("definitive pre-post failure")
            return CredentialsState.Configured("fixture-key", account)
        }
    }
    private fun alerts() = ProgressAlerts(db, settings, platform, marker, credentials, coordinator)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val databaseName = "progress-alert-test.db"
    private fun open() = Room.databaseBuilder(context, BacklogiumDatabase::class.java, databaseName)
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
}
