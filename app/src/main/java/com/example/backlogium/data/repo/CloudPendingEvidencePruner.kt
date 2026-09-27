package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.PendingCloudEvidenceDao
import com.example.backlogium.domain.CloudReaderIdentity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Retires generation-bound evidence only as part of a validated Steam baseline/session commit. */
class CloudPendingEvidencePruner @Inject constructor(
    private val dao: PendingCloudEvidenceDao,
    private val credentials: CredentialsProvider,
    private val settings: SettingsRepository,
    private val readerStateMutex: CloudReaderStateMutex = CloudReaderStateMutex(),
) {
    /**
     * Holds the same fence used by reader promotion until the caller's Room commit completes.
     * Stale evidence is rejected for placement, while a committed Steam baseline still retires
     * evidence belonging to the active reader.
     */
    suspend fun <T> withValidatedEvidence(
        evidenceIdentity: CloudReaderIdentity?,
        block: suspend (validatedEvidenceIdentity: CloudReaderIdentity?, pruningIdentity: CloudReaderIdentity?) -> T,
    ): T = readerStateMutex.mutex.withLock {
        val account = credentials.currentCredentials()?.steamId
        val activeIdentity = account?.let {
            CloudReaderIdentity(it, settings.cloudReaderGeneration.first())
        }
        val validatedIdentity = evidenceIdentity?.takeIf { it == activeIdentity }
        block(validatedIdentity, activeIdentity)
    }

    suspend fun afterBaseline(identity: CloudReaderIdentity?, appId: Long, baselineAt: Long) {
        identity ?: return
        dao.pruneClosed(identity.account, identity.readerGeneration, appId, baselineAt)
    }
}
