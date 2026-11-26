package com.example.backlogium.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AchievementAdmissionWakeWorkerTest {
    private val context = RuntimeEnvironment.getApplication()

    @After
    fun closeWorkManager() {
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun failedDeadlineEnqueueRetriesWithoutAnotherExternalTrigger() = runBlocking {
        var enqueueAttempts = 0
        val scheduler = object : AchievementAdmissionWorkScheduler {
            override suspend fun enqueue(steamId: String, generation: Long, delayMillis: Long) {
                enqueueAttempts++
                if (enqueueAttempts == 1) throw IOException("Simulated WorkManager enqueue failure")
            }

            override suspend fun recoverPending() = Unit
        }
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = if (workerClassName == AchievementAdmissionWakeWorker::class.java.name) {
                AchievementAdmissionWakeWorker(appContext, workerParameters, scheduler)
            } else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(workerFactory)
                .build(),
        )
        val workManager = WorkManager.getInstance(context)
        val request = OneTimeWorkRequestBuilder<AchievementAdmissionWakeWorker>()
            .setInputData(workDataOf(
                AchievementAdmissionWorker.KEY_STEAM_ID to "account-a",
                AchievementAdmissionWorker.KEY_GENERATION to 4L,
            ))
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

        workManager.enqueue(request).result.get()
        withTimeout(5_000L) {
            workManager.getWorkInfoByIdFlow(request.id).first {
                it?.state == WorkInfo.State.ENQUEUED && it.runAttemptCount == 1
            }
        }
        assertEquals(1, enqueueAttempts)

        WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(request.id)

        val completed = withTimeout(5_000L) {
            workManager.getWorkInfoByIdFlow(request.id).first { it?.state?.isFinished == true }
        }
        assertEquals(WorkInfo.State.SUCCEEDED, completed?.state)
        assertEquals(2, enqueueAttempts)
    }
}
