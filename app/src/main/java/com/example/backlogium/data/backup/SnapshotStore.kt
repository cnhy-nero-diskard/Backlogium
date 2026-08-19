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
 * (design.md decision 4) — plain files under [Context.getNoBackupFilesDir], never exposed outside
 * the app's own sandbox or the platform backup channels. Each snapshot is named by its own write
 * time so listing/retention needs no separate index file.
 */
@Singleton
class SnapshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val dir: File
        get() = File(context.noBackupFilesDir, DIR_NAME).apply { mkdirs() }

    /**
     * Move snapshots written by older versions out of [Context.getFilesDir]. A source file is
     * deleted only after a complete copy has been verified, and failed files stay in place for a
     * retry on the next process start. The optional copier exists for focused failure tests; its
     * result must mean that the destination contains a verified copy.
     */
    fun migrateLegacySnapshots() {
        migrateLegacySnapshots(::copyVerified)
    }

    internal fun migrateLegacySnapshots(copy: (File, File) -> Boolean) {
        val legacyDir = File(context.filesDir, DIR_NAME)
        if (!legacyDir.isDirectory) return

        val destinationDir = dir
        legacyDir.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            ?.forEach { source ->
                val destination = File(destinationDir, source.name)
                val copied = runCatching { copy(source, destination) }.getOrDefault(false)
                if (copied && destination.isFile && destination.length() == source.length()) {
                    source.delete()
                }
            }

        if (legacyDir.listFiles().isNullOrEmpty()) {
            legacyDir.delete()
        }
    }

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

    /** Delete one retained snapshot, refusing path-like names that did not come from [list]. */
    fun delete(fileName: String): Boolean {
        if (fileName != File(fileName).name) return false
        return File(dir, fileName).delete()
    }

    /** Discard the oldest snapshots beyond [maxCount], keeping the most recent [maxCount]. */
    fun enforceRetention(maxCount: Int) {
        list().drop(maxCount.coerceAtLeast(0)).forEach { File(dir, it.fileName).delete() }
    }

    private fun copyVerified(source: File, destination: File): Boolean {
        val temporary = File(destination.parentFile, ".${destination.name}.migrating")
        return try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (temporary.length() != source.length() || !sameContents(source, temporary)) {
                false
            } else {
                if (destination.exists() && !destination.delete()) return false
                if (!temporary.renameTo(destination)) return false
                if (source.lastModified() > 0L) destination.setLastModified(source.lastModified())
                sameContents(source, destination)
            }
        } catch (_: Exception) {
            false
        } finally {
            temporary.delete()
        }
    }

    private fun sameContents(first: File, second: File): Boolean {
        if (first.length() != second.length()) return false
        val firstBuffer = ByteArray(COPY_BUFFER_SIZE)
        val secondBuffer = ByteArray(COPY_BUFFER_SIZE)
        first.inputStream().use { firstInput ->
            second.inputStream().use { secondInput ->
                while (true) {
                    val firstRead = firstInput.read(firstBuffer)
                    val secondRead = secondInput.read(secondBuffer)
                    if (firstRead != secondRead) return false
                    if (firstRead < 0) return true
                    if (!firstBuffer.contentEquals(secondBuffer)) return false
                }
            }
        }
    }

    companion object {
        private const val DIR_NAME = "backup_snapshots"
        private const val EXTENSION = ".json"
        private const val COPY_BUFFER_SIZE = 8 * 1024
    }
}
