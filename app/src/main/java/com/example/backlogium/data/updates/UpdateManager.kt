package com.example.backlogium.data.updates

import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateManager @Inject constructor(
    private val artifactStore: UpdateArtifactStore,
    private val downloader: UpdateDownloader,
    private val verifier: UpdateVerifier,
    private val installer: UpdateInstaller,
) {
    suspend fun downloadAndInstall(
        update: AvailableUpdate,
        onProgress: (UpdateProgress) -> Unit,
    ): UpdateInstallResult {
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
            when (val result = installer.install(update, artifact)) {
                UpdateInstallResult.Started -> result
                UpdateInstallResult.PermissionRequired -> {
                    artifactStore.delete(update)
                    result
                }
                is UpdateInstallResult.Failed -> {
                    artifactStore.delete(update)
                    result
                }
            }
        } catch (cancellation: CancellationException) {
            artifactStore.delete(update)
            throw cancellation
        } catch (failure: Exception) {
            artifactStore.delete(update)
            UpdateInstallResult.Failed(failure.message ?: "The update download failed.")
        }
    }
}
