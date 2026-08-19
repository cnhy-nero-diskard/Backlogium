package com.example.backlogium.data.updates

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
            updateStateStore = FakeUpdateStateStore(),
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

    private class FakeUpdateStateStore : UpdateStateStore {
        private val stateFlow = MutableStateFlow(AppUpdateState())
        override val state: Flow<AppUpdateState> = stateFlow

        override suspend fun recordAttempt(atMillis: Long) = Unit
        override suspend fun recordCheck(
            atMillis: Long,
            seenTag: String?,
            available: AvailableUpdate?,
        ) = Unit
        override suspend fun setDeclinedTag(tag: String) = Unit
        override suspend fun clearAvailable() = Unit
        override suspend fun markInstallStarted(tag: String) = Unit
        override suspend fun markInstallPending(tag: String) = Unit
        override suspend fun markInstallFailed(tag: String, message: String) = Unit
        override suspend fun clearInstallStatus() = Unit
    }
}
