package com.example.backlogium

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Wires Hilt and configures WorkManager with the
 * [HiltWorkerFactory] so [com.example.backlogium.work.SteamSyncWorker] can be
 * constructor-injected. Enqueues the periodic Steam poll on startup.
 */
@HiltAndroidApp
class BacklogiumApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.ensurePeriodicSync()
    }
}
