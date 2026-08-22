package com.example.backlogium.work

import com.example.backlogium.data.diagnostics.PresenceDecisionRecorder
import com.example.backlogium.data.diagnostics.PresenceOutcome
import com.example.backlogium.data.local.AcquiredGamesAnnouncement
import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.data.local.dao.DiagnosticsDao
import com.example.backlogium.data.local.entity.PresenceDecision
import com.example.backlogium.data.local.entity.RequestBreakdown
import com.example.backlogium.data.local.entity.RequestCounterTotals
import com.example.backlogium.data.local.entity.RequestRouteTotals
import com.example.backlogium.data.local.entity.SyncRun
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate
import java.time.ZoneId

/**
 * Covers [PresenceService.onTimeout] directly — no [android.app.Service] lifecycle beyond field
 * injection is needed, since Robolectric's [org.robolectric.shadows.ShadowService] tracks
 * `stopSelf` bookkeeping without requiring the service to be attached to a real context. Per
 * design.md's automatable list: "that `onTimeout` stops the service" and that it writes its
 * diagnostic record, unlike the platform budget/refusal behaviour itself, which needs a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PresenceServiceLifecycleTest {

    @Test
    fun onTimeout_stopsTheServiceAndRecordsTheRuntimeBudgetOutcome() {
        val dao = RecordingDiagnosticsDao()
        val settings = FakeSettingsRepository()
        val dispatcher = StandardTestDispatcher()

        val service = PresenceService()
        service.settings = settings
        service.diagnostics = PresenceDecisionRecorder(dao, FixedTimeProvider)
        service.applicationScope = CoroutineScope(dispatcher)

        service.onTimeout(startId = 7, fgsType = 0)
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(PresenceService.isRunning)
        val shadow = shadowOf(service as android.app.Service)
        assertTrue(shadow.isStoppedBySelf)
        assertEquals(7, shadow.stopSelfId)

        assertEquals(
            listOf(PresenceOutcome.RUNTIME_BUDGET_REACHED.value),
            dao.decisions.map { it.outcome },
        )
        assertEquals(
            PresenceMonitoringAvailability.RUNTIME_BUDGET_EXHAUSTED,
            settings.recordedAvailability,
        )
    }

    private object FixedTimeProvider : TimeProvider {
        override fun nowMillis(): Long = 1_000L
        override fun zone(): ZoneId = ZoneId.of("UTC")
        override fun today(): LocalDate = LocalDate.parse("2026-08-17")
    }

    private class RecordingDiagnosticsDao : DiagnosticsDao {
        val decisions = mutableListOf<PresenceDecision>()

        override suspend fun insertRun(run: SyncRun): Long = 1L
        override suspend fun insertBreakdowns(rows: List<RequestBreakdown>) = Unit
        override suspend fun insertPresenceDecision(decision: PresenceDecision) {
            decisions += decision
        }
        override fun observeRuns(): Flow<List<SyncRun>> = emptyFlow()
        override fun observeRun(runId: Long): Flow<SyncRun?> = emptyFlow()
        override fun observeBreakdowns(runId: Long): Flow<List<RequestBreakdown>> = emptyFlow()
        override fun observePresenceDecisions(): Flow<List<PresenceDecision>> = emptyFlow()
        override suspend fun incrementRequestTotal(hourStart: Long, route: String, status: String, ok: Boolean, count: Int) = Unit
        override suspend fun pruneRequestTotals(cutoff: Long) = Unit
        override fun observeRequestTotals(cutoff: Long): Flow<RequestCounterTotals> = emptyFlow()
        override fun observeRequestRoutes(cutoff: Long): Flow<List<RequestRouteTotals>> = emptyFlow()
        override suspend fun pruneRuns(limit: Int) = Unit
        override suspend fun prunePresenceDecisions(limit: Int) = Unit
        override suspend fun deleteRequestBreakdowns() = Unit
        override suspend fun deleteSyncRuns() = Unit
        override suspend fun deletePresenceDecisions() = Unit
        override suspend fun deleteRequestTotals() = Unit
    }

    /** Only [setLiveMonitoringAvailability] is exercised; everything else is unused filler. */
    private class FakeSettingsRepository : SettingsRepository {
        var recordedAvailability: PresenceMonitoringAvailability? = null

        override suspend fun setLiveMonitoringAvailability(availability: PresenceMonitoringAvailability) {
            recordedAvailability = availability
        }

        override val liveSession: Flow<LiveSessionState> = MutableStateFlow(LiveSessionState())
        override suspend fun setLiveSession(appId: Long?, startedAt: Long) = error("not used")
        override suspend fun clearLiveSession() = error("not used")

        override val notificationPermissionRequested: Flow<Boolean> = MutableStateFlow(true)
        override suspend fun setNotificationPermissionRequested() = error("not used")

        override val liveMonitorEnabled: Flow<Boolean> = MutableStateFlow(false)
        override val acquiredGames: Flow<AcquiredGamesAnnouncement> =
            MutableStateFlow(AcquiredGamesAnnouncement())
        override suspend fun setAcquiredGamesDismissed() = Unit
        override suspend fun setLiveMonitorEnabled(enabled: Boolean) = error("not used")

        override val ruleConfig: Flow<RuleConfig> = MutableStateFlow(RuleConfig())
        override suspend fun setRuleConfig(config: RuleConfig) = error("not used")
        override val librarySort: Flow<LibrarySortPrefs> = MutableStateFlow(LibrarySortPrefs())
        override suspend fun setFocusSort(key: LibrarySortKey) = error("not used")
        override suspend fun setLibrarySort(key: LibrarySortKey) = error("not used")
        override suspend fun setFocusSortDirection(direction: LibrarySortDirection) =
            error("not used")

        override suspend fun setLibrarySortDirection(direction: LibrarySortDirection) =
            error("not used")
        override val libraryDensity: Flow<GameListDensity> = MutableStateFlow(GameListDensity.LIST)
        override suspend fun setLibraryDensity(density: GameListDensity) = error("not used")
        override val collectionDensity: Flow<GameListDensity> = MutableStateFlow(GameListDensity.LIST)
        override suspend fun setCollectionDensity(density: GameListDensity) = error("not used")
        override val autoSnapshotSettings: Flow<AutoSnapshotSettings> =
            MutableStateFlow(AutoSnapshotSettings())
        override suspend fun setAutoSnapshotEnabled(enabled: Boolean) = error("not used")
        override suspend fun setSnapshotRetentionCount(count: Int) = error("not used")
        override suspend fun setSnapshotIntervalHours(hours: Int) = error("not used")
    }
}
