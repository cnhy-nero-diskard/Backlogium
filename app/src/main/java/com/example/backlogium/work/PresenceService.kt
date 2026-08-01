package com.example.backlogium.work

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.example.backlogium.data.repo.LiveStatus
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.domain.TimeProvider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that owns the 30s live-presence poll while the player is in a game
 * (enhance-now-playing). Started when a game is detected — [SteamSyncWorker]'s periodic poll, or
 * the app-foreground re-check, via [PresenceServiceStarter] — and stops itself the moment a poll
 * reports not-in-game. Not resident: this service exists only for the duration of one play session.
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
    @Inject lateinit var notifications: PresenceNotifications
    @Inject lateinit var time: TimeProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickerJob: Job? = null

    private var current: NowPlaying.InGame? = null
    private var sessionStartedAt: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            PresenceNotifications.NOTIFICATION_ID,
            notifications.initial(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        serviceScope.launch {
            // The repository's shared state may still hold its pre-poll default until a real
            // fetch runs — checked here explicitly (and awaited) so the very first thing this
            // service reacts to is real data, never that default's NotPlaying.
            handle(liveStatusRepository.checkNow())
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
     * Android 15+ caps a `dataSync` foreground service at ~6h of continuous runtime and calls
     * this instead of just killing the process. A session that long is the exception, not the
     * norm, and [com.example.backlogium.work.SteamSyncWorker]'s next periodic poll (at most 15
     * minutes later) restarts the service if the player is still in a game — so stopping cleanly
     * here self-heals rather than losing tracking permanently.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    override fun onDestroy() {
        liveStatusRepository.stopPolling()
        notifications.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handle(status: LiveStatus) {
        when (val nowPlaying = status.nowPlaying) {
            is NowPlaying.InGame -> {
                current = nowPlaying
                sessionStartedAt = status.sessionStartedAt
                notifications.update(nowPlaying.name, elapsedMinutes())
            }

            NowPlaying.NotPlaying -> {
                notifications.clear()
                stopSelf()
            }
        }
    }

    private fun elapsedMinutes(): Int {
        val startedAt = sessionStartedAt ?: return 0
        return ((time.nowMillis() - startedAt) / 60_000L).toInt().coerceAtLeast(0)
    }

    companion object {
        private const val NOTIFICATION_TICK_MS = 60_000L
    }
}
