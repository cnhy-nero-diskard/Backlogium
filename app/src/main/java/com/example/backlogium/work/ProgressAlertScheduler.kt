package com.example.backlogium.work

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    @ApplicationContext private val context: Context,
    private val database: BacklogiumDatabase,
    private val settings: SettingsDataStore,
    private val marker: com.example.backlogium.data.credentials.AccountChangeMarkerStore,
    private val time: com.example.backlogium.domain.TimeProvider,
) {
    private val work = WorkManager.getInstance(context)
    fun start(scope: CoroutineScope) {
        // Periodic recovery survives a producer dying before its enqueue/invalidation observer runs.
        work.enqueueUniquePeriodicWork("progress-alert-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ProgressAlertWorker>(15, TimeUnit.MINUTES).build())
        enqueue()
        scheduleNextRecap()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                enqueue()
                scheduleNextRecap()
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch {
            combine(settings.progressAlertPreferences.map { it[com.example.backlogium.data.notifications.ProgressCategory.RECAP] }
                .distinctUntilChanged(), marker.activeGeneration) { enabled, generation -> enabled to generation }
                .collect {
                    enqueue()
                    scheduleNextRecap()
                }
        }
        scope.launch { database.progressAlertDao().observeWork().collect { if (it.isNotEmpty()) enqueue() } }
        scope.launch { settings.progressAlertHandoffs.collect { if (it.isNotEmpty()) enqueue() } }
    }
    fun scheduleNextRecap() {
        work.enqueueUniqueWork("progress-alert-next-day", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ProgressAlertWorker>()
                .setInitialDelay(com.example.backlogium.data.notifications.nextRecapDelay(time.nowMillis(), time.zone()), TimeUnit.MILLISECONDS)
                .build())
    }

    private fun enqueue() {
        work.enqueueUniqueWork("progress-alert-delivery", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<ProgressAlertWorker>().build())
    }
}
