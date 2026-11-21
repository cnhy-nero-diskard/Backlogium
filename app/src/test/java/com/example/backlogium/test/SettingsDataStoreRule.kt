package com.example.backlogium.test

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.backlogium.data.local.SettingsDataStore
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Own the file and writer lifetime; the Context delegate outlives Robolectric's temp contexts. */
class SettingsDataStoreRule : ExternalResource() {
    private val directory = Files.createTempDirectory("backlogium-settings-test").toFile()
    private val job = SupervisorJob()
    private val store = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(Dispatchers.IO + job),
        produceFile = { directory.resolve("settings.preferences_pb") },
    )

    fun create() = SettingsDataStore(store)

    override fun after() {
        runBlocking { job.cancelAndJoin() }
        directory.deleteRecursively()
    }
}
