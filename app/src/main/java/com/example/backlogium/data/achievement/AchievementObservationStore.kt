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
    private val recovery: AchievementRecomputeRecovery? = null,
) {
    suspend fun currentCredentials() = credentials.currentCredentials()
    suspend fun currentAccount() = currentCredentials()?.steamId

    suspend fun <T> commit(account: String, appId: Long, valid: () -> Boolean, block: suspend () -> T): T? =
        coordinator.withLock {
            if (!valid() || marker.pendingSteamId() != null ||
                credentials.currentCredentials()?.steamId != account
            ) return@withLock null
            recovery?.resumeWhileLocked()
            val result = database.withTransaction {
                if (!valid() || database.playerProfileDao().get()?.steamId != account ||
                    database.gameDao().getById(appId) == null || database.hiddenGameDao().isHidden(appId)
                ) return@withTransaction null
                block()
            }
            // The raw commit is authoritative even if the follow-up fails: keep its marker and
            // return the committed baseline. The next admitted write/startup retries it first.
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { recovery?.resumeWhileLocked() }
            }
            result
        }
}
