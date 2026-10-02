package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.GamePreference
import com.example.backlogium.domain.GameFavorite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GamePreferenceRepository @Inject constructor(
    private val database: BacklogiumDatabase,
    private val identity: AccountDataWriteGuard,
) {
    val favoriteAppIds: Flow<Set<Long>> = database.gamePreferenceDao().observeAll().map { rows ->
        rows.filter { it.isFavorite }.mapTo(mutableSetOf()) { it.appId }
    }

    fun favorite(appId: Long): Flow<GameFavorite> = combine(
        database.gamePreferenceDao().observe(appId), database.playerProfileDao().observe(),
    ) { preference, profile -> GameFavorite(appId, preference?.isFavorite == true, profile?.steamId.orEmpty()) }

    suspend fun setFavorite(appId: Long, favorite: Boolean, steamId: String) {
        database.withTransaction {
            identity.check(steamId)
            require(appId > 0 && database.gameDao().getById(appId) != null &&
                !database.hiddenGameDao().isHidden(appId)
            ) { "Game is no longer available. Reopen this screen." }
            database.gamePreferenceDao().upsert(GamePreference(appId, favorite))
        }
    }
}
