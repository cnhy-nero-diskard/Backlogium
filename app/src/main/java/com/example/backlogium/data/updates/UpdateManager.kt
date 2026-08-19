package com.example.backlogium.data.updates

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

interface AppUpdateManager {
    suspend fun downloadAndInstall(
        update: AvailableUpdate,
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateInstallResult
}

@Singleton
class UpdateManager @Inject constructor(
    private val artifactStore: UpdateArtifactStore,
    private val downloader: UpdateDownloader,
    private val verifier: UpdateVerifier,
    private val installer: UpdateInstaller,
    private val updateStateStore: UpdateStateStore,
) : AppUpdateManager {
    override suspend fun downloadAndInstall(
        update: AvailableUpdate,
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateInstallResult {
        updateStateStore.clearInstallStatus()
        if (!installer.canRequestPackageInstalls()) {
            runCatching { installer.openInstallPermissionSettings() }
            return UpdateInstallResult.PermissionRequired
        }

        val artifact = artifactStore.artifactFile(update)
        return try {
            downloader.download(update.apkUrl, artifact) { bytesRead, totalBytes ->
                onProgress(UpdateProgress.Downloading(bytesRead, totalBytes))
            }
            onProgress(UpdateProgress.VerifyingDigest)
            val checksum = downloader.fetchText(update.checksumUrl)
            if (!verifier.hasMatchingDigest(artifact, checksum)) {
                artifactStore.delete(update)
                return UpdateInstallResult.Failed("The downloaded update failed its checksum check.")
            }

            onProgress(UpdateProgress.VerifyingSigner)
            if (!verifier.hasMatchingSigner(artifact)) {
                artifactStore.delete(update)
                return UpdateInstallResult.Failed("The downloaded update was signed by another key.")
            }

            onProgress(UpdateProgress.Installing)
            updateStateStore.markInstallStarted(update.tag)
            when (val result = installer.install(update, artifact)) {
                UpdateInstallResult.Started -> result
                UpdateInstallResult.PermissionRequired -> {
                    artifactStore.delete(update)
                    updateStateStore.clearInstallStatus()
                    result
                }
                is UpdateInstallResult.Failed -> {
                    artifactStore.delete(update)
                    updateStateStore.clearInstallStatus()
                    result
                }
            }
        } catch (cancellation: CancellationException) {
            artifactStore.delete(update)
            updateStateStore.clearInstallStatus()
            throw cancellation
        } catch (failure: Exception) {
            artifactStore.delete(update)
            updateStateStore.clearInstallStatus()
            UpdateInstallResult.Failed(failure.message ?: "The update download failed.")
        }
    }
}
