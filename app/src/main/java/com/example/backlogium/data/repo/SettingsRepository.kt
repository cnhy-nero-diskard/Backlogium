package com.example.backlogium.data.repo

import com.example.backlogium.data.local.AutoSnapshotSettings
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.domain.LibrarySortKey
import com.example.backlogium.domain.LibrarySortPrefs
import com.example.backlogium.gamification.RuleConfig
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

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
interface SettingsRepository {
    val ruleConfig: Flow<RuleConfig>

    suspend fun setRuleConfig(config: RuleConfig)

    /**
     * The per-list Library sort selections. Unlike the achievement sort — a lens applied inside
     * one game's detail screen and discarded on the way out — the Library is returned to
     * constantly, so its ordering is remembered rather than re-picked every visit.
     */
    val librarySort: Flow<LibrarySortPrefs>

    suspend fun setFocusSort(key: LibrarySortKey)

    suspend fun setLibrarySort(key: LibrarySortKey)

    /** Automatic rolling snapshot configuration (add-backup-restore): see the Data & Backup section. */
    val autoSnapshotSettings: Flow<AutoSnapshotSettings>

    suspend fun setAutoSnapshotEnabled(enabled: Boolean)

    suspend fun setSnapshotRetentionCount(count: Int)

    suspend fun setSnapshotIntervalHours(hours: Int)
}

/** The only production implementation: a thin pass-through to Preferences DataStore. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val settings: SettingsDataStore,
) : SettingsRepository {
    override val ruleConfig: Flow<RuleConfig> = settings.ruleConfigFlow

    override suspend fun setRuleConfig(config: RuleConfig) = settings.setRuleConfig(config)

    override val librarySort: Flow<LibrarySortPrefs> = settings.librarySortFlow

    override suspend fun setFocusSort(key: LibrarySortKey) = settings.setFocusSort(key)

    override suspend fun setLibrarySort(key: LibrarySortKey) = settings.setLibrarySort(key)

    override val autoSnapshotSettings: Flow<AutoSnapshotSettings> = settings.autoSnapshotSettingsFlow

    override suspend fun setAutoSnapshotEnabled(enabled: Boolean) =
        settings.setAutoSnapshotEnabled(enabled)

    override suspend fun setSnapshotRetentionCount(count: Int) =
        settings.setSnapshotRetentionCount(count)

    override suspend fun setSnapshotIntervalHours(hours: Int) =
        settings.setSnapshotIntervalHours(hours)
}
