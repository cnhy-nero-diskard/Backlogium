package com.example.backlogium.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.await
import androidx.work.workDataOf
import com.example.backlogium.data.local.dao.AchievementAdmissionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

interface AchievementAdmissionWorkScheduler {
    suspend fun enqueue(steamId: String, generation: Long, delayMillis: Long = 0L)
    suspend fun recoverPending()
}

/** One durable serialized chain per Steam account and account-reset generation. */
@Singleton
class WorkManagerAchievementAdmissionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val admissionDao: AchievementAdmissionDao,
) : AchievementAdmissionWorkScheduler {
    private val workManager get() = WorkManager.getInstance(context)

    override suspend fun enqueue(steamId: String, generation: Long, delayMillis: Long) {
        val request = OneTimeWorkRequestBuilder<AchievementAdmissionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                AchievementAdmissionWorker.KEY_STEAM_ID to steamId,
                AchievementAdmissionWorker.KEY_GENERATION to generation,
            ))
            .build()
        workManager.enqueueUniqueWork(
            workName(steamId, generation),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        ).await()
    }

    override suspend fun recoverPending() {
        admissionDao.pendingIdentities().forEach { enqueue(it.steamId, it.accountGeneration) }
    }

    companion object {
        fun workName(steamId: String, generation: Long) = "achievement_admissions_${steamId}_$generation"
    }
}
