package com.example.backlogium.work

import android.content.Context
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** WorkManager cleanup is advisory; the durable reader generation remains the actual fence. */
class CloudRoutineWorkCanceller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun cancelOldReaderWork(removePeriodic: Boolean) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(CloudRoutineCatchUpWorker.ONE_TIME_NAME).await()
        if (removePeriodic) manager.cancelUniqueWork(CloudRoutineCatchUpWorker.PERIODIC_NAME).await()
    }
}
