package com.example.backlogium.data.setup

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.repo.CredentialsRepository
import com.example.backlogium.data.repo.CredentialsState
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

interface SetupAccounts {
    val owners: Flow<SetupOwner?>
    suspend fun <T> ifCurrent(owner: SetupOwner, block: suspend () -> T): T?
}

class CredentialSetupAccounts @Inject constructor(
    private val credentials: CredentialsRepository,
    private val marker: AccountChangeMarkerStore,
) : SetupAccounts {
    override val owners: Flow<SetupOwner?> =
        combine(credentials.credentialsStateFlow, marker.activeGeneration) { state, generation ->
            if (generation != null && state is CredentialsState.Configured) {
                SetupOwner(state.steamId, generation)
            } else null
        }.distinctUntilChanged()

    override suspend fun <T> ifCurrent(owner: SetupOwner, block: suspend () -> T): T? = marker.withAccountState {
        if (marker.pendingSteamId() != null || marker.generation() != owner.generation ||
            credentials.currentCredentials()?.steamId != owner.steamId
        ) return@withAccountState null
        block()
    }
}
