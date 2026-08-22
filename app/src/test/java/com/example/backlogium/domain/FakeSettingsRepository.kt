package com.example.backlogium.domain

import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.LiveSessionState
import com.example.backlogium.data.repo.SettingsRepository
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory stand-in for the DataStore-backed implementation. */
internal class FakeSettingsRepository : SettingsRepository {
    private val versioned = MutableStateFlow(VersionedRuleConfig(RuleConfig(), 0L))
    val stored: RuleConfig get() = versioned.value.config
    val version: Long get() = versioned.value.version
    override suspend fun setRuleConfig(config: RuleConfig) {
        setRuleConfigAndGetVersion(config)
    }
    override val ruleConfig: Flow<RuleConfig> = versioned.map { it.config }
    override val ruleConfigWithVersion: Flow<VersionedRuleConfig> = versioned
    override suspend fun setRuleConfigAndGetVersion(config: RuleConfig): VersionedRuleConfig {
        val result = VersionedRuleConfig(config, versioned.value.version + 1L)
        versioned.value = result
        return result
    }

    // The Library sort selections share this store but are irrelevant to a rule change.
    private val sort = MutableStateFlow(LibrarySortPrefs())
    override val librarySort: Flow<LibrarySortPrefs> = sort
    override suspend fun setFocusSort(key: LibrarySortKey) {
        sort.value = sort.value.copy(focus = key)
    }

    override suspend fun setLibrarySort(key: LibrarySortKey) {
        sort.value = sort.value.copy(library = key)
    }

    override suspend fun setFocusSortDirection(direction: LibrarySortDirection) {
        sort.value = sort.value.copy(focusDirection = direction)
    }

    override suspend fun setLibrarySortDirection(direction: LibrarySortDirection) {
        sort.value = sort.value.copy(libraryDirection = direction)
    }

    override val libraryDensity: Flow<GameListDensity> = MutableStateFlow(GameListDensity.LIST)
    override suspend fun setLibraryDensity(density: GameListDensity) = Unit
    override val collectionDensity: Flow<GameListDensity> = MutableStateFlow(GameListDensity.LIST)
    override suspend fun setCollectionDensity(density: GameListDensity) = Unit

    // Auto-snapshot configuration (add-backup-restore) is irrelevant to a rule change.
    private val autoSnapshot = MutableStateFlow(AutoSnapshotSettings())
    override val autoSnapshotSettings: Flow<AutoSnapshotSettings> = autoSnapshot
    override suspend fun setAutoSnapshotEnabled(enabled: Boolean) {
        autoSnapshot.value = autoSnapshot.value.copy(enabled = enabled)
    }

    override suspend fun setSnapshotRetentionCount(count: Int) {
        autoSnapshot.value = autoSnapshot.value.copy(retentionCount = count)
    }

    override suspend fun setSnapshotIntervalHours(hours: Int) {
        autoSnapshot.value = autoSnapshot.value.copy(intervalHours = hours)
    }

    // Live now-playing session (enhance-now-playing) is irrelevant to a rule change.
    private val session = MutableStateFlow(LiveSessionState())
    override val liveSession: Flow<LiveSessionState> = session
    override suspend fun setLiveSession(appId: Long?, startedAt: Long) {
        session.value = LiveSessionState(appId, startedAt)
    }

    override val notificationPermissionRequested: Flow<Boolean> = MutableStateFlow(true)
    override suspend fun setNotificationPermissionRequested() = Unit

    private val liveMonitor = MutableStateFlow(false)
    override val liveMonitorEnabled: Flow<Boolean> = liveMonitor
    override suspend fun setLiveMonitorEnabled(enabled: Boolean) {
        liveMonitor.value = enabled
    }

    override suspend fun clearLiveSession() {
        session.value = LiveSessionState()
    }
}
