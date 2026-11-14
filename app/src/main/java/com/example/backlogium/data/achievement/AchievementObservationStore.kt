package com.example.backlogium.data.achievement

import androidx.room.withTransaction
import com.example.backlogium.data.credentials.AccountChangeMarkerStore
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.repo.CredentialsProvider
import com.example.backlogium.work.SteamSyncCoordinator
import javax.inject.Inject
import javax.inject.Singleton

/** Account reset and all ordinary achievement writers share the sync admission barrier. */
@Singleton
class AchievementObservationStore @Inject constructor(
    val database: BacklogiumDatabase,
    private val credentials: CredentialsProvider,
    private val marker: AccountChangeMarkerStore,
    private val coordinator: SteamSyncCoordinator,
) {
    suspend fun currentCredentials() = credentials.currentCredentials()
    suspend fun currentAccount() = currentCredentials()?.steamId

    suspend fun <T> commit(account: String, appId: Long, valid: () -> Boolean, block: suspend () -> T): T? =
        coordinator.withLock {
            if (!valid() || marker.pendingSteamId() != null ||
                credentials.currentCredentials()?.steamId != account
            ) return@withLock null
            database.withTransaction {
                if (!valid() || database.playerProfileDao().get()?.steamId != account ||
                    database.gameDao().getById(appId) == null || database.hiddenGameDao().isHidden(appId)
                ) return@withTransaction null
                block()
            }
        }
}
