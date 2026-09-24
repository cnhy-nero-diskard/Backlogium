package com.example.backlogium.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudRoutineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Unique one-time work absorbs duplicate opportunities, including one already running. */
    fun enqueueOpportunity(account: String, generation: Long) {
        val request = OneTimeWorkRequestBuilder<CloudRoutineCatchUpWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(
                CloudRoutineCatchUpWorker.KEY_ACCOUNT to account,
                CloudRoutineCatchUpWorker.KEY_GENERATION to generation,
            ))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CloudRoutineCatchUpWorker.ONE_TIME_NAME, ExistingWorkPolicy.KEEP, request,
        )
    }
}
