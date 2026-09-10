package com.example.backlogium.data.repo

import com.example.backlogium.data.local.dao.GameDao
import com.example.backlogium.data.local.dao.GameGenreCacheDao
import com.example.backlogium.data.local.dao.HiddenGameDao
import com.example.backlogium.data.local.entity.HiddenGame
import com.example.backlogium.domain.TimeProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One hidden game as the hidden-games list presents it. Carries the stored library identity so the
 * list can name what is hidden — the one surface where a hidden game is deliberately visible,
 * because a game that can be hidden and not found again is a trap.
 *
 * [name] falls back to the app id when the game is no longer in the library: a hide outlives
 * ownership by design, and an unnamed row still has to be unhideable.
 */
data class HiddenGameEntry(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val hiddenAt: Long,
    val fromBulkAction: Boolean,
)

/**
 * One library item the store reports as something other than a game — the shape the bulk review
 * offers for confirmation. [typeLabel] is the store's own word for it, shown so the player can see
 * *why* it was proposed rather than being asked to trust a classification.
 */
data class NonGameCandidate(
    val appId: Long,
    val name: String,
    val iconUrl: String,
    val typeLabel: String,
)

/**
 * The hidden set and the only writes that change it (add-hidden-games).
 *
 * Every other read path in the app excludes hidden games by consuming [hiddenAppIds] at its own
 * repository boundary, so no screen has to remember to filter — see [GameRepository],
 * [SessionRepository], [CollectionRepository], and [AchievementRepository].
 *
 * Hiding here is *only* the visibility write. The retroactive XP consequence belongs to
 * [com.example.backlogium.domain.GameVisibilityUseCase], which pairs this write with the
 * recompute; callers must use that rather than writing visibility directly, for the same reason
 * a rule change goes through its use case.
 */
@Singleton
class HiddenGamesRepository @Inject constructor(
    private val hiddenGameDao: HiddenGameDao,
    private val gameDao: GameDao,
    private val storeCacheDao: GameGenreCacheDao,
    private val time: TimeProvider,
) {
    /** Every hidden app id, as the exclusion joins consume it. */
    val hiddenAppIds: Flow<Set<Long>> =
        hiddenGameDao.observeAll().map { rows -> rows.mapTo(mutableSetOf()) { it.appId } }

    /** The hidden games as the settings list renders them, most recently hidden first. */
    val hiddenGames: Flow<List<HiddenGameEntry>> =
        hiddenGameDao.observeAll().combine(gameDao.observeAllGames()) { hidden, games ->
            val gamesByAppId = games.associateBy { it.appId }
            hidden.map { row ->
                val game = gamesByAppId[row.appId]
                HiddenGameEntry(
                    appId = row.appId,
                    name = game?.name?.takeIf { it.isNotBlank() } ?: "App ${row.appId}",
                    iconUrl = game?.iconUrl.orEmpty(),
                    hiddenAt = row.hiddenAt,
                    fromBulkAction = row.fromBulkAction,
                )
            }
        }

    /**
     * Visible library items whose recorded store type is not a game. Items whose type has not been
     * retrieved are absent — never assumed to be a game or a non-game — and nothing here is hidden
     * until the player confirms it.
     */
    val nonGameCandidates: Flow<List<NonGameCandidate>> =
        storeCacheDao.observeNonGameCandidates().map { rows ->
            rows.map { row ->
                NonGameCandidate(
                    appId = row.appId,
                    name = row.name.takeIf { it.isNotBlank() } ?: "App ${row.appId}",
                    iconUrl = row.iconUrl,
                    typeLabel = row.appType.orEmpty(),
                )
            }
        }

    suspend fun hiddenAppIdSet(): Set<Long> = hiddenGameDao.hiddenAppIds().toSet()

    suspend fun isHidden(appId: Long): Boolean = hiddenGameDao.isHidden(appId)

    /** Hide [appIds]. Re-hiding an already hidden game refreshes nothing the player can see. */
    suspend fun hide(appIds: Collection<Long>, fromBulkAction: Boolean = false) {
        if (appIds.isEmpty()) return
        val now = time.nowMillis()
        hiddenGameDao.upsertAll(
            appIds.distinct().map { HiddenGame(appId = it, hiddenAt = now, fromBulkAction = fromBulkAction) },
        )
    }

    /** Unhide [appIds]. Nothing else is written: the game's own rows were never removed. */
    suspend fun unhide(appIds: Collection<Long>) {
        if (appIds.isEmpty()) return
        hiddenGameDao.delete(appIds.distinct())
    }

    suspend fun unhideAll() = hiddenGameDao.deleteAll()
}
