package com.example.backlogium.data.updates

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

const val UPDATE_ARTIFACT_PARTIAL_SUFFIX = ".part"

interface UpdateArtifactStore {
    fun artifactFile(update: AvailableUpdate): File

    fun sweep(keep: AvailableUpdate?)

    fun delete(update: AvailableUpdate)
}

/** Owns the re-obtainable APK outside Android's backup lifecycle. */
@Singleton
class FileUpdateArtifactStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateArtifactStore {
    private val directory: File
        get() = File(context.noBackupFilesDir, DIRECTORY_NAME)

    override fun artifactFile(update: AvailableUpdate): File = File(directory, update.artifactFileName)

    override fun sweep(keep: AvailableUpdate?) {
        sweepUpdateArtifacts(directory, keep?.artifactFileName)
    }

    override fun delete(update: AvailableUpdate) {
        artifactFile(update).delete()
        File(artifactFile(update).absolutePath + UPDATE_ARTIFACT_PARTIAL_SUFFIX).delete()
    }

    private companion object {
        const val DIRECTORY_NAME = "updates"
    }
}

internal fun sweepUpdateArtifacts(directory: File, keepName: String?) {
    directory.listFiles()
        ?.filter {
            it.isFile && it.name.startsWith("backlogium-update-") && it.name != keepName
        }
        ?.forEach { it.delete() }
}
