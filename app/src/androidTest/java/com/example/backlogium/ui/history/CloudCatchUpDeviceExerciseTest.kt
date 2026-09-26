package com.example.backlogium.ui.history

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.example.backlogium.data.repo.CloudReadSummary
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.CloudReadTrigger
import com.example.backlogium.data.repo.CloudRoutineAdmission
import com.example.backlogium.data.repo.CloudRoutineAttempt
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
            val beforeAdmission = api.calls.size
            assertEquals(
                CloudRoutineAttempt.NotAdmitted(CloudRoutineAdmission.SATISFIED_BY_READ),
                repository.runRoutineCatchUp(account, generation, consume = {}),
            )
            assertEquals("a newer terminal manual read should satisfy one opportunity", beforeAdmission,
                api.calls.size)

            val partialAttempt = repository.runRoutineCatchUp(
                account = account,
                generation = generation,
                consume = {},
            )
            assertTrue(partialAttempt is CloudRoutineAttempt.Admitted)
            val partial = (partialAttempt as CloudRoutineAttempt.Admitted).outcome
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
            val resumedAttempt = repository.runRoutineCatchUp(
                account = account,
                generation = generation,
                consume = {},
            )
            assertTrue(resumedAttempt is CloudRoutineAttempt.Admitted)
            val complete = (resumedAttempt as CloudRoutineAttempt.Admitted).outcome
            assertEquals(CloudCatchUpResult.Complete(1, 2, false), complete)
            assertEquals(beforeResume + 1, api.calls.size)
            assertEquals("2026-09-23T13:00:00Z", settings.cloudReadPosition.first())

            // Manual only is a persisted policy, not an unconfigured-reader sentinel. It blocks
            // routine admission before and after recreating both repositories, but leaves manual
            // Read now and the existing cooldown/cursors intact when a cadence is re-enabled.
            val lastRoutineAttempt = checkNotNull(settings.cloudRoutineState.first().lastAdmittedAt)
            assertEquals(time.now, lastRoutineAttempt)
            val completedCursor = settings.cloudReadPosition.first()
            val ingestPosition = settings.cloudIngestPosition.first()
            settings.setCloudRoutinePolicy(CloudRoutinePolicy.OFF_MANUAL_ONLY)
            assertEquals(CloudRoutinePolicy.OFF_MANUAL_ONLY, settings.cloudRoutineState.first().policy)

            val callsBeforeOffAttempt = api.calls.size
            assertEquals(
                CloudRoutineAttempt.NotAdmitted(CloudRoutineAdmission.UNAVAILABLE),
                repository.runRoutineCatchUp(account, generation, consume = {}),
            )
            assertEquals(callsBeforeOffAttempt, api.calls.size)

            val restartedSettings = DataStoreSettingsRepository(SettingsDataStore(context))
            val restartedRepository = CloudPresenceRepository(
                api, credentialsStore, FixedCredentialsProvider(account), restartedSettings,
                database.cloudReadDao(),
                RoomCloudPendingEvidence(database.pendingCloudEvidenceDao(), database.gameDao(),
                    RoomDatabaseTransactionScope(database)), time,
            )
            restartedRepository.refreshConfiguration()
            assertNotNull(restartedRepository.configuration.first())
            val afterRestart = restartedSettings.cloudRoutineState.first()
            assertEquals(CloudRoutinePolicy.OFF_MANUAL_ONLY, afterRestart.policy)
            assertEquals(lastRoutineAttempt, afterRestart.lastAdmittedAt)
            assertEquals(completedCursor, restartedSettings.cloudReadPosition.first())
            assertEquals(ingestPosition, restartedSettings.cloudIngestPosition.first())

            assertEquals(
                CloudRoutineAttempt.NotAdmitted(CloudRoutineAdmission.UNAVAILABLE),
                restartedRepository.runRoutineCatchUp(account, generation, consume = {}),
            )
            assertEquals(callsBeforeOffAttempt, api.calls.size)

            api.onRead = { position ->
                assertEquals(completedCursor, position)
                page(
                    transitions = emptyList(),
                    start = "2026-09-25T13:00:00Z",
                    end = "2026-09-25T13:10:00Z",
                    readAt = "2026-09-25T13:10:00Z",
                    next = "2026-09-25T13:10:00Z",
                )
            }
            assertTrue(restartedRepository.read(CloudReadTrigger.SETTINGS_MANUAL) is CloudReadResult.Success)
            val manualReadCursor = "2026-09-25T13:10:00Z"
            assertEquals(manualReadCursor, restartedSettings.cloudReadPosition.first())
            assertEquals(CloudRoutinePolicy.OFF_MANUAL_ONLY, restartedSettings.cloudRoutineState.first().policy)

            restartedSettings.setCloudRoutinePolicy(CloudRoutinePolicy.DAILY)
            val reenabled = restartedSettings.cloudRoutineState.first()
            assertEquals(CloudRoutinePolicy.DAILY, reenabled.policy)
            assertEquals(lastRoutineAttempt, reenabled.lastAdmittedAt)
            assertEquals(manualReadCursor, restartedSettings.cloudReadPosition.first())
            assertEquals(CloudRoutineAdmission.COOLDOWN,
                restartedSettings.admitCloudRoutine(lastRoutineAttempt + 60L * 60_000L))
            assertEquals(lastRoutineAttempt, restartedSettings.cloudRoutineState.first().lastAdmittedAt)

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

    @Test
    fun configuredEmptyHistoryWithFailedStaleReaderFitsCompactDarkAndLargeTextViews() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli()
        val state = HistoryUiState(
            loading = false,
            configured = true,
            today = "2026-09-26",
            windowStartDate = "2026-08-27",
            cloudReaderConfigured = true,
            cloudReadSummary = CloudReadSummary(
                lastAttemptAt = now - 60 * 60_000L,
                lastTrigger = CloudReadTrigger.SETTINGS_MANUAL,
                lastOutcome = CloudReadSummaryOutcome.FAILED,
                lastFailure = CloudReadFailure.UNREACHABLE,
                lastSuccessAt = now - 48 * 60 * 60_000L,
                latestObservationAt = now - 72 * 60 * 60_000L,
            ),
            statusNow = now,
        )
        val showingHistory = mutableStateOf(true)
        val darkTheme = mutableStateOf(false)
        val fontScale = mutableFloatStateOf(1f)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = fontScale.floatValue),
            ) {
                BacklogiumTheme(darkTheme = darkTheme.value) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (showingHistory.value) {
                            HistoryContent(state, onOpenCloudActivity = { showingHistory.value = false })
                        } else {
                            CloudActivityContent(state, onBack = { showingHistory.value = true })
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("No history yet").assertIsDisplayed()
        composeRule.onNodeWithText("Cloud activity").assertIsDisplayed()
        captureScreenshot("history-empty-compact.png", context)

        composeRule.onNodeWithText("Cloud activity").performClick()
        composeRule.onNodeWithText("Back to History").assertIsDisplayed()
        composeRule.onNodeWithText("History loaded:", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "No cloud contributions are recorded in this loaded History window.",
        ).assertIsDisplayed()
        captureScreenshot("cloud-activity-empty-compact.png", context)
        composeRule.onNodeWithText("Reader status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Reader request failed", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("The latest observation is over a day old.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Recovered play · 0 contributions").assertDoesNotExist()

        composeRule.runOnIdle { darkTheme.value = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No cloud contributions are recorded in this loaded History window.")
            .performScrollTo().assertIsDisplayed()
        captureScreenshot("cloud-activity-empty-dark.png", context)

        composeRule.runOnIdle {
            darkTheme.value = false
            fontScale.floatValue = 2f
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Back to History").assertIsDisplayed()
        composeRule.onNodeWithText("No cloud contributions are recorded in this loaded History window.")
            .performScrollTo().assertIsDisplayed()
        captureScreenshot("cloud-activity-empty-large-font.png", context)
        composeRule.onNodeWithText("Reader request failed", substring = true)
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("The latest observation is over a day old.")
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "Reader success does not confirm current poller health. Missing observations do not mean no play.",
        ).performScrollTo().assertIsDisplayed()
        captureScreenshot("cloud-activity-empty-large-font-status.png", context)

        composeRule.onNodeWithText("Back to History").performClick()
        composeRule.onNodeWithText("No history yet").assertIsDisplayed()
    }

    @Test
    fun cloudActivityRevealsAnInitiallyUncomposedDistantEarlierSessionOnDevice() {
        val today = LocalDate.parse("2026-09-26")
        val targetDate = today.minusDays(20).toString()
        val targetSession = HistorySessionUi(
            id = 991L,
            startAt = Instant.parse("2026-09-06T10:30:00Z").toEpochMilli(),
            minutes = 91,
            open = false,
            cloudContribution = SessionCloudContribution(recoveredSharedPlay = ContributionState.FULL),
        )
        val targetGame = HistoryGameGroup(
            appId = 991L,
            name = "Distant target",
            iconUrl = "",
            minutesPlayed = targetSession.minutes,
            sessions = listOf(targetSession),
        )
        val days = (0..20).map { offset ->
            val date = today.minusDays(offset.toLong()).toString()
            val games = when (offset) {
                0, 1, 2 -> {
                    val session = HistorySessionUi(
                        id = 10_000L + offset,
                        startAt = Instant.parse("$date" + "T08:00:00Z").toEpochMilli(),
                        minutes = 10,
                        open = false,
                    )
                    listOf(HistoryGameGroup(
                        appId = 200L + offset,
                        name = if (offset == 0) "Today game" else "Earlier day $offset game",
                        iconUrl = "",
                        minutesPlayed = session.minutes,
                        sessions = listOf(session),
                    ))
                }
                20 -> listOf(targetGame)
                else -> emptyList()
            }
            HistoryDayGroup(
                date = date,
                minutesPlayed = games.sumOf { it.minutesPlayed },
                goalMinutesPlayed = 0,
                questMet = games.isNotEmpty(),
                games = games,
                achievements = HistoryAchievements(emptyList(), 0),
            )
        }
        val state = HistoryUiState(
            loading = false,
            configured = true,
            days = days,
            today = today.toString(),
            windowStartDate = days.last().date,
            cloudReaderConfigured = true,
            statusNow = Instant.parse("2026-09-26T12:00:00Z").toEpochMilli(),
        )
        val showingHistory = mutableStateOf(true)
        val reveal = mutableStateOf<HistoryReveal?>(null)
        composeRule.setContent {
            BacklogiumTheme {
                if (showingHistory.value) {
                    HistoryContent(
                        state = state,
                        onOpenCloudActivity = { showingHistory.value = false },
                        reveal = reveal.value,
                        onRevealHandled = { reveal.value = null },
                    )
                } else {
                    CloudActivityContent(
                        state = state,
                        onOpenSession = { item ->
                            reveal.value = HistoryReveal(item.date, item.game.appId, item.session.id)
                            showingHistory.value = true
                        },
                        onBack = { showingHistory.value = true },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(historyDayTestTag(today.toString()))
            .assert(androidx.compose.ui.test.hasStateDescription("Expanded"))
        (1..2).forEach { offset ->
            val precedingDate = today.minusDays(offset.toLong()).toString()
            composeRule.onNodeWithTag(historyDayTestTag(precedingDate))
                .performScrollTo().performClick()
            composeRule.onNodeWithTag(historyDayTestTag(precedingDate))
                .assert(androidx.compose.ui.test.hasStateDescription("Expanded"))
        }
        composeRule.onNodeWithTag(historySessionTestTag(targetSession.id)).assertDoesNotExist()

        composeRule.onNodeWithText("Cloud activity").performScrollTo().performClick()
        composeRule.onNodeWithText("Distant target").performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(historySessionTestTag(targetSession.id))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_HISTORY_REVEAL_UNAVAILABLE).assertDoesNotExist()
        composeRule.onNodeWithTag(historyDayTestTag(targetDate))
            .assert(androidx.compose.ui.test.hasStateDescription("Expanded"))
        composeRule.onNodeWithText("Distant target").assertIsDisplayed()
        composeRule.onNodeWithTag(historySessionTestTag(targetSession.id)).assertIsDisplayed()
    }

    private fun captureScreenshot(name: String, context: Context) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "backlogium-$name")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BacklogiumTestCaptures")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ))
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            checkNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                assertTrue("failed to write screenshot $name",
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            context.contentResolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
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
