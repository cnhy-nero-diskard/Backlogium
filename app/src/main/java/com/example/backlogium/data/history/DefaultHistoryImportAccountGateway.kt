package com.example.backlogium.data.history

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.domain.HistoryImportAccountGateway
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production [HistoryImportAccountGateway]: the active account comes from the resolved credentials,
 * and the durable account-change marker reports any reset in flight (stabilize-first-run-setup).
 *
 * Both reads stay cheap and are made under the Steam-sync coordinator by the import so they are
 * coherent with every account-change reset, which serializes on the same lock.
 */
@Singleton
class DefaultHistoryImportAccountGateway @Inject constructor(
    private val credentials: CredentialsProvider,
    private val accountChangeMarker: AccountChangeMarkerStore,
) : HistoryImportAccountGateway {

    override suspend fun activeSteamId(): String? = credentials.currentCredentials()?.steamId

    override suspend fun pendingResetSteamId(): String? = accountChangeMarker.pendingSteamId()
}