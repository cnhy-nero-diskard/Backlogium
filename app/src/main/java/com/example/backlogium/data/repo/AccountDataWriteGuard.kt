package com.example.backlogium.data.repo

import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Check inside the same Room transaction as the write, so reset cannot interleave. */
interface AccountDataWriteGuard {
    suspend fun capture(): String
    suspend fun check(steamId: String)
}

@Singleton
class RoomAccountDataWriteGuard @Inject constructor(
    private val database: BacklogiumDatabase,
    private val marker: AccountChangeMarkerStore,
    private val credentials: CredentialsProvider,
) : AccountDataWriteGuard {
    override suspend fun capture(): String {
        check(marker.pendingSteamId() == null) { "Account change in progress. Reopen this screen." }
        return database.playerProfileDao().get()?.steamId.orEmpty()
    }

    override suspend fun check(steamId: String) {
        check(marker.pendingSteamId() == null &&
            database.playerProfileDao().get()?.steamId.orEmpty() == steamId &&
            credentials.currentCredentials()?.steamId.orEmpty() == steamId
        ) { "Account changed. Reopen this screen and retry." }
    }
}
