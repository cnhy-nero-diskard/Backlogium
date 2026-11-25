package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** A deadline timer only; the drain re-reads authoritative eligibility from Room. */
@HiltWorker
class AchievementAdmissionWakeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val scheduler: AchievementAdmissionWorkScheduler,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val steamId = inputData.getString(AchievementAdmissionWorker.KEY_STEAM_ID)
            ?.takeIf { it.isNotBlank() } ?: return Result.success()
        val generation = inputData.getLong(AchievementAdmissionWorker.KEY_GENERATION, -1L)
        if (generation < 0L) return Result.success()
        scheduler.enqueue(steamId, generation)
        return Result.success()
    }
}
