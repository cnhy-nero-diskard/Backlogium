package com.example.backlogium.data.updates

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Owns the re-obtainable APK outside Android's backup lifecycle. */
@Singleton
class UpdateArtifactStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val directory: File
        get() = File(context.noBackupFilesDir, DIRECTORY_NAME)

    fun artifactFile(update: AvailableUpdate): File = File(directory, update.artifactFileName)

    fun sweep(keep: AvailableUpdate?) {
        val keepName = keep?.artifactFileName
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name != keepName }
            ?.forEach { it.delete() }
    }

    fun delete(update: AvailableUpdate) {
        artifactFile(update).delete()
        File(artifactFile(update).absolutePath + PARTIAL_SUFFIX).delete()
    }

    companion object {
        const val DIRECTORY_NAME = "updates"
        const val FILE_PREFIX = "backlogium-update-"
        const val PARTIAL_SUFFIX = ".part"
    }
}
