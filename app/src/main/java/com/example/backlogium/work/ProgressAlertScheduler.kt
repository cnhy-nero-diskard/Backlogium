package com.example.backlogium.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

@Singleton
class ProgressAlertScheduler @Inject constructor(
    @ApplicationContext context: Context,
    private val database: BacklogiumDatabase,
    private val settings: SettingsDataStore,
) {
    private val work = WorkManager.getInstance(context)
    fun start(scope: CoroutineScope) {
        // Periodic recovery survives a producer dying before its enqueue/invalidation observer runs.
        work.enqueueUniquePeriodicWork("progress-alert-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProgressAlertWorker>(15, TimeUnit.MINUTES).build())
        enqueue()
        scope.launch { database.progressAlertDao().observeWork().collect { if (it.isNotEmpty()) enqueue() } }
        scope.launch { settings.progressAlertHandoffs.collect { if (it.isNotEmpty()) enqueue() } }
    }
    private fun enqueue() {
        work.enqueueUniqueWork("progress-alert-delivery", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ProgressAlertWorker>().build())
    }
}
