package com.example.backlogium.data.backup

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SnapshotStoreTest {

    private val context = RuntimeEnvironment.getApplication()
    private val legacyDir get() = File(context.filesDir, "backup_snapshots")
    private val newDir get() = File(context.noBackupFilesDir, "backup_snapshots")
    private val store get() = SnapshotStore(context, Json {})

    @Before
    fun cleanBefore() {
        legacyDir.deleteRecursively()
        newDir.deleteRecursively()
    }

    @After
    fun cleanAfter() {
        legacyDir.deleteRecursively()
        newDir.deleteRecursively()
    }

    @Test
    fun nothingToMigrate_leavesTheNewStoreEmpty() {
        store.migrateLegacySnapshots()

        assertTrue(store.list().isEmpty())
        assertFalse(legacyDir.exists())
    }

    @Test
    fun normalMigration_preservesEverySnapshotAndItsTimestamp() {
        writeLegacy("1000.json", "first")
        writeLegacy("2000.json", "second")

        store.migrateLegacySnapshots()

        assertEquals(listOf("2000.json", "1000.json"), store.list().map { it.fileName })
        assertEquals(listOf(2000L, 1000L), store.list().map { it.writtenAtMillis })
        assertEquals("first", File(newDir, "1000.json").readText())
        assertEquals("second", File(newDir, "2000.json").readText())
        assertFalse(legacyDir.exists())
    }

    @Test
    fun interruptedMigration_convergesOnTheNextRunWithoutPruningTheOnlyOldCopy() {
        writeLegacy("1000.json", "first")
        writeLegacy("2000.json", "second")

        store.migrateLegacySnapshots { source, destination ->
            if (source.name == "2000.json") {
                false
            } else {
                source.copyTo(destination, overwrite = true)
                true
            }
        }
        store.enforceRetention(maxCount = 0)

        assertTrue(File(legacyDir, "2000.json").isFile)
        assertFalse(File(legacyDir, "1000.json").exists())
        assertFalse(File(newDir, "2000.json").exists())

        store.migrateLegacySnapshots()

        assertEquals(listOf("2000.json"), store.list().map { it.fileName })
        assertFalse(legacyDir.exists())
    }

    @Test
    fun failedCopy_keepsTheSourceSnapshotForRetry() {
        writeLegacy("1000.json", "important history")

        store.migrateLegacySnapshots { _, _ -> false }

        assertTrue(File(legacyDir, "1000.json").isFile)
        assertFalse(File(newDir, "1000.json").exists())
    }

    private fun writeLegacy(name: String, contents: String) {
        legacyDir.mkdirs()
        File(legacyDir, name).writeText(contents)
    }
}
