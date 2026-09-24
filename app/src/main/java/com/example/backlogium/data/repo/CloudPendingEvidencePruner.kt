package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.PendingCloudEvidenceDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** Called inside the caller's Steam baseline/session transaction, never on a reader request. */
class CloudPendingEvidencePruner @Inject constructor(
    private val dao: PendingCloudEvidenceDao,
    private val credentials: CredentialsProvider,
    private val settings: SettingsRepository,
) {
    suspend fun afterBaseline(appId: Long, baselineAt: Long) {
        val account = credentials.currentCredentials()?.steamId ?: return
        val generation = settings.cloudReaderGeneration.first()
        dao.pruneClosed(account, generation, appId, baselineAt)
    }
}
