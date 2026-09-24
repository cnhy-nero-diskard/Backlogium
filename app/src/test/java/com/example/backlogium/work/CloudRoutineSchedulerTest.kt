package com.example.backlogium.work

import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.backlogium.data.repo.CloudRoutinePolicy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CloudRoutineSchedulerTest {
    private lateinit var workManager: WorkManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context, Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun tearDown() = WorkManagerTestInitHelper.closeWorkDatabase()

    @Test
    fun periodicPolicyUpdatesCadenceAndNeverRequiresThePhoneToBeOnline() {
        val expectedHours = mapOf(
            CloudRoutinePolicy.AUTOMATIC to 24L,
            CloudRoutinePolicy.EVERY_12_HOURS to 12L,
            CloudRoutinePolicy.DAILY to 24L,
            CloudRoutinePolicy.EVERY_48_HOURS to 48L,
        )
        expectedHours.forEach { (policy, hours) ->
            CloudRoutineScheduler.enqueuePeriodic(workManager, "account", 3L, policy)
            val info = workManager.getWorkInfosForUniqueWork(
                CloudRoutineCatchUpWorker.PERIODIC_NAME,
            ).get().single { !it.state.isFinished }
            assertEquals(WorkInfo.State.ENQUEUED, info.state)
            val request = androidx.work.impl.WorkManagerImpl.getInstance()!!
                .workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
            assertEquals("account", request.input.getString(CloudRoutineCatchUpWorker.KEY_ACCOUNT))
            assertEquals(3L, request.input.getLong(CloudRoutineCatchUpWorker.KEY_GENERATION, -1L))
            assertEquals(java.util.concurrent.TimeUnit.HOURS.toMillis(hours), request?.intervalDuration)
            assertEquals(NetworkType.CONNECTED, request?.constraints?.requiredNetworkType)
        }
    }

    @Test
    fun duplicatePlayEndOpportunityStaysQueuedOffline() = runTest {
        CloudRoutineScheduler.enqueueOneTime(workManager, "account", 3L)
        CloudRoutineScheduler.enqueueOneTime(workManager, "account", 3L)
        val infos = workManager.getWorkInfosForUniqueWork(CloudRoutineCatchUpWorker.ONE_TIME_NAME).get()
        assertEquals(1, infos.count { !it.state.isFinished })
        assertTrue(infos.all { it.state == WorkInfo.State.ENQUEUED })
    }
}
