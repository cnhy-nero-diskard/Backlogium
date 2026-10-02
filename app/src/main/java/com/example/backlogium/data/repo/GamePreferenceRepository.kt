package com.example.backlogium.data.repo

import androidx.room.withTransaction
import com.example.backlogium.data.local.BacklogiumDatabase
import com.example.backlogium.data.local.entity.GamePreference
import com.example.backlogium.domain.GameFavorite
import com.example.backlogium.domain.GameArtworkPreference
import com.example.backlogium.domain.GameArtworkVariant
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

    val artworkByAppId: Flow<Map<Long, GameArtworkVariant>> = database.gamePreferenceDao().observeAll()
        .map { rows -> rows.mapNotNull { row ->
            GameArtworkVariant.fromToken(row.artworkVariant)?.let { row.appId to it }
        }.toMap() }

    fun artwork(appId: Long): Flow<GameArtworkPreference> = combine(
        database.gamePreferenceDao().observe(appId), database.playerProfileDao().observe(),
    ) { preference, profile ->
        GameArtworkPreference(appId, GameArtworkVariant.fromToken(preference?.artworkVariant), profile?.steamId.orEmpty())
    }

    suspend fun setArtwork(appId: Long, variant: GameArtworkVariant?, steamId: String) {
        database.withTransaction {
            checkAvailable(appId, steamId)
            val prior = database.gamePreferenceDao().get(appId) ?: GamePreference(appId, false)
            database.gamePreferenceDao().upsert(prior.copy(artworkVariant = variant?.name))
        }
    }

    suspend fun setFavorite(appId: Long, favorite: Boolean, steamId: String) {
        database.withTransaction {
            checkAvailable(appId, steamId)
            val prior = database.gamePreferenceDao().get(appId) ?: GamePreference(appId, false)
            database.gamePreferenceDao().upsert(prior.copy(isFavorite = favorite))
        }
    }

    private suspend fun checkAvailable(appId: Long, steamId: String) {
        identity.check(steamId)
        require(appId > 0 && database.gameDao().getById(appId) != null &&
            !database.hiddenGameDao().isHidden(appId)
        ) { "Game is no longer available. Reopen this screen." }
    }
}
