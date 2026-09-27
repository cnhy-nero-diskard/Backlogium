package com.example.backlogium.data.local

import com.example.backlogium.data.repo.CloudRoutinePolicy
import com.example.backlogium.data.repo.CloudRoutineAdmission
import com.example.backlogium.data.repo.CloudReadFailure
import com.example.backlogium.data.repo.CloudReadSummaryOutcome
import com.example.backlogium.data.repo.CloudReadTrigger
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudRoutineSettingsTest {
    @Test fun routineOutcomeSurvivesManualReadPolicyChangeAndRestartButNotRemoval() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsDataStore(context)
        store.clearAccountDerivedState()
        try {
            store.initializeCloudRoutinePolicy()
            store.recordCloudRoutineAdmission(100L)
            store.recordCloudRoutineOutcome(100L, CloudReadSummaryOutcome.PARTIAL)
            store.recordCloudReadSummary(200L, CloudReadTrigger.SETTINGS_MANUAL,
                CloudReadSummaryOutcome.COMPLETE, null, null, false, null, null)
            store.setCloudRoutinePolicy(CloudRoutinePolicy.DAILY)
            val reopened = SettingsDataStore(context).cloudRoutineStateFlow.first()
            assertEquals(CloudRoutinePolicy.DAILY, reopened.policy)
            assertEquals(100L, reopened.lastAdmittedAt)
            assertEquals(CloudReadSummaryOutcome.PARTIAL, reopened.lastOutcome)
            store.recordCloudRoutineOutcome(99L, CloudReadSummaryOutcome.COMPLETE)
            assertEquals(CloudReadSummaryOutcome.PARTIAL, store.cloudRoutineStateFlow.first().lastOutcome)
            store.clearCloudRoutinePolicy()
            assertNull(store.cloudRoutineStateFlow.first().lastOutcome)
        } finally {
            store.clearAccountDerivedState()
        }
    }

    @Test
    fun readSummaryRetainsLastSuccessAndObservationAfterPartialAndFailureAcrossRestart() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val store = SettingsDataStore(context)
        store.clearAccountDerivedState()
        try {
            store.recordCloudReadSummary(
                10L, CloudReadTrigger.SETTINGS_MANUAL, CloudReadSummaryOutcome.PARTIAL,
                null, 5L, true, 0L, 10L,
            )
            store.recordCloudReadSummary(
                20L, CloudReadTrigger.ROUTINE, CloudReadSummaryOutcome.FAILED,
                CloudReadFailure.UNREACHABLE, null, null, null, null,
            )
            val restarted = SettingsDataStore(context).cloudReadSummaryFlow.first()
            assertEquals(20L, restarted.lastAttemptAt)
            assertEquals(CloudReadSummaryOutcome.FAILED, restarted.lastOutcome)
            assertEquals(CloudReadFailure.UNREACHABLE, restarted.lastFailure)
            assertEquals(10L, restarted.lastSuccessAt)
            assertEquals(5L, restarted.latestObservationAt)
            assertEquals(true, restarted.lastSuccessHasMore)

            store.recordCloudReadSummary(
                30L, CloudReadTrigger.SYNC, CloudReadSummaryOutcome.NO_NEW_DATA,
                null, null, false, 0L, 30L,
            )
            assertEquals(5L, store.cloudReadSummaryFlow.first().latestObservationAt)
            assertEquals(false, store.cloudReadSummaryFlow.first().lastSuccessHasMore)
        } finally {
            store.clearAccountDerivedState()
        }
    }

    @Test
    fun concurrentTriggersFailureCooldownAndTerminalWatermarkAreShared() = runTest {
        val store = SettingsDataStore(RuntimeEnvironment.getApplication())
        store.clearAccountDerivedState()
        try {
            store.initializeCloudRoutinePolicy()
            val first = async { store.admitCloudRoutine(1_000L) }
            val second = async { store.admitCloudRoutine(1_000L) }
            assertEquals(
                setOf(CloudRoutineAdmission.ADMITTED, CloudRoutineAdmission.COOLDOWN),
                setOf(first.await(), second.await()),
            )
            assertEquals(CloudRoutineAdmission.COOLDOWN, store.admitCloudRoutine(2_000L))
            store.recordCloudOtherRead(terminal = true)
            assertEquals(CloudRoutineAdmission.SATISFIED_BY_READ,
                store.admitCloudRoutine(1_000L + 12L * 3_600_000L))
            assertEquals(CloudRoutineAdmission.ADMITTED,
                store.admitCloudRoutine(1_000L + 12L * 3_600_000L))
            // The used terminal read is now older than the latest admission.
            assertEquals(CloudRoutineAdmission.ADMITTED,
                store.admitCloudRoutine(1_000L + 24L * 3_600_000L))
            store.recordCloudOtherRead(terminal = true)
            store.recordCloudOtherRead(terminal = false)
            assertEquals(CloudRoutineAdmission.ADMITTED,
                store.admitCloudRoutine(1_000L + 36L * 3_600_000L))
        } finally {
            store.clearAccountDerivedState()
        }
    }

    @Test
    fun preferenceAndAdmissionSurviveStoreRecreationWithoutResettingCursors() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val first = SettingsDataStore(context)
        first.clearAccountDerivedState()
        try {
            first.setCloudReadPosition("read-cursor")
            first.setCloudIngestPosition("ingest-cursor")
            assertNull(first.cloudRoutineStateFlow.first().policy)
            assertEquals(1L, first.initializeCloudRoutinePolicy().lastAdmissionWatermark)
            first.setCloudRoutinePolicy(CloudRoutinePolicy.EVERY_12_HOURS)
            first.recordCloudRoutineAdmission(42L)

            val restarted = SettingsDataStore(context)
            assertEquals(CloudRoutinePolicy.EVERY_12_HOURS, restarted.initializeCloudRoutinePolicy().policy)
            assertEquals(42L, restarted.cloudRoutineStateFlow.first().lastAdmittedAt)
            assertEquals(2L, restarted.cloudRoutineStateFlow.first().orderingWatermark)
            assertEquals("read-cursor", restarted.cloudReadPositionFlow.first())
            assertEquals("ingest-cursor", restarted.cloudIngestPositionFlow.first())
            restarted.setCloudRoutinePolicy(CloudRoutinePolicy.EVERY_48_HOURS)
            assertEquals(42L, first.cloudRoutineStateFlow.first().lastAdmittedAt)
        } finally {
            first.clearAccountDerivedState()
        }
    }

    @Test
    fun manualOnlySurvivesRestartAndReenableKeepsCooldownAndCursors() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val first = SettingsDataStore(context)
        first.clearAccountDerivedState()
        try {
            first.setCloudReadPosition("read-cursor")
            first.setCloudIngestPosition("ingest-cursor")
            first.initializeCloudRoutinePolicy()
            first.recordCloudRoutineAdmission(42L)
            first.setCloudRoutinePolicy(CloudRoutinePolicy.OFF_MANUAL_ONLY)

            assertEquals(
                CloudRoutineAdmission.UNAVAILABLE,
                first.admitCloudRoutine(42L + 72L * 3_600_000L),
            )
            val restarted = SettingsDataStore(context)
            val disabled = restarted.initializeCloudRoutinePolicy()
            assertEquals(CloudRoutinePolicy.OFF_MANUAL_ONLY, disabled.policy)
            assertEquals(42L, disabled.lastAdmittedAt)

            restarted.setCloudRoutinePolicy(CloudRoutinePolicy.DAILY)
            val reenabled = restarted.cloudRoutineStateFlow.first()
            assertEquals(CloudRoutinePolicy.DAILY, reenabled.policy)
            assertEquals(42L, reenabled.lastAdmittedAt)
            assertEquals(CloudRoutineAdmission.COOLDOWN, restarted.admitCloudRoutine(
                42L + 12L * 3_600_000L,
            ))
            assertEquals("read-cursor", restarted.cloudReadPositionFlow.first())
            assertEquals("ingest-cursor", restarted.cloudIngestPositionFlow.first())
        } finally {
            first.clearAccountDerivedState()
        }
    }
}
