package com.example.backlogium.data.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.domain.DerivedStateWriteCoordinator
import com.example.backlogium.domain.TimeProvider
import com.example.backlogium.work.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of reading a candidate backup file/snapshot, before any merge happens. */
sealed interface ParsedBackup {
    data class Valid(val file: BackupFile) : ParsedBackup

    /** Not valid JSON, or an unsupported/unrecognized [BackupFile.formatVersion]. */
    data object InvalidFormat : ParsedBackup

    /** Decoded but semantically invalid — see [BackupValidator] for what was checked. */
    data class Invalid(val problems: List<BackupValidationProblem>) : ParsedBackup

    /** Refused before the payload was materialized (tasks.md 4). */
    data class TooLarge(val limitBytes: Long, val actualBytes: Long) : ParsedBackup
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
    private val derivedStateWrites: DerivedStateWriteCoordinator,
) {
    /** Export to a user-chosen SAF destination. Independent of the auto-snapshot toggle. */
    suspend fun exportTo(uri: Uri) {
        val file = exportMapper.buildExport()
        val bytes = json.encodeToString(BackupFile.serializer(), file).toByteArray()
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: error("Unable to open $uri for writing")
    }

    /**
     * Read and validate a SAF-picked file without modifying any data. The reported size is
     * checked first, but a resolver-reported size is metadata, not a guarantee — the read itself
     * is bounded so an absent or understated size cannot defeat the limit (tasks.md 4.2).
     */
    suspend fun parseFrom(uri: Uri): ParsedBackup {
        val reportedSize = context.contentResolver.querySize(uri)
        if (reportedSize != null && reportedSize > MAX_IMPORT_BYTES) {
            return ParsedBackup.TooLarge(MAX_IMPORT_BYTES, reportedSize)
        }
        val bytes = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytesUpTo(MAX_IMPORT_BYTES) }
            ?: return ParsedBackup.InvalidFormat
        if (bytes.size.toLong() > MAX_IMPORT_BYTES) {
            return ParsedBackup.TooLarge(MAX_IMPORT_BYTES, bytes.size.toLong())
        }
        return parseText(bytes.decodeToString())
    }

    /** Read and validate a retained automatic snapshot by its [SnapshotMeta.fileName]. */
    fun parseSnapshot(fileName: String): ParsedBackup {
        val file = snapshotStore.read(fileName) ?: return ParsedBackup.InvalidFormat
        return file.toParsedResult()
    }

    private fun parseText(text: String): ParsedBackup {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), text) }
            .getOrNull() ?: return ParsedBackup.InvalidFormat
        if (file.formatVersion != BackupFile.CURRENT_FORMAT_VERSION) return ParsedBackup.InvalidFormat
        return file.toParsedResult()
    }

    private fun BackupFile.toParsedResult(): ParsedBackup =
        when (val result = BackupValidator.validate(this)) {
            is BackupValidationResult.Valid -> ParsedBackup.Valid(result.file)
            is BackupValidationResult.Invalid -> ParsedBackup.Invalid(result.problems)
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
        derivedStateWrites.withLock {
            val rules = settings.ruleConfigWithVersionFlow.first()
            mergeEngine.mergeWithLockHeld(file, rules.config, rules.version)
        }
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

    companion object {
        /**
         * Justified worst case (tasks.md 4.1): 5,000 owned games (a very large Steam library),
         * each with up to 100 achievements averaging ~300 bytes as JSON (apiName, displayName,
         * timestamps, snapshot) and a decade of daily sessions (~3,650 rows) averaging ~120
         * bytes — roughly 5,000*100*300 + 5,000*3,650*120/5,000 ≈ 150M + 2.2M, so achievements
         * dominate at ~150 MB. Rounded up and given headroom for JSON indentation and every
         * other table: 256 MB.
         */
        const val MAX_IMPORT_BYTES: Long = 256L * 1024 * 1024
    }
}

internal fun android.content.ContentResolver.querySize(uri: Uri): Long? =
    query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
            cursor.getLong(sizeIndex)
        } else {
            null
        }
    }

/**
 * Reads in bounded chunks and stops as soon as the running total exceeds [maxBytes] — at most one
 * chunk past the limit, never an unbounded payload — so a reported size that is absent or
 * understates the actual size cannot defeat it (tasks.md 4.2).
 */
internal fun InputStream.readBytesUpTo(maxBytes: Long): ByteArray {
    val out = ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (total <= maxBytes) {
        val read = read(chunk)
        if (read < 0) break
        out.write(chunk, 0, read)
        total += read
    }
    return out.toByteArray()
}
