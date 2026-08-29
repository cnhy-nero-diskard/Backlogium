package com.example.backlogium.data.repo

import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.local.PendingSessionEnd
import com.example.backlogium.data.local.PresenceMonitoringAvailability
import com.example.backlogium.data.local.AcquiredGamesAnnouncement
import com.example.backlogium.data.local.SharedGameAnnouncement
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.domain.GameListDensity
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortDirection
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.domain.SmartCollectionVisibility
import com.example.backlogium.domain.VersionedRuleConfig
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable handoff for a session end. The event stays pending until its WorkManager schedule has
 * been accepted; replaying an already-accepted event is safe because scheduling replaces its
 * per-game generation.
 */
interface SessionEndOutbox {
    val pendingSessionEnds: Flow<List<PlaySessionEnd>>

    suspend fun recordSessionEnd(
        sessionEnd: PlaySessionEnd,
        nextLiveSession: LiveSessionState,
    )

    suspend fun acknowledgeSessionEnd(sessionEnd: PlaySessionEnd)
}

/**
 * Read/write access to app settings: the tunable gamification [RuleConfig] and the per-list
 * Library sort selections.
 *
 * Exists so consumers above `data/` depend on a repository rather than on DataStore: settings
 * are the one piece of state a cloud-backed build would most plausibly move, and [RuleConfig]
 * is already a plain type from the pure `:gamification` module, so the contract survives the
 * storage swap unchanged.
 *
 * Storage only, deliberately: a rule *change* is retroactive and has to be paired with a
 * recompute, which is [com.example.backlogium.domain.UpdateRuleConfigUseCase]'s job. Injecting
 * the gamification updater here would undo the cloud-seam boundary and make a storage class
 * depend on the domain layer.
 *
 * An interface rather than a class (mirroring [com.example.backlogium.domain.TimeProvider]) so
 * callers can be tested on the JVM without a `Context`-scoped DataStore.
 */
interface SettingsRepository : SessionEndOutbox {
    override val pendingSessionEnds: Flow<List<PlaySessionEnd>>
        get() = flowOf(emptyList())

    override suspend fun recordSessionEnd(
        sessionEnd: PlaySessionEnd,
        nextLiveSession: LiveSessionState,
    ) {
        if (nextLiveSession.startedAt == null) {
            clearLiveSession()
        } else {
            setLiveSession(nextLiveSession.appId, nextLiveSession.startedAt)
        }
    }

    override suspend fun acknowledgeSessionEnd(sessionEnd: PlaySessionEnd) = Unit

    val ruleConfig: Flow<RuleConfig>

    suspend fun setRuleConfig(config: RuleConfig)

    /** Rules and their DataStore provenance version, read as one preference snapshot. */
    val ruleConfigWithVersion: Flow<VersionedRuleConfig>
        get() = ruleConfig.map { VersionedRuleConfig(it, 0L) }

    suspend fun readRuleConfigWithVersion(): VersionedRuleConfig = ruleConfigWithVersion.first()

    /** Production storage overrides this to return the version from the same edit transaction. */
    suspend fun setRuleConfigAndGetVersion(config: RuleConfig): VersionedRuleConfig {
        setRuleConfig(config)
        return readRuleConfigWithVersion()
    }

    /**
     * The per-list Library sort selections. Unlike the achievement sort — a lens applied inside
     * one game's detail screen and discarded on the way out — the Library is returned to
     * constantly, so its ordering is remembered rather than re-picked every visit.
     */
    val librarySort: Flow<LibrarySortPrefs>

    suspend fun setFocusSort(key: LibrarySortKey)

    suspend fun setLibrarySort(key: LibrarySortKey)

    suspend fun setFocusSortDirection(direction: LibrarySortDirection)

    suspend fun setLibrarySortDirection(direction: LibrarySortDirection)

    /** Presentation preferences for the Library and collection overview, independently stored. */
    val libraryDensity: Flow<GameListDensity>

    suspend fun setLibraryDensity(density: GameListDensity)

    val collectionDensity: Flow<GameListDensity>

    suspend fun setCollectionDensity(density: GameListDensity)

    /** Per-list visibility for derived collections; old implementations default to all visible. */
    val smartCollectionVisibility: Flow<SmartCollectionVisibility>
        get() = flowOf(SmartCollectionVisibility())

    suspend fun setSmartCollectionVisibility(visibility: SmartCollectionVisibility) = Unit

    /** Automatic rolling snapshot configuration (add-backup-restore): see the Data & Backup section. */
    val autoSnapshotSettings: Flow<AutoSnapshotSettings>

    suspend fun setAutoSnapshotEnabled(enabled: Boolean)

    suspend fun setSnapshotRetentionCount(count: Int)

    suspend fun setSnapshotIntervalHours(hours: Int)

    /** The persisted live now-playing session (enhance-now-playing) — see [LiveSessionState]. */
    val liveSession: Flow<LiveSessionState>

    suspend fun setLiveSession(appId: Long?, startedAt: Long)

    suspend fun clearLiveSession()

    /**
     * Whether the runtime notification permission has already been requested once
     * (fix-live-status-detection), so the app asks at most once rather than on every launch.
     */
    val notificationPermissionRequested: Flow<Boolean>

    suspend fun setNotificationPermissionRequested()

    /** Explicit opt-in to keep the foreground presence service polling before a game is detected. */
    val liveMonitorEnabled: Flow<Boolean>

    suspend fun setLiveMonitorEnabled(enabled: Boolean)

    /** Durable availability state for the opt-in monitor; old test doubles default to available. */
    /**
     * The newly-acquired-games announcement written by the most recent acquiring poll. Read-only
     * apart from the dismissal: only a poll may create one, so a restore cannot manufacture an
     * announcement and neither can a UI surface.
     */
    val acquiredGames: Flow<AcquiredGamesAnnouncement>

    /** Dismiss the current announcement. Per-batch: a later acquisition clears the flag again. */
    suspend fun setAcquiredGamesDismissed()

    /**
     * Durable foreground cue when automatic family-shared admission could not post a
     * notification — the oldest undismissed admission, when more than one is queued.
     */
    val sharedGameAnnouncement: Flow<SharedGameAnnouncement?>
        get() = flowOf(null)

    /** Dismiss [appId]'s durable admission cue; any other queued admission's cue stays queued. */
    suspend fun clearSharedGameAnnouncement(appId: Long) = Unit

    val liveMonitoringAvailability: Flow<PresenceMonitoringAvailability>
        get() = flowOf(PresenceMonitoringAvailability.AVAILABLE)

    suspend fun setLiveMonitoringAvailability(availability: PresenceMonitoringAvailability) = Unit
}

/** The only production implementation: a thin pass-through to Preferences DataStore. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val settings: SettingsDataStore,
) : SettingsRepository {
    override val ruleConfig: Flow<RuleConfig> = settings.ruleConfigFlow

    override suspend fun setRuleConfig(config: RuleConfig) = settings.setRuleConfig(config)

    override val ruleConfigWithVersion: Flow<VersionedRuleConfig> = settings.ruleConfigWithVersionFlow

    override suspend fun setRuleConfigAndGetVersion(config: RuleConfig): VersionedRuleConfig =
        settings.setRuleConfigAndGetVersion(config)

    override val librarySort: Flow<LibrarySortPrefs> = settings.librarySortFlow

    override suspend fun setFocusSort(key: LibrarySortKey) = settings.setFocusSort(key)

    override suspend fun setLibrarySort(key: LibrarySortKey) = settings.setLibrarySort(key)

    override suspend fun setFocusSortDirection(direction: LibrarySortDirection) =
        settings.setFocusSortDirection(direction)

    override suspend fun setLibrarySortDirection(direction: LibrarySortDirection) =
        settings.setLibrarySortDirection(direction)

    override val libraryDensity: Flow<GameListDensity> = settings.libraryDensityFlow

    override suspend fun setLibraryDensity(density: GameListDensity) =
        settings.setLibraryDensity(density)

    override val collectionDensity: Flow<GameListDensity> = settings.collectionDensityFlow

    override suspend fun setCollectionDensity(density: GameListDensity) =
        settings.setCollectionDensity(density)

    override val smartCollectionVisibility: Flow<SmartCollectionVisibility> =
        settings.smartCollectionVisibilityFlow

    override suspend fun setSmartCollectionVisibility(visibility: SmartCollectionVisibility) =
        settings.setSmartCollectionVisibility(visibility)

    override val autoSnapshotSettings: Flow<AutoSnapshotSettings> = settings.autoSnapshotSettingsFlow

    override suspend fun setAutoSnapshotEnabled(enabled: Boolean) =
        settings.setAutoSnapshotEnabled(enabled)

    override suspend fun setSnapshotRetentionCount(count: Int) =
        settings.setSnapshotRetentionCount(count)

    override suspend fun setSnapshotIntervalHours(hours: Int) =
        settings.setSnapshotIntervalHours(hours)

    override val liveSession: Flow<LiveSessionState> = settings.liveSessionFlow

    override suspend fun setLiveSession(appId: Long?, startedAt: Long) =
        settings.setLiveSession(appId, startedAt)

    override suspend fun clearLiveSession() = settings.clearLiveSession()

    override val pendingSessionEnds: Flow<List<PlaySessionEnd>> =
        settings.pendingSessionEndsFlow.map { entries ->
            entries.map { PlaySessionEnd(it.appId, it.endedAt, it.steamId) }
        }

    override suspend fun recordSessionEnd(
        sessionEnd: PlaySessionEnd,
        nextLiveSession: LiveSessionState,
    ) = settings.recordSessionEnd(
        PendingSessionEnd(sessionEnd.appId, sessionEnd.endedAt, sessionEnd.steamId),
        nextLiveSession,
    )

    override suspend fun acknowledgeSessionEnd(sessionEnd: PlaySessionEnd) =
        settings.acknowledgeSessionEnd(
            PendingSessionEnd(sessionEnd.appId, sessionEnd.endedAt, sessionEnd.steamId),
        )

    override val notificationPermissionRequested: Flow<Boolean> =
        settings.notificationPermissionRequestedFlow

    override suspend fun setNotificationPermissionRequested() =
        settings.setNotificationPermissionRequested()

    override val liveMonitorEnabled: Flow<Boolean> = settings.liveMonitorEnabledFlow

    override suspend fun setLiveMonitorEnabled(enabled: Boolean) =
        settings.setLiveMonitorEnabled(enabled)

    override val acquiredGames: Flow<AcquiredGamesAnnouncement> = settings.acquiredGamesFlow

    override suspend fun setAcquiredGamesDismissed() = settings.setAcquiredGamesDismissed()

    override val sharedGameAnnouncement: Flow<SharedGameAnnouncement?> =
        settings.sharedGameAnnouncementFlow

    override suspend fun clearSharedGameAnnouncement(appId: Long) =
        settings.clearSharedGameAnnouncement(appId)

    override val liveMonitoringAvailability: Flow<PresenceMonitoringAvailability> =
        settings.liveMonitoringAvailabilityFlow

    override suspend fun setLiveMonitoringAvailability(availability: PresenceMonitoringAvailability) =
        settings.setLiveMonitoringAvailability(availability)
}
