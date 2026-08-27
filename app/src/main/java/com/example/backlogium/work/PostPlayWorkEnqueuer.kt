package com.example.backlogium.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The asynchronous WorkManager enqueue operation, kept behind a small seam so the session-end
 * outbox can be tested against an operation that has not completed yet.
 */
interface PostPlayWorkEnqueuer {
    fun enqueue(
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Operation
}

@Singleton
class WorkManagerPostPlayWorkEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
) : PostPlayWorkEnqueuer {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun enqueue(
        uniqueWorkName: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ): Operation = workManager.enqueueUniqueWork(uniqueWorkName, policy, request)
}
