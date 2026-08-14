package com.example.backlogium.data.backup

import android.content.Context
import android.net.Uri
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of reading a candidate backup file/snapshot, before any merge happens. */
sealed interface ParsedBackup {
    data class Valid(val file: BackupFile) : ParsedBackup

    /** Not valid JSON, or an unsupported/unrecognized [BackupFile.formatVersion]. */
    data object InvalidFormat : ParsedBackup
}

/**
 * Single entry point for every backup/restore trigger (design.md decision 4): manual
 * export/import via SAF, the automatic rolling snapshot, and restoring a listed snapshot all go
 * through this repository, and every import/restore shares the one [BackupMergeEngine].
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val exportMapper: BackupExportMapper,
    private val mergeEngine: BackupMergeEngine,
    private val snapshotStore: SnapshotStore,
    private val settings: SettingsDataStore,
    private val credentials: CredentialsRepository,
    private val time: TimeProvider,
    private val syncScheduler: SyncScheduler,
) {
    /** Export to a user-chosen SAF destination. Independent of the auto-snapshot toggle. */
    suspend fun exportTo(uri: Uri) {
        val file = exportMapper.buildExport()
        val bytes = json.encodeToString(BackupFile.serializer(), file).toByteArray()
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to open $uri for writing")
    }

    /** Read and validate a SAF-picked file without modifying any data. */
    suspend fun parseFrom(uri: Uri): ParsedBackup {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().decodeToString() }
            ?: return ParsedBackup.InvalidFormat
        return parseText(text)
    }

    /** Read and validate a retained automatic snapshot by its [SnapshotMeta.fileName]. */
    fun parseSnapshot(fileName: String): ParsedBackup =
        snapshotStore.read(fileName)?.let { ParsedBackup.Valid(it) } ?: ParsedBackup.InvalidFormat

    private fun parseText(text: String): ParsedBackup {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrNull() ?: return ParsedBackup.InvalidFormat
        if (file.formatVersion != BackupFile.CURRENT_FORMAT_VERSION) return ParsedBackup.InvalidFormat
        return ParsedBackup.Valid(file)
    }

    /** The signed-in account's SteamID64, or null while unconfigured. */
    suspend fun currentSteamId(): String? = credentials.currentCredentials()?.steamId

    /** Whether [file]'s recorded identity differs from the signed-in account. Warn, don't block. */
    suspend fun isMismatched(file: BackupFile): Boolean {
        val current = currentSteamId() ?: return false
        return file.identity.steamId64.isNotBlank() && file.identity.steamId64 != current
    }

    /** Merge a validated file into the local database — the one import/restore code path. */
    suspend fun importBackup(file: BackupFile) {
        val rules = settings.ruleConfigWithVersionFlow.first()
        mergeEngine.merge(file, rules.config, rules.version)
        // Restore supplies only unlocked achievements and no per-game metadata, so a restored
        // library reads as entirely unfetched. Kick off a deferred reconciliation pass to
        // converge the cold tier as soon as conditions allow rather than waiting a week.
        syncScheduler.reconcileNow(force = false)
    }

    /** Retained automatic snapshots, most recent first. */
    fun listSnapshots(): List<SnapshotMeta> = snapshotStore.list()

    /**
     * Write an automatic snapshot if enabled and due (design.md decision 4). Hooked into
     * [com.example.backlogium.work.SteamSyncWorker]'s success path, not a separate scheduler —
     * the throttle/retention checks are cheap guards at the point of writing.
     */
    suspend fun writeAutoSnapshotIfDue() {
        val config = settings.autoSnapshotSettingsFlow.first()
        if (!config.enabled) return
        val now = time.nowMillis()
        val newest = snapshotStore.newestWrittenAtMillis()
        val intervalMillis = config.intervalHours * 3_600_000L
        if (newest != null && now - newest < intervalMillis) return
        snapshotStore.write(exportMapper.buildExport(), now)
        snapshotStore.enforceRetention(config.retentionCount)
    }
}
