package com.example.backlogium

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.work.PresenceServiceStarter
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Wires Hilt and configures WorkManager with the
 * [HiltWorkerFactory] so [com.example.backlogium.work.SteamSyncWorker] can be
 * constructor-injected. Enqueues the periodic Steam poll on startup, and re-checks live presence
 * every time the app is foregrounded.
 */
@HiltAndroidApp
class BacklogiumApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var liveStatusRepository: LiveStatusRepository

    @Inject
    lateinit var presenceServiceStarter: PresenceServiceStarter

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        syncScheduler.ensurePeriodicSync()
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundPresenceCheck())
    }

    /**
     * Presence is an app concern, not a screen concern: bound here it fires once per app-foreground
     * regardless of which destination happens to be showing, so a game started while the app was
     * open or backgrounded is detected on return. A screen-scoped check couldn't — Home's nav entry
     * is never popped, so its ViewModel is constructed once per process and never again.
     *
     * One request per foreground event, not a loop; [PresenceService][
     * com.example.backlogium.work.PresenceService] still owns the recurring 30s cadence, and
     * starting it while already running is a no-op that does not reset the recorded session start.
     */
    private inner class ForegroundPresenceCheck : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            scope.launch {
                if (liveStatusRepository.checkNow().nowPlaying is NowPlaying.InGame) {
                    presenceServiceStarter.start()
                }
            }
        }
    }
}
