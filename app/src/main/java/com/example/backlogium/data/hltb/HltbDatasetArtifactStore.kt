package com.example.backlogium.data.hltb

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface HltbDatasetArtifactStore {
    fun stagingFile(): File
    fun clearStaging()
}

/** Owns the replaceable verified-download staging area outside Android backup. */
@Singleton
class FileHltbDatasetArtifactStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : HltbDatasetArtifactStore {
    private val directory: File
        get() = File(context.noBackupFilesDir, DIRECTORY_NAME)

    override fun stagingFile(): File = File(directory, STAGING_NAME)

    override fun clearStaging() {
        val staging = stagingFile()
        staging.delete()
        File(staging.absolutePath + ".part").delete()
    }

    private companion object {
        const val DIRECTORY_NAME = "hltb-dataset"
        const val STAGING_NAME = "incoming.json"
    }
}
