package com.example.backlogium.ui.settings

import android.Manifest
import android.app.NotificationManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.notifications.*
import com.example.backlogium.data.repo.*
import com.example.backlogium.domain.*
import com.example.backlogium.ui.theme.BacklogiumTheme
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Local fixtures only; start this targeted test with POST_NOTIFICATIONS denied. */
class ProgressAlertDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun settingsThroughDurableDeliveryPreservesLocalProgressWhenAndroidIsDenied() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = context.cacheDir.resolve("progress-alert-device-${System.nanoTime()}.preferences_pb")
        val writer = SupervisorJob()
        val settings = SettingsDataStore(PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + writer), produceFile = { file }))
        val db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).build()
        val manager = context.getSystemService(NotificationManager::class.java)
        val marker = AccountChangeMarkerStore(context)
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured("fixture-key", "progress-device-fixture")
        }
        val platform = ProgressAlertPlatform(context)
        platform.registerChannels()
        val alerts = ProgressAlerts(db, settings, platform, marker, credentials, SteamSyncCoordinator())
        try {
            assertFalse("Run with notification permission denied", platform.permissionGranted())
            assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
                context.checkSelfPermission(Manifest.permission.VIBRATE))
            assertTrue(manager.getNotificationChannel(ProgressCategory.QUOTA.channelId).shouldVibrate())
            db.playerProfileDao().insertIfMissing()
            db.playerProfileDao().resetForAccountChange("progress-device-fixture")
            compose.setContent {
                val preferences by settings.progressAlertPreferences.collectAsState(ProgressCategory.entries.associateWith { it.defaultEnabled })
                BacklogiumTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProgressAlertSettings(preferences) { category, enabled -> runBlocking { settings.setProgressAlert(category, enabled) } }
                    }
                }
            }
            compose.onNodeWithText("Android notifications are disabled. In-app progress remains available.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Daily quota").performScrollTo().assertIsOff().performClick().assertIsOn()
            compose.onNodeWithContentDescription("Broken streaks").performScrollTo().assertIsOff()
            val store = DataStoreProgressMarksStore(settings, alerts)
            val date = LocalDate.of(2026, 11, 25)
            store.write(ProgressMarks(initialized = true, lastCelebratedLevel = 1))
            store.update { it.copy(pendingQuestDates = setOf(date)) }
            alerts.dispatch()
            assertTrue(db.progressAlertDao().pending().isEmpty())
            assertFalse(manager.activeNotifications.any { it.tag?.contains("progress-device-fixture") == true })
            val events = ProgressEventRepository(store, db.playerProfileDao(), db.dailyProgressDao(), ProgressTransitionCoordinator())
            assertTrue(events.pendingEvents.first().contains(ProgressEvent.QuestMet(date)))

            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            assertTrue(platform.permissionGranted())
            alerts.dispatch() // Permission enablement must not revive the suppressed event.
            assertFalse(manager.activeNotifications.any { it.tag?.contains("progress-device-fixture") == true })
            compose.onNodeWithContentDescription("New owned games").performScrollTo().performClick().assertIsOn()
            db.gameDao().upsert(Game(166L, "Device fixture", "", 0, 0, 0))
            alerts.admission("progress-device-fixture", marker.generation(), 166L, "Device fixture", shared = false)
            alerts.dispatch()
            val notification = manager.activeNotifications.single { it.tag?.contains("progress-device-fixture") == true }
            assertEquals("Added Device fixture", notification.notification.extras.getString("android.title"))
            assertTrue(notification.notification.contentIntent.isImmutable)
            manager.cancel(notification.tag, notification.id)
            alerts.dispatch()
            assertFalse(manager.activeNotifications.any { it.tag?.contains("progress-device-fixture") == true })
            events.acknowledge(ProgressEvent.QuestMet(date))
            assertFalse(events.pendingEvents.first().contains(ProgressEvent.QuestMet(date)))
        } finally {
            manager.activeNotifications.filter { it.tag?.contains("progress-device-fixture") == true }.forEach { manager.cancel(it.tag, it.id) }
            db.close()
            writer.cancelAndJoin()
            file.delete()
        }
    }
}
