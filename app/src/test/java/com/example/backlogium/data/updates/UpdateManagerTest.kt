package com.example.backlogium.data.updates

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test
    fun digestFailureDeletesArtifactAndNeverInvokesInstaller() = runTest {
        val root = createTempDir(prefix = "backlogium-update-manager")
        val update = AvailableUpdate(
            tag = "v1.8.0",
            versionName = "1.8.0",
            versionCode = 1_008_000L,
            releaseName = "Release",
            releaseNotes = "Notes",
            apkName = "app-release.apk",
            apkUrl = "https://example.test/app.apk",
            checksumUrl = "https://example.test/app.sha256",
        )
        val artifacts = FakeArtifactStore(root, update)
        val installer = RecordingInstaller()
        val manager = UpdateManager(
            artifactStore = artifacts,
            downloader = object : UpdateDownloader {
                override suspend fun download(
                    url: String,
                    destination: File,
                    onProgress: suspend (Long, Long?) -> Unit,
                ) {
                    destination.parentFile?.mkdirs()
                    destination.writeText("corrupt")
                }

                override suspend fun fetchText(url: String): String = "0".repeat(64)
            },
            verifier = object : UpdateVerifier {
                override suspend fun hasMatchingDigest(apk: File, checksumAsset: String) = false
                override fun hasMatchingSigner(apk: File): Boolean = error("signer must not run")
            },
            installer = installer,
        )

        val result = manager.downloadAndInstall(update) { }

        assertTrue(result is UpdateInstallResult.Failed)
        assertFalse(artifacts.artifact.exists())
        assertFalse(installer.called)
        root.deleteRecursively()
    }

    private class FakeArtifactStore(
        root: File,
        update: AvailableUpdate,
    ) : UpdateArtifactStore {
        val artifact = File(root, update.artifactFileName)
        override fun artifactFile(update: AvailableUpdate): File = artifact
        override fun sweep(keep: AvailableUpdate?) = Unit
        override fun delete(update: AvailableUpdate) {
            artifact.delete()
            File(artifact.absolutePath + UPDATE_ARTIFACT_PARTIAL_SUFFIX).delete()
        }
    }

    private class RecordingInstaller : UpdateInstaller {
        var called = false
        override fun canRequestPackageInstalls(): Boolean = true
        override fun openInstallPermissionSettings() = Unit
        override fun install(update: AvailableUpdate, artifact: File): UpdateInstallResult {
            called = true
            return UpdateInstallResult.Started
        }
    }
}
