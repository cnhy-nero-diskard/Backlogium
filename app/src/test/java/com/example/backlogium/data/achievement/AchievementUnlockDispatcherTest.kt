package com.example.backlogium.data.achievement

import androidx.room.Room
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.credentials.EncryptedCredentialStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.AchievementWatchWork
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.data.remote.SteamApi
import com.example.backlogium.data.repo.*
import com.example.backlogium.domain.*
import com.example.backlogium.work.ActivityVisibilityTracker
import com.example.backlogium.work.SteamSyncCoordinator
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AchievementUnlockDispatcherTest {
    private lateinit var db: BacklogiumDatabase
    private lateinit var dispatcher: AchievementUnlockDispatcher
    private val settings = FakeSettingsRepository()
    private val posts = mutableListOf<String>()
    private var result = AchievementUnlockNotifier.Result.POSTED
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val group = AchievementUnlockGroup("event", "fixture", 1, "Fixture", 1,
        listOf(AchievementUnlockItem("first", "First", null, 1, 5.0)))
    private val work get() = AchievementWatchWork(group.eventId, group.account, group.appId, "epoch", 1, false, Json.encodeToString(group))

    @Before fun setUp() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java).allowMainThreadQueries().build()
        db.playerProfileDao().insertIfMissing()
        db.playerProfileDao().resetForAccountChange("fixture")
        db.gameDao().upsert(Game(1, "Fixture", "", 0, 0, 0))
        val time = SystemTimeProvider()
        val api = Proxy.newProxyInstance(SteamApi::class.java.classLoader, arrayOf(SteamApi::class.java)) { _, _, _ -> error("no network") } as SteamApi
        val credentials = object : CredentialsProvider {
            override suspend fun currentCredentials() = CredentialsState.Configured("fixture", "fixture")
        }
        dispatcher = AchievementUnlockDispatcher(db.achievementWatchDao(), AchievementObservationStore(db, credentials,
            AccountChangeMarkerStore(context), SteamSyncCoordinator()), settings,
            HiddenGamesRepository(db.hiddenGameDao(), db.gameDao(), db.gameGenreCacheDao(), time),
            CredentialsRepository(EncryptedCredentialStore(context), api),
            ProgressEventRepository(InMemoryProgressMarksStore(), db.playerProfileDao(), db.dailyProgressDao(), ProgressTransitionCoordinator(), db.achievementWatchDao()),
            ActivityVisibilityTracker(context), object : AchievementUnlockNotifier {
                override fun post(group: AchievementUnlockGroup): AchievementUnlockNotifier.Result {
                    // The background choice must already be durable before external posting.
                    runBlocking {
                        assertEquals(AchievementWatchWork.BACKGROUND, db.achievementWatchDao().get(group.eventId)!!.deliveryState)
                    }
                    posts += group.eventId
                    return result
                }
            }, scope)
        db.achievementWatchDao().insert(work)
        Unit
    }
    @After fun close() { scope.cancel(); db.close() }

    @Test fun foregroundClaimIsTerminalBeforeRenderingAndCannotReplay() = runBlocking {
        assertTrue(dispatcher.dispatch(work, true))
        assertEquals(AchievementWatchWork.FOREGROUND, db.achievementWatchDao().get("event")!!.deliveryState)
        assertEquals(group, dispatcher.foregroundGroups.first())
        dispatcher.dispatch(work, true)
        assertNull(withTimeoutOrNull(10) { dispatcher.foregroundGroups.first() })
        assertTrue(posts.isEmpty())
    }

    @Test fun uncertainBackgroundPostRetainsSurfaceAndReusesIdentity() = runBlocking {
        result = AchievementUnlockNotifier.Result.RETRY
        assertFalse(dispatcher.dispatch(work, false))
        assertEquals(AchievementWatchWork.BACKGROUND, db.achievementWatchDao().get("event")!!.deliveryState)
        result = AchievementUnlockNotifier.Result.POSTED
        assertTrue(dispatcher.dispatch(work, true)) // Returning to foreground cannot switch its surface.
        assertEquals(listOf("event", "event"), posts)
        assertEquals(AchievementWatchWork.DELIVERED, db.achievementWatchDao().get("event")!!.deliveryState)
        assertNull(withTimeoutOrNull(10) { dispatcher.foregroundGroups.first() })
    }

    @Test fun deniedPermissionAndAlertOptOutAreTerminal() = runBlocking {
        result = AchievementUnlockNotifier.Result.SUPPRESSED
        dispatcher.dispatch(work, false)
        assertEquals(AchievementWatchWork.SUPPRESSED, db.achievementWatchDao().get("event")!!.deliveryState)
        db.achievementWatchDao().insert(work.copy(eventId = "second", payload = Json.encodeToString(group.copy(eventId = "second"))))
        settings.setAchievementWatch(AchievementWatchPreferences(alertsEnabled = false))
        dispatcher.dispatch(work.copy(eventId = "second"), true)
        assertEquals(AchievementWatchWork.SUPPRESSED, db.achievementWatchDao().get("second")!!.deliveryState)
        settings.setAchievementWatch(AchievementWatchPreferences())
        dispatcher.dispatch(work.copy(eventId = "second"), false)
        assertEquals(1, posts.size)
    }

    @Test fun hiddenOrReplacedAccountsCannotDispatchOrOpenDestination() = runBlocking {
        db.hiddenGameDao().upsertAll(listOf(HiddenGame(1, 1, false)))
        dispatcher.dispatch(work, true)
        assertTrue(posts.isEmpty())
        assertFalse(dispatcher.canOpen("fixture", 1))
        db.hiddenGameDao().deleteAll()
        assertFalse(dispatcher.canOpen("other", 1))
        assertTrue(dispatcher.canOpen("fixture", 1))
    }
}
