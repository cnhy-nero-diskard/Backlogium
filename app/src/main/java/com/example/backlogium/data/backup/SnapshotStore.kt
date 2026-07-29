package com.example.backlogium.data.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One retained automatic snapshot: its file name (also its written-at epoch millis) and time. */
data class SnapshotMeta(val fileName: String, val writtenAtMillis: Long)

/**
 * App-private (not user-visible, no SAF prompt) storage for automatic rolling snapshots
 * (design.md decision 4) — plain files under [Context.getFilesDir], never exposed outside the
 * app's own sandbox. Each snapshot is named by its own write time so listing/retention needs no
 * separate index file.
 */
@Singleton
class SnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val dir: File
        get() = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Retained snapshots, most recent first. */
    fun list(): List<SnapshotMeta> = dir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
        ?.mapNotNull { f -> f.name.removeSuffix(EXTENSION).toLongOrNull()?.let { SnapshotMeta(f.name, it) } }
        ?.sortedByDescending { it.writtenAtMillis }
        ?: emptyList()

    fun newestWrittenAtMillis(): Long? = list().maxOfOrNull { it.writtenAtMillis }

    fun write(file: BackupFile, nowMillis: Long) {
        File(dir, "$nowMillis$EXTENSION").writeText(json.encodeToString(BackupFile.serializer(), file))
    }

    fun read(fileName: String): BackupFile? {
        val target = File(dir, fileName)
        if (!target.isFile) return null
        return runCatching { json.decodeFromString(BackupFile.serializer(), target.readText()) }
            .getOrNull()
    }

    /** Discard the oldest snapshots beyond [maxCount], keeping the most recent [maxCount]. */
    fun enforceRetention(maxCount: Int) {
        list().drop(maxCount.coerceAtLeast(0)).forEach { File(dir, it.fileName).delete() }
    }

    companion object {
        private const val DIR_NAME = "backup_snapshots"
        private const val EXTENSION = ".json"
    }
}
