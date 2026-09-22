package com.example.backlogium.data.credentials

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedCredentialStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun clearStoredCloudCredentials() = runBlocking {
        EncryptedCredentialStore(context).clearCloudCredentials()
    }

    @Test
    fun cloudCredentialFileDoesNotContainRawTokenAndDisplayIsMasked() = runBlocking {
        val store = EncryptedCredentialStore(context)
        val endpoint = "https://reader.example.com/read"
        val token = "test-cloud-reader-token"
        store.clearCloudCredentials()
        store.writeCloudCredentials(endpoint, token)

        assertEquals(CloudCredentials(endpoint, token), store.readCloudCredentials())
        val file = context.filesDir.resolve("datastore/credentials.preferences_pb")
        assertTrue("DataStore should create its file under filesDir", file.exists())
        assertFalse(String(file.readBytes(), Charsets.UTF_8).contains(token))
        assertEquals("".repeat(token.length - 4) + token.takeLast(4), maskCredential(token))
    }
}
