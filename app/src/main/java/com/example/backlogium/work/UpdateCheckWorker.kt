package com.example.backlogium.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.backlogium.data.updates.AppUpdateRepository
import com.example.backlogium.BuildConfig
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/** Best-effort release discovery. All remote failures are deliberately terminal successes. */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: AppUpdateRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        if (BuildConfig.DEBUG) return Result.success()
        repository.check(force = false)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "app_update_check"
    }
}
