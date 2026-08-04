package com.example.backlogium

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.example.backlogium.data.repo.LiveStatus
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.work.PresenceServiceStarter
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

internal const val FOREGROUND_PRESENCE_ATTEMPTS = 4
internal const val FOREGROUND_PRESENCE_RETRY_MS = 5_000L

/**
 * Steam can briefly keep returning the pre-launch presence after the app is foregrounded. Give
 * that eventually-consistent signal a small, bounded window to settle instead of making one
 * request and then remaining idle until the next sync or process restart.
 */
internal suspend fun detectForegroundPresence(
    checkNow: suspend () -> LiveStatus,
    startPresence: () -> Unit,
    attempts: Int = FOREGROUND_PRESENCE_ATTEMPTS,
    retryDelayMillis: Long = FOREGROUND_PRESENCE_RETRY_MS,
    delayBeforeRetry: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    require(attempts > 0) { "attempts must be positive" }
    repeat(attempts) { attempt ->
        val status = checkNow()
        // LiveStatusRepository currently treats a cancelled network call like any other failed
        // observation. Re-assert cancellation here before acting on the retained status.
        currentCoroutineContext().ensureActive()
        if (status.nowPlaying is NowPlaying.InGame) {
            startPresence()
            return true
        }
        if (attempt < attempts - 1) delayBeforeRetry(retryDelayMillis)
    }
    return false
}

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
    lateinit var settings: SettingsRepository

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Narrative debugging only — structured records (SyncRunRecorder, PresenceDecisionRecorder)
        // are the durable diagnostic surface and are active in release builds regardless. No tree
        // is planted here in release, so Timber calls are no-ops and nothing reaches logcat.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        syncScheduler.ensurePeriodicSync()
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundPresenceCheck())
    }

    /**
     * Presence is an app concern, not a screen concern: bound here it fires once per app-foreground
     * regardless of which destination happens to be showing, so a game started while the app was
     * open or backgrounded is detected on return. A screen-scoped check couldn't — Home's nav entry
     * is never popped, so its ViewModel is constructed once per process and never again.
     *
     * A short bounded retry window covers Steam presence propagation after a game launch;
     * [PresenceService][com.example.backlogium.work.PresenceService] still owns the recurring 30s
     * cadence once a game is detected. Starting it while already running is a no-op that does not
     * reset the recorded session start.
     */
    private inner class ForegroundPresenceCheck : DefaultLifecycleObserver {
        private var detectionJob: Job? = null

        override fun onStart(owner: LifecycleOwner) {
            detectionJob?.cancel()
            detectionJob = scope.launch {
                // Android may stop a long-running monitor. Re-start it only from this visible
                // foreground interaction, never from a worker or boot receiver.
                if (settings.liveMonitorEnabled.first()) {
                    presenceServiceStarter.start()
                }
                detectForegroundPresence(
                    checkNow = liveStatusRepository::checkNow,
                    startPresence = presenceServiceStarter::start,
                )
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            detectionJob?.cancel()
            detectionJob = null
        }
    }
}
