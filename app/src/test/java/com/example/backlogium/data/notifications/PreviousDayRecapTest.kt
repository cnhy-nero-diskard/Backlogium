package com.example.backlogium.data.notifications

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.room.Room
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.Achievement
import com.example.backlogium.data.local.entity.DailyProgress
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.DailyActivityRepository
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.test.SettingsDataStoreRule
import com.example.backlogium.work.SteamSyncCoordinator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
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
class PreviousDayRecapTest {
    @get:Rule val settingsRule = SettingsDataStoreRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val settings by lazy { settingsRule.create() }
    private lateinit var db: BacklogiumDatabase
    private lateinit var alerts: ProgressAlerts
    private lateinit var recap: PreviousDayRecap
    private var instant = Instant.parse("2026-11-26T00:05:00Z")
    private var zone = ZoneId.of("UTC")
    private var account = "fixture"
    private val time = object : TimeProvider {
        override fun nowMillis() = instant.toEpochMilli()
        override fun zone() = zone
        override fun today() = instant.atZone(zone).toLocalDate()
    }
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().resetForAccountChange(account)
        val marker = AccountChangeMarkerStore(context)
        val coordinator = SteamSyncCoordinator()
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured("fixture-key", account)
        }
        val platform = ProgressAlertPlatform(context)
        platform.registerChannels()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        settings.setProgressAlert(ProgressCategory.RECAP, true)
        alerts = ProgressAlerts(db, settings, platform, marker, credentials, coordinator, time)
        recap = PreviousDayRecap(db, DailyActivityRepository(db, time), alerts, time, credentials, marker, coordinator)
    }
    @After fun close() = db.close()

    @Test fun midnightAndDstUseCalendarDaysInsteadOfTwentyFourHourOffsets() {
        val newYork = ZoneId.of("America/New_York")
        fun millis(value: String) = Instant.parse(value).toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 24), previousRecapDate(millis("2026-11-25T23:59:59Z"), zone))
        assertEquals(LocalDate.of(2026, 11, 25), previousRecapDate(millis("2026-11-26T00:00:00Z"), zone))
        assertEquals(TimeUnit.HOURS.toMillis(23), nextRecapDelay(millis("2026-03-08T05:00:00Z"), newYork))
        assertEquals(TimeUnit.HOURS.toMillis(25), nextRecapDelay(millis("2026-11-01T04:00:00Z"), newYork))
    }

    @Test fun zoneChangesRecomputeYesterdayAndDelayedPendingRecapsCannotReplayOlderDates() = runBlocking {
        recap.recordCurrentDay()
        assertEquals("2026-11-25", db.progressAlertDao().pending().single().sourceId)
        zone = ZoneId.of("Pacific/Honolulu")
        recap.recordCurrentDay()
        assertEquals(setOf("2026-11-24", "2026-11-25"), db.progressAlertDao().pending().map { it.sourceId }.toSet())
        instant = Instant.parse("2026-11-30T22:00:00Z")
        recap.recordCurrentDay() // A deferred job only creates the current yesterday.
        alerts.dispatch()
        val notices = context.getSystemService(NotificationManager::class.java).activeNotifications
        assertEquals(1, notices.size)
        assertEquals("Your recap for 2026-11-29", notices.single().notification.extras.getString("android.title"))
        recap.recordCurrentDay()
        alerts.dispatch()
        assertEquals(1, context.getSystemService(NotificationManager::class.java).activeNotifications.size)
    }

    @Test fun canonicalPlayAndDatedUnlocksExcludeHiddenGamesAndUnsupportedDates() = runBlocking {
        val yesterday = LocalDate.of(2026, 11, 25)
        val start = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        for (appId in 1L..3L) db.gameDao().upsert(Game(appId, "Game $appId", "", 0, 0, 0))
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(3L, 1L)))
        for ((id, minutes) in listOf(1L to 20, 1L to 10, 2L to 15, 3L to 999)) {
            db.sessionDao().insert(Session(appId = id, startAt = start, minutes = minutes, open = false))
        }
        fun achievement(id: Long, name: String, unlockedAt: Long?) = Achievement(appId = id, apiName = name, displayName = name, unlocked = true,
            unlockedAt = unlockedAt, globalPercent = 1.0, snapshotPercent = 1.0, fetchedAt = start)
        db.achievementDao().upsertAll(listOf(
            achievement(1, "dated", start), achievement(1, "observed-only", null),
            achievement(1, "older", start - 1), achievement(2, "next-day", start + TimeUnit.DAYS.toMillis(1)),
            achievement(3, "hidden", start),
        ))
        db.dailyProgressDao().upsert(DailyProgress(yesterday.toString(), 45))
        recap.recordCurrentDay()
        val pending = db.progressAlertDao().pending().single()
        assertTrue(pending.text.contains("45 recorded minutes across 2 games; 1 unlocks with confirmed dates"))
        assertTrue(pending.text.contains("timing may include estimates"))
        assertTrue(pending.text.contains("without supported dates are excluded"))
        assertTrue(pending.text.contains("unobserved play is not included"))
        alerts.dispatch()
        context.getSystemService(NotificationManager::class.java).cancelAll()
        recap.recordCurrentDay()
        alerts.dispatch()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun missingCoverageIsDisclosedAndOptOutIsNotReopened() = runBlocking {
        settings.setProgressAlert(ProgressCategory.RECAP, false)
        recap.recordCurrentDay()
        val marker = AccountChangeMarkerStore(context)
        val key = "$account:${marker.generation()}:RECAP:2026-11-25"
        val suppressed = db.progressAlertDao().get(key)!!
        assertEquals(ProgressAlert.SUPPRESSED, suppressed.state)
        assertTrue(suppressed.text.contains("Coverage is incomplete"))
        settings.setProgressAlert(ProgressCategory.RECAP, true)
        recap.recordCurrentDay()
        alerts.dispatch()
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
