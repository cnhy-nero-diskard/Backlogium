package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import javax.inject.Inject

interface GameNotificationTargetValidator {
    suspend fun canOpen(account: String, appId: Long): Boolean
}

class GameNotificationTargets @Inject constructor(
    private val credentials: CredentialsProvider,
    private val accountChange: AccountChangeMarkerStore,
    private val database: BacklogiumDatabase,
) : GameNotificationTargetValidator {
    override suspend fun canOpen(account: String, appId: Long): Boolean = accountChange.withAccountState {
        accountChange.pendingSteamId() == null &&
            credentials.currentCredentials()?.steamId == account &&
            database.withTransaction {
                database.playerProfileDao().get()?.steamId == account &&
                    database.gameDao().getById(appId) != null &&
                    !database.hiddenGameDao().isHidden(appId)
            }
    }
}
