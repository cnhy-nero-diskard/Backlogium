package com.example.backlogium.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.backlogium.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Schedules discovery without making app startup or composition perform a network request. */
@Singleton
class UpdateScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ensurePeriodicUpdateCheck() {
        if (BuildConfig.DEBUG) return
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            UpdateCheckPolicyHours.PERIOD_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UpdateCheckWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    private object UpdateCheckPolicyHours {
        const val PERIOD_HOURS = 24L
    }
}
