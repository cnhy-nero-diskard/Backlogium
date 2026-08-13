package com.example.backlogium.data.repo

import com.example.backlogium.data.local.SettingsDataStore
import com.example.backlogium.domain.ProgressMarks
import com.example.backlogium.domain.ProgressMarksStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Production [ProgressMarksStore] backed by Preferences DataStore. */
@Singleton
class DataStoreProgressMarksStore @Inject constructor(
    private val settings: SettingsDataStore,
) : ProgressMarksStore {
    override val marks: Flow<ProgressMarks> = settings.progressMarksFlow

    override suspend fun read(): ProgressMarks = settings.readProgressMarks()

    override suspend fun write(marks: ProgressMarks) = settings.writeProgressMarks(marks)
}
