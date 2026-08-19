package com.example.backlogium.data.updates

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateArtifactStoreTest {
    @Test
    fun sweepRemovesStaleArtifactsAndKeepsCurrentOffer() {
        val directory = createTempDir(prefix = "backlogium-update-artifacts")
        val current = File(directory, "backlogium-update-1.8.0.apk").apply { writeText("current") }
        val stale = File(directory, "backlogium-update-1.7.0.apk").apply { writeText("stale") }
        val unrelated = File(directory, "not-an-update.txt").apply { writeText("keep") }

        sweepUpdateArtifacts(directory, current.name)

        assertTrue(current.exists())
        assertFalse(stale.exists())
        assertTrue(unrelated.exists())
        directory.deleteRecursively()
    }
}
