package com.example.backlogium.data.setup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.example.backlogium.work.setup.AdmissionKind
import com.example.backlogium.work.setup.SetupOperationState
import com.example.backlogium.work.setup.SetupOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

private val Context.testSetupStore by preferencesDataStore(name = "setup")

/**
 * Real-DataStore round-trip and legacy migration for the versioned setup attempt records
 * (task 3.2). The store retains known outcomes, ignores unknown staged attempts, and never
 * fabricates a live job: an intent-only record stays intent-only, and a legacy marker without a
 * work id is read but never turned into an admitted attempt.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreSetupStateStoreTest {

    private lateinit var context: Context
    private lateinit var store: DataStoreSetupStateStore

    @Before
    fun setUp() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.testSetupStore.edit { it.clear() }
        store = DataStoreSetupStateStore(context.testSetupStore)
    }

    @Test
    fun futureRecordVersionIsNotInterpretedAsCurrentWork() {
        assertNull(decodeStageAttempt(encodeStageAttempt(StageAttemptRecord(stageId = "library_sync", schemaVersion = 2))))
        assertEquals(1, decodeStageAttempt(encodeStageAttempt(StageAttemptRecord(stageId = "library_sync")))?.schemaVersion)
    }

    @Test
    fun anAttemptRecordSurvivesARoundTripWithEveryField() = runBlocking {
        val record = StageAttemptRecord(
            stageId = "library_sync",
            generation = 3L,
            cohortId = "cohort-1",
            accountMarker = "opaque-marker",
            requestId = "req-1",
            uniqueWorkName = "steam_sync_now",
            candidateWorkId = "candidate-uuid",
            admittedWorkId = "uuid-1",
            admissionKind = AdmissionKind.REUSED,
            operation = SetupOperationState.RetryScheduled(
                attempt = 2,
                reason = "transient",
            ),
            foregroundSettledReason = com.example.backlogium.work.setup.ForegroundSettledReason.BACKOFF,
        )

        store.upsertAttempt(record)

        assertEquals(record, store.attemptRecord("library_sync"))
        assertEquals(record, store.storedAttemptRecords()["library_sync"])
    }

    @Test
    fun compareAndSwapOnlyReplacesTheExactExpectedAttempt() = runBlocking {
        store.upsertAttempt(StageAttemptRecord(stageId = "sync", generation = 1L, accountMarker = "m"))

        // A matching swap lands.
        val swapped = store.compareAndSwapAttempt(
            stageId = "sync",
            expectedGeneration = 1L,
            expectedAdmittedWorkId = null,
            expectedAccountMarker = "m",
            newRecord = StageAttemptRecord(
                stageId = "sync",
                generation = 1L,
                accountMarker = "m",
                operation = SetupOperationState.Succeeded(),
            ),
        )
        assertTrue(swapped)
        assertEquals(SetupOperationState.Succeeded(), store.attemptRecord("sync")?.operation)

        // A replacement generation (2) cannot be regressed by a stale callback still expecting 1.
        store.upsertAttempt(StageAttemptRecord(stageId = "sync", generation = 2L, accountMarker = "m"))
        val stale = store.compareAndSwapAttempt(
            stageId = "sync",
            expectedGeneration = 1L,
            expectedAdmittedWorkId = null,
            expectedAccountMarker = "m",
            newRecord = StageAttemptRecord(stageId = "sync", generation = 1L, operation = SetupOperationState.Failed("stale")),
        )
        assertFalse(stale)
        assertEquals(2L, store.attemptRecord("sync")?.generation)
        assertTrue(store.attemptRecord("sync")?.operation is SetupOperationState.NeverRun)
    }

    @Test
    fun upsertingAgainRetainsOnlyTheLatestAttempt() = runBlocking {
        store.upsertAttempt(StageAttemptRecord(stageId = "sync", generation = 1L))
        store.upsertAttempt(
            StageAttemptRecord(
                stageId = "sync",
                generation = 2L,
                operation = SetupOperationState.Succeeded(),
            ),
        )

        val record = store.attemptRecord("sync")
        assertEquals(2L, record?.generation)
        assertEquals(SetupOperationState.Succeeded(), record?.operation)
        assertEquals(1, store.storedAttemptRecords().size)
    }

    @Test
    fun legacyOutcomeKeysRemainReadableAndKnownOutcomesSurvive() = runBlocking {
        store.writeOutcome("library_sync", SetupOutcome.Succeeded)
        store.writeOutcome("completion_times", SetupOutcome.Failed("dataset fetch failed"))

        val outcomes = store.storedOutcomes()
        assertEquals(SetupOutcome.Succeeded, outcomes["library_sync"])
        assertEquals(SetupOutcome.Failed("dataset fetch failed"), outcomes["completion_times"])
    }

    @Test
    fun legacyOutcomesMergeWithLatestAttemptRecords() = runBlocking {
        store.writeOutcome("assets", SetupOutcome.Failed("old failure"))
        store.upsertAttempt(
            StageAttemptRecord(
                stageId = "assets",
                generation = 1L,
                operation = SetupOperationState.Succeeded(),
            ),
        )
        store.writeOutcome("times", SetupOutcome.Skipped)

        val outcomes = store.storedOutcomes()
        // The latest attempt record wins over the legacy historical outcome for the same stage.
        assertEquals(SetupOutcome.Succeeded, outcomes["assets"])
        // A stage with no attempt keeps its legacy terminal outcome.
        assertEquals(SetupOutcome.Skipped, outcomes["times"])
    }

    @Test
    fun pendingOperationStatesAreNotLegacyTerminalOutcomes() = runBlocking {
        store.upsertAttempt(
            StageAttemptRecord(
                stageId = "sync",
                generation = 1L,
                operation = SetupOperationState.Waiting("queued"),
            ),
        )
        // Pending work is never a terminal legacy outcome: it reads as "in progress" (NeverRun),
        // which is exactly what pre-3.2 surfaces understood as still running.
        assertEquals(SetupOutcome.NeverRun, store.storedOutcomes()["sync"])
    }

    @Test
    fun unknownStageAttemptsAreRetainedByTheStoreNotDroppedOrInvented() = runBlocking {
        store.upsertAttempt(
            StageAttemptRecord(
                stageId = "stage_from_the_future",
                generation = 1L,
                operation = SetupOperationState.Succeeded(),
            ),
        )

        // The store keeps the unknown id (the registry projection is what ignores it), and it does
        // not fabricate any additional live job for any stage.
        assertNotNull(store.storedAttemptRecords()["stage_from_the_future"])
    }

    @Test
    fun corruptOrFutureAttemptRowsAreIgnoredNotFatal() = runBlocking {
        // A row written by a newer app with a state this build cannot decode (or a corrupted row)
        // must not stop the store from rendering every other value.
        context.testSetupStore.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("stage_attempt_corrupt")] =
                "{\"stageId\":\"corrupt\""
        }
        store.upsertAttempt(
            StageAttemptRecord(stageId = "sync", generation = 1L, operation = SetupOperationState.Succeeded()),
        )

        assertNull(store.storedAttemptRecords()["corrupt"])
        assertNotNull(store.storedAttemptRecords()["sync"])
        assertTrue(store.storedOutcomes().containsKey("sync"))
    }

    @Test
    fun anIntentOnlyAttemptNeverGainsAFabricatedJob() = runBlocking {
        store.upsertAttempt(
            StageAttemptRecord(
                stageId = "sync",
                generation = 1L,
                requestId = "req-interrupted",
                uniqueWorkName = "steam_sync_now",
            ),
        )

        val record = store.attemptRecord("sync")
        assertEquals("req-interrupted", record?.requestId)
        assertNull("an interrupted admission stays unassociated until recovery", record?.admittedWorkId)
        assertFalse("no arbitrary finished/historical job is attached", record?.hasDurableAdmission == true)
    }

    @Test
    fun aLegacyActiveMarkerIsReadAndConsumedWithoutGuessingAJob() = runBlocking {
        // Pre-3.2 store shape: active stage + selected set + no persisted work id.
        context.testSetupStore.edit { prefs ->
            prefs[androidx.datastore.preferences.core.stringPreferencesKey("active_stage_id")] = "library_sync"
            prefs[
                androidx.datastore.preferences.core.stringSetPreferencesKey("active_stage_selection")
            ] = setOf("library_sync", "completion_times")
        }

        val marker = store.readLegacyActiveMarker()
        assertEquals("library_sync", marker?.stageId)
        assertNull(marker?.workId)
        assertEquals(setOf("library_sync", "completion_times"), marker?.selectedStageIds)

        store.consumeLegacyActiveMarker()
        assertNull(store.readLegacyActiveMarker())
    }

    @Test
    fun cohortRoundsTripAndClears() = runBlocking {
        assertEquals(SetupCohort(), store.cohort())

        store.setCohort("cohort-9", setOf("a", "b"), "opaque")
        assertEquals(
            SetupCohort("cohort-9", setOf("a", "b"), "opaque"),
            store.cohort(),
        )

        store.clearCohort()
        assertEquals(SetupCohort(), store.cohort())
    }

    @Test
    fun theFirstRunFlagAndCompletedFlagRoundTrip() = runBlocking {
        assertFalse(store.firstRunSetupActiveFlow.first())
        assertFalse(store.completedFlow.first())

        store.setFirstRunSetupActive(true)
        store.markCompleted()

        assertTrue(store.firstRunSetupActiveFlow.first())
        assertTrue(store.completedFlow.first())
    }
}
