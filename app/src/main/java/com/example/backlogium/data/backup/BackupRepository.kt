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
import java.io.InputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
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
     *
     * Decoding streams straight off the [java.io.InputStream] rather than materializing the file.
     * Buffering it would have cost a `ByteArrayOutputStream`, its `toByteArray()` copy, and a
     * UTF-16 `String` roughly twice the file's size — several multiples of the limit in peak heap
     * before the object graph even exists, which made the cap a file-size bound rather than a
     * memory-safety one.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun parseFrom(uri: Uri): ParsedBackup {
        val reportedSize = context.contentResolver.querySize(uri)
        if (reportedSize != null && reportedSize > MAX_IMPORT_BYTES) {
            return ParsedBackup.TooLarge(MAX_IMPORT_BYTES, reportedSize)
        }
        val stream = context.contentResolver.openInputStream(uri) ?: return ParsedBackup.InvalidFormat
        val decoded = stream.use { raw ->
            val bounded = BoundedInputStream(raw, MAX_IMPORT_BYTES)
            runCatching { json.decodeFromStream(BackupFile.serializer(), bounded) }
        }
        val file = decoded.getOrElse { failure ->
            // The bound is enforced mid-read, so an oversized file surfaces here rather than as a
            // size check on an already-materialized payload.
            if (failure is StreamLimitExceededException) {
                return ParsedBackup.TooLarge(MAX_IMPORT_BYTES, failure.bytesReadAtLeast)
            }
            return ParsedBackup.InvalidFormat
        }
        if (file.formatVersion != BackupFile.CURRENT_FORMAT_VERSION) return ParsedBackup.InvalidFormat
        return file.toParsedResult()
    }

    /** Read and validate a retained automatic snapshot by its [SnapshotMeta.fileName]. */
    fun parseSnapshot(fileName: String): ParsedBackup {
        val file = snapshotStore.read(fileName) ?: return ParsedBackup.InvalidFormat
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
        return isCrossAccountBackup(current, file.identity.steamId64)
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
         * Justified worst case (tasks.md 4.1), for a deliberately extreme account:
         *
         * - 5,000 owned games × ~80 B  ≈ 0.4 MB
         * - 20,000 unlocked achievements × ~200 B ≈ 4.0 MB
         * - 10 years of sessions at 5/day (18,250) × ~130 B ≈ 2.4 MB
         * - 3,650 daily-progress rows × ~90 B ≈ 0.3 MB
         * - 5,000 HLTB rows × ~150 B ≈ 0.8 MB
         * - computed rollups (xpPerGame + xpTimeline) ≈ 0.6 MB
         *
         * ≈ 8.5 MB total. A 4× allowance for whitespace, longer names, and growth gives 32 MB.
         *
         * This is deliberately far below the previous 256 MB. Even streaming the parse, the
         * decoded object graph is a multiple of the wire size, and Android heaps are small — a
         * limit that only bounds the file while permitting an OOM during decode is not a limit.
         */
        const val MAX_IMPORT_BYTES: Long = 32L * 1024 * 1024
    }
}

/** Pure cross-account warning predicate; importing still remains an explicit, permitted merge. */
internal fun isCrossAccountBackup(currentSteamId: String?, backupSteamId: String): Boolean =
    !currentSteamId.isNullOrBlank() && backupSteamId.isNotBlank() && currentSteamId != backupSteamId

/** Raised by [BoundedInputStream] when a read would carry it past its limit. */
internal class StreamLimitExceededException(val bytesReadAtLeast: Long) :
    java.io.IOException("backup exceeds the maximum supported size")

/**
 * Fails the read as soon as the stream passes [maxBytes], so the limit holds no matter what the
 * content resolver reported — an absent or understated size cannot smuggle a larger payload past
 * it, and nothing beyond the bound is ever buffered (tasks.md 4.2).
 */
internal class BoundedInputStream(
    private val delegate: InputStream,
    private val maxBytes: Long,
) : InputStream() {
    private var readSoFar = 0L

    private fun countOrThrow(bytes: Int): Int {
        if (bytes > 0) {
            readSoFar += bytes
            if (readSoFar > maxBytes) throw StreamLimitExceededException(readSoFar)
        }
        return bytes
    }

    override fun read(): Int = delegate.read().also { if (it >= 0) countOrThrow(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = countOrThrow(delegate.read(b, off, len))

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
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

