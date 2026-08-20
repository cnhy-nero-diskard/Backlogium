package com.example.backlogium.data.updates

import java.io.File
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateDataStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        dataStoreFile = File(context.filesDir, "datastore/app_updates.preferences_pb")
        dataStoreFile.delete()
    }

    @After
    fun tearDown() {
        dataStoreFile.delete()
    }

    @Test
    fun structuredPresentationSurvivesStateRoundTripAndLegacyBodyIsSanitized() = runTest {
        val notes = ReleaseNotesPresentation(
            schemaVersion = ReleaseNotesContract.SCHEMA_VERSION,
            tag = "v1.8.0",
            sections = listOf(
                ReleaseNoteSection("features", "Features", listOf("A readable update.")),
            ),
        )
        val update = AvailableUpdate(
            tag = "v1.8.0",
            versionName = "1.8.0",
            versionCode = 1_008_000L,
            releaseName = "Backlogium 1.8.0",
            releaseNotes = "## Changed\n* fix: Keep offline progress by @user",
            apkName = "Backlogium-1.8.0.apk",
            apkUrl = "https://example.test/app.apk",
            checksumUrl = "https://example.test/app.sha256",
            structuredNotes = notes,
        )
        val store = UpdateDataStore(context)

        store.recordCheck(1L, update.tag, update)

        val restored = store.state.first().available
        assertNotNull(restored)
        assertEquals(notes, restored?.structuredNotes)
        assertEquals("Keep offline progress", restored?.releaseNotes)
    }
}
