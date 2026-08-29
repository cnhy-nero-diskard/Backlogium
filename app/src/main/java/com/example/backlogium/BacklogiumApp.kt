package com.example.backlogium

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import coil.ImageLoader
import coil.ImageLoaderFactory
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.data.repo.LiveStatus
import com.example.backlogium.data.repo.LiveStatusRepository
import com.example.backlogium.data.repo.NowPlaying
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.data.backup.SnapshotStore
import com.example.backlogium.data.diagnostics.DiagnosticHistoryMigration
import com.example.backlogium.data.repo.AccountChangeCoordinator
import com.example.backlogium.data.steamassets.SteamAssetInterceptor
import com.example.backlogium.di.ApplicationScope
import com.example.backlogium.domain.DailyProgressBackfillUseCase
import com.example.backlogium.domain.PendingImportRecomputeUseCase
import com.example.backlogium.work.PostPlaySyncScheduler
import com.example.backlogium.work.PresenceServiceStarter
import com.example.backlogium.work.SyncScheduler
import com.example.backlogium.work.UpdateScheduler
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
    startPresence: suspend () -> Unit,
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
class BacklogiumApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var steamAssetInterceptor: SteamAssetInterceptor

    @Inject
    lateinit var updateScheduler: UpdateScheduler

    @Inject
    lateinit var liveStatusRepository: LiveStatusRepository

    @Inject
    lateinit var presenceServiceStarter: PresenceServiceStarter

    @Inject
    lateinit var postPlaySyncScheduler: PostPlaySyncScheduler

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var dailyProgressBackfill: DailyProgressBackfillUseCase

    @Inject
    lateinit var pendingImportRecompute: PendingImportRecomputeUseCase

    @Inject
    lateinit var snapshotStore: SnapshotStore

    @Inject
    lateinit var diagnosticHistoryMigration: DiagnosticHistoryMigration

    @Inject
    lateinit var accountChangeCoordinator: AccountChangeCoordinator

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(steamAssetInterceptor) }
        .build()

    override fun onCreate() {
        super.onCreate()
        // Narrative debugging only — structured records (SyncRunRecorder, PresenceDecisionRecorder)
        // are the durable diagnostic surface and are active in release builds regardless. No tree
        // is planted here in release, so Timber calls are no-ops and nothing reaches logcat.
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // Scheduling is local and non-blocking; the worker performs discovery only in release
        // builds and only when WorkManager has a connected network.
        if (!BuildConfig.DEBUG) {
            updateScheduler.ensurePeriodicUpdateCheck()
        }
        // Complete storage migrations before scheduling work that could list or write snapshots.
        // Both migrations are idempotent and leave their source data in place when a copy or
        // database operation fails, so the next process start can retry safely.
        snapshotStore.migrateLegacySnapshots()
        scope.launch {
            val ready = runCatching { accountChangeCoordinator.resumeIfPending() }
                .onFailure { Timber.e(it, "Account-change recovery failed; sync remains unscheduled") }
                .isSuccess
            if (!ready) return@launch

            // Start after account recovery so a durable session-end handoff cannot schedule work
            // against an account reset that is still incomplete. The outbox replays anything
            // recorded before process death.
            postPlaySyncScheduler.observeSessionEnds()
            runCatching { diagnosticHistoryMigration.purgeLegacyIdentifiersIfNeeded() }
                .onFailure { Timber.e(it, "Diagnostic history migration failed") }
            correctHistoricalDailyTotals()
            resolvePendingImportRecompute()
            syncScheduler.ensurePeriodicSync()
            syncScheduler.ensurePeriodicReconciliation()
            syncScheduler.ensurePeriodicWishlistSampling()
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundPresenceCheck())
    }

    /**
     * Finish a backup restore whose process ended after the merge committed but before the
     * follow-up recompute ran (auditfix-backup-integrity). A no-op on every launch where no merge
     * is mid-flight, which is the common case.
     */
    private fun resolvePendingImportRecompute() {
        scope.launch {
            runCatching { pendingImportRecompute() }
                .onFailure { Timber.e(it, "Pending import recompute failed") }
        }
    }

    /**
     * One-time correction of per-day totals recorded under the superseded poll-time attribution
     * (auditfix-day-attribution Decision 7). Guarded by a persisted flag, so this is a no-op on
     * every launch after the first and costs a fresh install nothing.
     *
     * On start-up rather than in the sync worker: the rows most likely to be skewed belong to users
     * who are offline or whose credentials have lapsed, and those users' syncs may never run. It
     * reads only local data, so it needs no network either.
     */
    private fun correctHistoricalDailyTotals() {
        scope.launch {
            runCatching { dailyProgressBackfill() }
                .onSuccess { corrections ->
                    when {
                        corrections == null -> Timber.d("Daily-totals correction already applied")
                        corrections.isEmpty() -> Timber.d("Daily totals already agree with sessions")
                        else -> Timber.i("Corrected %d daily totals from sessions", corrections.size)
                    }
                }
                // Never let a failed correction take down app start-up: the stored totals stay as
                // they were, the guard stays unset, and the next launch retries.
                .onFailure { Timber.e(it, "Daily-totals correction failed") }
        }
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
                    presenceServiceStarter.startFromForeground(trigger = "foreground_monitor")
                }
                val detected = detectForegroundPresence(
                    checkNow = liveStatusRepository::checkNow,
                    startPresence = {
                        presenceServiceStarter.startFromForeground(trigger = "foreground_detection")
                    },
                )
                if (!detected && !settings.liveMonitorEnabled.first()) {
                    settings.setLiveMonitoringAvailability(
                        PresenceMonitoringAvailability.AVAILABLE,
                    )
                }
            }
        }

        override fun onStop(owner: LifecycleOwner) {
            detectionJob?.cancel()
            detectionJob = null
        }
    }
}
