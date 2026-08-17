package com.example.backlogium.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.backlogium.data.diagnostics.PresenceDecisionRecorder
import com.example.backlogium.data.diagnostics.PresenceOutcome
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.data.repo.LiveStatus
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.domain.TimeProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that owns the 30s live-presence poll while the player is in a game
 * (enhance-now-playing). Started by a foreground app interaction via [PresenceServiceStarter] and
 * stops itself the moment a poll reports not-in-game. A background [SteamSyncWorker] can detect a
 * game but records that monitoring must wait for the next foreground visit. Not resident: this
 * service exists only for the duration of one play session.
 *
 * Home and the Library remain plain observers of [LiveStatusRepository.liveStatus] whether or not
 * this service happens to be running — degraded (no live updates) but not broken when it is not.
 *
 * All coordination runs on a single ([Dispatchers.Main.immediate]) coroutine scope, so the ticker
 * and the poll-driven updates below never race over the plain `current`/`sessionStartedAt` fields.
 */
@AndroidEntryPoint
class PresenceService : Service() {

    @Inject lateinit var liveStatusRepository: LiveStatusRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var notifications: PresenceNotifications
    @Inject lateinit var time: TimeProvider
    @Inject lateinit var diagnostics: PresenceDecisionRecorder
    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    private var current: NowPlaying.InGame? = null
    private var sessionStartedAt: Long? = null
    private var liveMonitorEnabled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(
                this,
                PresenceNotifications.NOTIFICATION_ID,
                notifications.initial(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            isRunning = true
        } catch (error: Exception) {
            recordLifecycleOutcome(
                outcome = PresenceOutcome.START_FAILED,
                availability = PresenceMonitoringAvailability.START_FAILED,
            )
            throw error
        }

        serviceScope.launch {
            // Read the persisted preference before the first observation: a monitor started from
            // Settings must not see its initial NotPlaying result and stop before the preference
            // collector gets its first emission.
            liveMonitorEnabled = settings.liveMonitorEnabled.first()
            var observationStarted = false
            launch {
                settings.liveMonitorEnabled.collect { enabled ->
                    val changed = enabled != liveMonitorEnabled
                    liveMonitorEnabled = enabled
                    // Turning monitoring off only stops an idle service. An already observed game
                    // remains tracked until Steam reports that it has ended.
                    if (changed && observationStarted) {
                        handle(liveStatusRepository.liveStatus.value)
                    }
                }
            }
            // The repository's shared state may still hold its pre-poll default until a real
            // fetch runs — checked here explicitly (and awaited) so the very first thing this
            // service reacts to is real data, never that default's NotPlaying.
            handle(liveStatusRepository.checkNow())
            observationStarted = true
            liveStatusRepository.startPolling()
            liveStatusRepository.liveStatus.collect { handle(it) }
        }

        tickerJob = serviceScope.launch {
            // Elapsed-time refresh on its own cadence, independent of the 30s poll.
            while (isActive) {
                delay(NOTIFICATION_TICK_MS)
                current?.let { notifications.update(it.name, elapsedMinutes()) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Android 15+ gives `dataSync` foreground services a cumulative six-hour budget in a rolling
     * 24-hour window and calls this when the budget is reached. Stopping cleanly is required, and
     * the next background worker is not a recovery path: Android may reject another `dataSync`
     * start until the user brings the app to the foreground. The app-foreground observer owns the
     * only unattended-safe recovery attempt and records the unavailable state until then.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        isRunning = false
        recordLifecycleOutcome(
            outcome = PresenceOutcome.RUNTIME_BUDGET_REACHED,
            availability = PresenceMonitoringAvailability.RUNTIME_BUDGET_EXHAUSTED,
        )
        stopSelf(startId)
    }

    override fun onDestroy() {
        isRunning = false
        liveStatusRepository.stopPolling()
        notifications.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handle(status: LiveStatus) {
        when (presenceServiceMode(liveMonitorEnabled, status.nowPlaying)) {
            PresenceServiceMode.PLAYING -> {
                val nowPlaying = status.nowPlaying as NowPlaying.InGame
                current = nowPlaying
                sessionStartedAt = status.sessionStartedAt
                notifications.update(nowPlaying.name, elapsedMinutes())
            }

            PresenceServiceMode.MONITORING -> {
                current = null
                sessionStartedAt = null
                notifications.monitoring()
            }

            PresenceServiceMode.STOP -> {
                current = null
                sessionStartedAt = null
                notifications.clear()
                stopSelf()
            }
        }
    }

    private fun elapsedMinutes(): Int {
        val startedAt = sessionStartedAt ?: return 0
        return ((time.nowMillis() - startedAt) / 60_000L).toInt().coerceAtLeast(0)
    }

    private fun recordLifecycleOutcome(
        outcome: PresenceOutcome,
        availability: PresenceMonitoringAvailability,
    ) {
        applicationScope.launch {
            runCatching { settings.setLiveMonitoringAvailability(availability) }
            diagnostics.record("service", outcome)
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val NOTIFICATION_TICK_MS = 60_000L
    }
}
