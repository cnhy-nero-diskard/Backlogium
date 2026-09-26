package com.example.backlogium.ui.history

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.backlogium.data.backup.RoomDatabaseTransactionScope
import com.example.backlogium.data.credentials.CloudCredentials
import com.example.backlogium.data.credentials.CloudCredentialsStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.local.entity.Game
import com.example.backlogium.data.local.entity.RecoveredSharedPlayState
import com.example.backlogium.data.local.entity.Session
import com.example.backlogium.data.local.entity.TimingInformedSteamPlayState
import com.example.backlogium.data.remote.CloudPresenceApi
import com.example.backlogium.data.remote.dto.CloudPresenceCurrentDto
import com.example.backlogium.data.remote.dto.CloudPresenceResponseDto
import com.example.backlogium.data.remote.dto.CloudPresenceTransitionDto
import com.example.backlogium.data.repo.CloudCatchUpResult
import com.example.backlogium.data.repo.CloudPresenceRepository
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.repo.CloudReadResult
import com.example.backlogium.data.repo.CloudReadTrigger
import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.data.repo.CredentialsState
import com.example.backlogium.data.repo.ContributionState
import com.example.backlogium.data.repo.DataStoreSettingsRepository
import com.example.backlogium.data.repo.RoomCloudPendingEvidence
import com.example.backlogium.data.repo.SessionCloudContribution
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.ui.theme.BacklogiumTheme
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises persisted reader behavior and the visible History attribution on a real Android runtime. */
@RunWith(AndroidJUnit4::class)
class CloudCatchUpDeviceExerciseTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun configuredStaleFailingAndMultiPageCatchUpKeepsHistoryLocalAndDoesNotTouchPollerSchedule() = runBlocking {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Keep this integration fixture out of the installed app's credential-protected Settings.
        val context = appContext.createDeviceProtectedStorageContext()
        val database = Room.inMemoryDatabaseBuilder(context, BacklogiumDatabase::class.java)
            .allowMainThreadQueries().build()
        val store = SettingsDataStore(context)
        val api = ReaderApi()
        val credentialsStore = ReaderCredentialsStore()
        val account = "76561198000000000"
        val start = Instant.parse("2026-09-22T00:00:00Z").toEpochMilli()
        val startText = Instant.ofEpochMilli(start).toString()
        val readerEndpoint = "https://reader.example.test/read"
        val settings = DataStoreSettingsRepository(store)
        try {
            store.clearAccountDerivedState()
            database.gameDao().upsert(Game(
                appId = 440L,
                name = "Deep Quest",
                iconUrl = "",
                playtimeForever = 600,
                playtime2Weeks = 0,
                lastPlaytime = 600,
            ))
            database.sessionDao().insert(Session(
                appId = 440L,
                startAt = start,
                endAt = start + 45 * 60_000L,
                minutes = 45,
                open = false,
                recoveredSharedPlay = RecoveredSharedPlayState.PARTIAL,
                timingInformedSteamPlay = TimingInformedSteamPlayState.PARTIAL,
            ))
            credentialsStore.writeCloudCredentials(readerEndpoint, "device-test-token")
            val time = MutableDeviceTime(Instant.parse("2026-09-24T12:00:00Z").toEpochMilli())
            val repository = CloudPresenceRepository(
                api = api,
                credentialsStore = credentialsStore,
                credentialsProvider = FixedCredentialsProvider(account),
                settings = settings,
                cloudReadDao = database.cloudReadDao(),
                pendingEvidence = RoomCloudPendingEvidence(
                    database.pendingCloudEvidenceDao(), database.gameDao(), RoomDatabaseTransactionScope(database),
                ),
                time = time,
            )
            repository.refreshConfiguration()
            assertNotNull(repository.configuration.first())
            assertEquals(CloudRoutinePolicy.AUTOMATIC, repository.reconcileRoutinePolicy()?.policy)

            val firstCursor = "2026-09-23T12:00:00Z"
            api.onRead = { position ->
                assertEquals(null, position)
                page(
                    transitions = emptyList(),
                    start = "2026-09-20T00:00:00Z",
                    end = "2026-09-20T01:00:00Z",
                    readAt = "2026-09-24T12:00:00Z",
                    next = firstCursor,
                    current = CloudPresenceCurrentDto(
                        v = 2, lastObservedAt = "2026-09-20T01:00:00Z", gameid = "500",
                        gameName = "Poller sample", personastate = 1,
                    ),
                )
            }
            assertTrue(repository.read(CloudReadTrigger.SETTINGS_MANUAL) is CloudReadResult.Success)
            assertEquals(firstCursor, settings.cloudReadPosition.first())

            api.onRead = { throw IOException("simulated offline reader") }
            time.now = Instant.parse("2026-09-24T12:01:00Z").toEpochMilli()
            assertEquals(CloudReadResult.Failed(CloudReadFailure.UNREACHABLE),
                repository.read(CloudReadTrigger.SETTINGS_MANUAL))
            val failedSummary = repository.readSummary.first()
            assertEquals(com.example.backlogium.data.repo.CloudReadSummaryOutcome.FAILED, failedSummary.lastOutcome)
            assertEquals(CloudReadFailure.UNREACHABLE, failedSummary.lastFailure)
            assertEquals(CloudReadTrigger.SETTINGS_MANUAL, failedSummary.lastTrigger)
            assertEquals(Instant.parse("2026-09-24T12:00:00Z").toEpochMilli(), failedSummary.lastSuccessAt)
            assertEquals(Instant.parse("2026-09-20T01:00:00Z").toEpochMilli(), failedSummary.latestObservationAt)
            assertTrue(observationOlderThanDay(failedSummary, time.now))

            // Reopening the repository is local-only: configuration and summary survive, and no
            // extra API read is issued while the device is offline.
            val callsBeforeReopen = api.calls.size
            val reopened = CloudPresenceRepository(
                api, credentialsStore, FixedCredentialsProvider(account), settings, database.cloudReadDao(),
                RoomCloudPendingEvidence(database.pendingCloudEvidenceDao(), database.gameDao(),
                    RoomDatabaseTransactionScope(database)), time,
            )
            reopened.refreshConfiguration()
            assertNotNull(reopened.configuration.first())
            assertEquals(failedSummary, reopened.readSummary.first())
            assertEquals(callsBeforeReopen, api.calls.size)

            api.installMultiPageFixture(firstCursor)
            val generation = settings.cloudReaderGeneration.first()
            val partial = repository.readRoutineCatchUp(
                expectedAccount = account,
                expectedGeneration = generation,
                consume = {},
            )
            assertEquals(CloudCatchUpResult.Partial(4, 8), partial)
            val partialCursor = settings.cloudReadPosition.first()
            assertEquals("2026-09-23T12:40:00Z", partialCursor)
            val pending = database.pendingCloudEvidenceDao().intervals(account, generation)
            assertTrue("owned timing intervals were not retained on device", pending.isNotEmpty())
            assertTrue(pending.any { it.appId == 440L })

            val beforeResume = api.calls.size
            val nextDate = "2026-09-23T12:50:00Z"
            time.now = Instant.parse("2026-09-25T00:02:00Z").toEpochMilli()
            api.onRead = { position ->
                assertEquals("2026-09-23T12:40:00Z", position)
                page(
                    transitions = listOf(
                        transition(nextDate, 440, "Deep Quest"),
                        transition("2026-09-23T13:00:00Z", 500, "Poller sample"),
                    ),
                    start = "2026-09-23T12:40:00Z",
                    end = "2026-09-23T13:00:00Z",
                    readAt = "2026-09-25T00:02:00Z",
                    next = "2026-09-23T13:00:00Z",
                )
            }
            val complete = repository.readRoutineCatchUp(
                expectedAccount = account,
                expectedGeneration = generation,
                consume = {},
            )
            assertEquals(CloudCatchUpResult.Complete(1, 2, false), complete)
            assertEquals(beforeResume + 1, api.calls.size)
            assertEquals("2026-09-23T13:00:00Z", settings.cloudReadPosition.first())

            // Catch-up only acquires evidence. It must not replace Steam's minute ledger or
            // the already recorded two-fact contribution on the realistic local History row.
            val storedSession = database.sessionDao().getAll().single()
            assertEquals(45, storedSession.minutes)
            assertEquals(RecoveredSharedPlayState.PARTIAL, storedSession.recoveredSharedPlay)
            assertEquals(TimingInformedSteamPlayState.PARTIAL, storedSession.timingInformedSteamPlay)
            assertTrue(api.calls.isNotEmpty())
            assertTrue(api.calls.all {
                it.endpoint == readerEndpoint && it.authorization == "Bearer device-test-token"
            })

            val historySession = HistorySessionUi(
                id = storedSession.id,
                startAt = storedSession.startAt,
                minutes = storedSession.minutes,
                open = storedSession.open,
                cloudContribution = SessionCloudContribution(
                    recoveredSharedPlay = storedSession.recoveredSharedPlay.toContributionState(),
                    timingInformedSteamPlay = storedSession.timingInformedSteamPlay.toContributionState(),
                ),
            )
            val day = HistoryDayGroup(
                date = "2026-09-22", minutesPlayed = 45, goalMinutesPlayed = 0, questMet = false,
                games = listOf(HistoryGameGroup(440, "Deep Quest", "", 45, listOf(historySession))),
                achievements = HistoryAchievements(emptyList(), 0),
            )
            val historyState = mutableStateOf(HistoryUiState(
                loading = false, configured = true, days = listOf(day), today = time.today().toString(),
                cloudReaderConfigured = true,
                cloudReadSummary = failedSummary,
                statusNow = time.now,
            ))
            val showHistory = mutableStateOf(true)
            val reveal = mutableStateOf<HistoryReveal?>(HistoryReveal(day.date, 440L, storedSession.id))
            composeRule.setContent {
                BacklogiumTheme {
                    if (showHistory.value) {
                        HistoryContent(
                            state = historyState.value,
                            onOpenCloudActivity = { showHistory.value = false },
                            reveal = reveal.value,
                            onRevealHandled = { reveal.value = null },
                        )
                    } else {
                        CloudActivityContent(
                            state = historyState.value,
                            onOpenSession = { item ->
                                reveal.value = HistoryReveal(item.date, item.game.appId, item.session.id)
                                showHistory.value = true
                            },
                            onBack = { showHistory.value = true },
                        )
                    }
                }
            }

            composeRule.onNodeWithText("Deep Quest").performScrollTo().assertIsDisplayed()
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(TAG_HISTORY_CLOUD_MARK).performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithTag(TAG_HISTORY_CLOUD_MARK).assert(
                androidx.compose.ui.test.hasContentDescription(
                    "Partly recovered play. Partly cloud-informed timing.",
                ),
            )
            composeRule.onNodeWithTag(TAG_HISTORY_CLOUD_MARK).performClick()
            composeRule.onNodeWithText("Cloud observations recovered some of this session’s play; other play was recorded locally.")
                .assertIsDisplayed()
            composeRule.onNodeWithText("Cloud observations informed the timing of part of this session. Steam supplied the minute total.")
                .assertIsDisplayed()
            composeRule.onNodeWithText("Got it").performClick()
            composeRule.onNodeWithText("Cloud activity").performClick()
            composeRule.onNodeWithText("Recovered play · 1 contribution").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Cloud-timed Steam play · 1 contribution").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Reader request failed", substring = true)
                .performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("The latest observation is over a day old.").performScrollTo().assertIsDisplayed()

            // Without a reader the same persisted session and minutes remain visible, but its
            // cloud-specific entry/mark are suppressed rather than treated as local loss.
            composeRule.runOnIdle {
                historyState.value = historyState.value.copy(cloudReaderConfigured = false)
                reveal.value = HistoryReveal(day.date, 440L, storedSession.id)
                showHistory.value = true
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Deep Quest").performScrollTo().assertIsDisplayed()
            composeRule.onNodeWithText("Cloud activity").assertDoesNotExist()
            composeRule.onAllNodesWithText("45 mins played", substring = true)
                .assertCountEquals(2)
            composeRule.onAllNodesWithText("45 mins played", substring = true)[0].assertIsDisplayed()
            composeRule.onNodeWithTag(TAG_HISTORY_CLOUD_MARK).assertDoesNotExist()
        } finally {
            store.clearAccountDerivedState()
            database.close()
        }
    }

    private fun page(
        transitions: List<CloudPresenceTransitionDto>,
        start: String,
        end: String,
        readAt: String,
        next: String?,
        more: Boolean = false,
        current: CloudPresenceCurrentDto? = null,
    ) = CloudPresenceResponseDto(
        account = "76561198000000000",
        transitions = transitions,
        current = current,
        nextPosition = next,
        hasMore = more,
        windowStart = start,
        windowEnd = end,
        readAt = readAt,
    )

    private fun transition(at: String, appId: Long, name: String) = CloudPresenceTransitionDto(
        v = 3, t = at, gameid = appId.toString(), gameName = name, personastate = 1,
    )

    private fun dtoPage(transitions: List<CloudPresenceTransitionDto>, more: Boolean, next: String) = page(
        transitions = transitions,
        start = "2026-09-23T12:00:00Z",
        end = transitions.last().t!!,
        readAt = "2026-09-24T12:00:00Z",
        next = next,
        more = more,
    )

    private data class ReaderCall(val endpoint: String, val authorization: String, val position: String?)

    private inner class ReaderApi : CloudPresenceApi {
        val calls = mutableListOf<ReaderCall>()
        var onRead: suspend (String?) -> CloudPresenceResponseDto = { error("reader fixture is not installed") }

        override suspend fun read(
            endpoint: String,
            authorization: String,
            position: String?,
        ): CloudPresenceResponseDto {
            calls += ReaderCall(endpoint, authorization, position)
            return onRead(position)
        }

        fun installMultiPageFixture(start: String) {
            val t1 = "2026-09-23T12:10:00Z"
            val t2 = "2026-09-23T12:20:00Z"
            val t3 = "2026-09-23T12:30:00Z"
            val t4 = "2026-09-23T12:40:00Z"
            val t5 = "2026-09-23T12:50:00Z"
            val t6 = "2026-09-23T13:00:00Z"
            val p1 = dtoPage(listOf(transition(t1, 440, "Deep Quest"), transition(t2, 500, "Poller sample")), true, t2)
            val p2 = dtoPage(listOf(transition(t2, 500, "Poller sample"), transition(t3, 440, "Deep Quest")), true, t3)
            val p3 = dtoPage(listOf(transition(t3, 440, "Deep Quest"), transition(t4, 500, "Poller sample")), true, t4)
            val p4 = dtoPage(listOf(transition(t4, 500, "Poller sample"), transition(t5, 440, "Deep Quest")), true, t5)
            val terminal = dtoPage(listOf(transition(t5, 440, "Deep Quest"), transition(t6, 500, "Poller sample")), false, t6)
            val pages = mapOf(start to p1, t1 to p2, t2 to p3, t3 to p4, t4 to terminal)
            onRead = { position -> pages[position] ?: error("unexpected reader position $position") }
        }
    }

    private class ReaderCredentialsStore : CloudCredentialsStore {
        private var value: CloudCredentials? = null
        override suspend fun readCloudCredentials(): CloudCredentials? = value
        override suspend fun writeCloudCredentials(endpoint: String, token: String) {
            value = CloudCredentials(endpoint, token)
        }
        override suspend fun clearCloudCredentials() { value = null }
    }

    private class MutableDeviceTime(var now: Long) : TimeProvider {
        override fun nowMillis(): Long = now
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
    }

    private class FixedCredentialsProvider(private val account: String) : CredentialsProvider {
        override suspend fun currentCredentials(): CredentialsState.Configured =
            CredentialsState.Configured("local-test-key", account)
    }

    private fun com.example.backlogium.data.local.entity.RecoveredSharedPlayState?
        .toContributionState(): ContributionState = when (this) {
        com.example.backlogium.data.local.entity.RecoveredSharedPlayState.UNKNOWN, null -> ContributionState.UNKNOWN
        com.example.backlogium.data.local.entity.RecoveredSharedPlayState.NONE -> ContributionState.NONE
        com.example.backlogium.data.local.entity.RecoveredSharedPlayState.FULL -> ContributionState.FULL
        com.example.backlogium.data.local.entity.RecoveredSharedPlayState.PARTIAL -> ContributionState.PARTIAL
    }

    private fun com.example.backlogium.data.local.entity.TimingInformedSteamPlayState?
        .toContributionState(): ContributionState = when (this) {
        com.example.backlogium.data.local.entity.TimingInformedSteamPlayState.UNKNOWN, null -> ContributionState.UNKNOWN
        com.example.backlogium.data.local.entity.TimingInformedSteamPlayState.NONE -> ContributionState.NONE
        com.example.backlogium.data.local.entity.TimingInformedSteamPlayState.FULL -> ContributionState.FULL
        com.example.backlogium.data.local.entity.TimingInformedSteamPlayState.PARTIAL -> ContributionState.PARTIAL
    }
}
