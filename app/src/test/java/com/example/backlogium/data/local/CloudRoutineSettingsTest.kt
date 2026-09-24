package com.example.backlogium.data.local

import com.example.backlogium.data.repo.CloudRoutinePolicy
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
}
